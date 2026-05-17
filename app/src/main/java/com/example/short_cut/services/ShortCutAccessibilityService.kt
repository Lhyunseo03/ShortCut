package com.example.short_cut.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.short_cut.R
import com.example.short_cut.db.AppDatabase
import com.example.short_cut.db.ScrollHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// 마지막으로 체크한 hourly 스크롤 횟수 — violation 전송 시 사용
private var lastHourlyCount = 0

class ShortCutAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortCut"
        const val TARGET_PACKAGE = "com.google.android.youtube"
        const val SHORTS_NODE_ID = "com.google.android.youtube:id/reel_player_page_container"
        const val DEBOUNCE_MS = 500L
        const val NOISE_MS = 1500L

        // 그만보기(Stop) 선택 후 쇼츠 진입 차단 시간 — 5분
        const val STOP_BLOCK_MS = 5 * 60 * 1000L

        // hourly limit 초과 후 추가 popup 발생 간격 (10회당)
        const val HOURLY_STEP = 10

        // daily limit 초과 후 추가 popup 발생 간격 (100회당)
        const val DAILY_STEP = 100

        // SharedPreferences 키 — 영구 상태 (서비스 재시작/YT 강제종료 대비)
        const val PREFS = "short_cut_prefs"
        const val PK_DAILY_MILESTONE = "dailyMilestone"        // 직전 popup 트리거된 dailyCount 값 (-1=미발생)
        const val PK_HOURLY_MILESTONE = "hourlyMilestone"      // 직전 popup 트리거된 hourlyCount 값 (-1=미발생)
        const val PK_STOP_UNTIL = "stopUntilMs"                // 쇼츠 진입 차단 만료 시각 (0=차단 없음)
        const val PK_PENDING_TYPE = "pendingPopupType"         // "daily"/"hourly"/null — 현재 미응답 popup 종류
        const val PK_PENDING_OVERAGE = "pendingPopupOverage"   // 현재 미응답 popup 의 overage (0,10,20.../0,100,200...)
        const val PK_TODAY_START = "todayStartMs"              // 마지막으로 처리한 "오늘 0시" — 날짜 롤오버 감지용
    }

    // ── Room DB ───────────────────────────────────────────────
    private lateinit var scrollHistoryDao: com.example.short_cut.db.ScrollHistoryDao
    private lateinit var userLimitDao: com.example.short_cut.db.UserLimitDao

    // 코루틴 스코프 — DB 작업은 메인 스레드에서 실행하면 안 되므로 별도 스코프 사용
    // SupervisorJob: 하나의 코루틴이 실패해도 나머지에 영향 없음
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Limit 설정 ────────────────────────────────────────────
    private var hourlyLimit = 50
    private var dailyLimit = 100

    // ── 카운터 ────────────────────────────────────────────────
    // 오늘 총 스크롤 횟수 — 앱 재시작 시 Room DB 에서 복원, 자정 롤오버 시 0으로 재로드
    private var dailyCount = 0

    // 직전 popup 이 발생한 카운트 값. -1 이면 popup 미발생 상태.
    //  - 다음 daily popup 트리거: max(dailyLimit, dailyMilestone + DAILY_STEP)
    //  - 다음 hourly popup 트리거: max(hourlyLimit, hourlyMilestone + HOURLY_STEP)
    private var dailyMilestone = -1
    private var hourlyMilestone = -1

    // 미응답 popup — YT 강제종료 등으로 popup view 가 사라져도 재진입 시 같은 popup 복원
    // null 이면 미응답 popup 없음
    private var pendingPopupType: String? = null
    private var pendingPopupOverage = 0

    // 그만보기(Stop) 선택 후 쇼츠 진입 차단 만료 시각 (Unix ms). 0 = 차단 없음.
    private var stopUntilMs = 0L

    // 마지막으로 처리한 "오늘 0시" — 자정 롤오버 감지에 사용
    private var todayStartMs = 0L

    // ── 스크롤 감지 상태 ──────────────────────────────────────
    private var isInShortsMode = false
    private var lastCountTime = 0L
    private var shortsEnteredTime = 0L
    private var lastNodeCount = 0
    private var lastContentDesc = ""
    private var isPopupShowing = false
    private var windowManager: WindowManager? = null
    // popup view 들 — variant 4(stacked) 를 위해 list 로 관리. 단일 popup variant 에서도 list 에 1개로 저장.
    private val popupViews = mutableListOf<View>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 배치 전송 ─────────────────────────────────────────────
    private var batchScrollCount = 0
    private val BATCH_SIZE = 10
    private val batchHandler = Handler(Looper.getMainLooper())
    private val batchTimerRunnable = Runnable {
        flushBatch()
        scheduleBatchTimer()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            // packageNames 를 비워서 다른 앱 이벤트도 받음 — YT 가 포그라운드에서 벗어나면 popup 만 dismiss
            // (pending 상태는 유지되어 YT/쇼츠 재진입 시 popup 복원)
            packageNames = null
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
        serviceInfo = info
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val db = AppDatabase.getDatabase(this)
        scrollHistoryDao = db.scrollHistoryDao()
        userLimitDao = db.userLimitDao()

        serviceScope.launch {
            initializeOnStart()
        }

        Log.d(TAG, "서비스 연결 | API ${android.os.Build.VERSION.SDK_INT}")

        scheduleBatchTimer()
    }

    // 서비스 시작 시 Room DB / SharedPreferences 에서 상태 복원
    private suspend fun initializeOnStart() {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 1주일 이상 된 스크롤 기록 삭제
        val oneWeekAgo = now - (7 * 24 * 60 * 60 * 1000L)
        scrollHistoryDao.deleteOlderThan(oneWeekAgo)

        // 오늘 자정 이후 스크롤 횟수를 Room DB 에서 불러와 dailyCount 복원
        todayStartMs = getStartOfDayTimestamp()
        dailyCount = scrollHistoryDao.countToday(todayStartMs)
        Log.d(TAG, "오늘 스크롤 복원: $dailyCount")

        // userId 변경 감지 → 다른 유저 데이터 흔적 제거
        val userId = prefs.getString("userId", "unknown") ?: "unknown"
        val lastUserId = prefs.getString("lastUserId", "") ?: ""
        if (userId != lastUserId) {
            scrollHistoryDao.deleteOlderThan(now + 1L) // 전체 삭제
            prefs.edit()
                .putString("lastUserId", userId)
                .remove(PK_DAILY_MILESTONE)
                .remove(PK_HOURLY_MILESTONE)
                .remove(PK_PENDING_TYPE)
                .remove(PK_PENDING_OVERAGE)
                .remove(PK_STOP_UNTIL)
                .apply()
            dailyCount = 0
            Log.d(TAG, "userId 변경 감지 → 상태 초기화 ($lastUserId → $userId)")
        }

        // pending limit 변경 예약이 만료됐으면 promote
        userLimitDao.promoteExpiredPending(now)
        val userLimit = userLimitDao.getLimit(userId)
        if (userLimit != null) {
            hourlyLimit = userLimit.hourlyLimit
            dailyLimit = userLimit.dailyLimit
            Log.d(TAG, "limit 로드 — hourly: $hourlyLimit, daily: $dailyLimit")
        }

        // 영구 상태 복원
        dailyMilestone = prefs.getInt(PK_DAILY_MILESTONE, -1)
        hourlyMilestone = prefs.getInt(PK_HOURLY_MILESTONE, -1)
        stopUntilMs = prefs.getLong(PK_STOP_UNTIL, 0L)
        pendingPopupType = prefs.getString(PK_PENDING_TYPE, null)
        pendingPopupOverage = prefs.getInt(PK_PENDING_OVERAGE, 0)

        // milestone 유효성 검증 — count 가 milestone 보다 낮으면 stale 이므로 리셋
        // (자정 롤오버나 hourly 슬라이딩 윈도우로 인해 count 가 줄어든 경우)
        if (dailyMilestone >= 0 && dailyCount < dailyMilestone) {
            dailyMilestone = -1
        }
        val hourlyAtStart = scrollHistoryDao.countLastHour(now - 60L * 60L * 1000L)
        if (hourlyMilestone >= 0 && hourlyAtStart < hourlyMilestone) {
            hourlyMilestone = -1
        }

        // 만료된 stopUntilMs 정리
        if (stopUntilMs > 0 && now >= stopUntilMs) {
            stopUntilMs = 0
        }

        savePersistedState()
        Log.d(TAG, "상태 복원 — dailyMilestone=$dailyMilestone, hourlyMilestone=$hourlyMilestone, stopUntilMs=$stopUntilMs, pending=$pendingPopupType($pendingPopupOverage)")
    }

    // 오늘 자정(00:00:00) 타임스탬프 계산
    private fun getStartOfDayTimestamp(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // 자정 롤오버 처리 — countShorts() 진입 시 매번 호출
    // 날짜가 바뀌었으면 dailyCount 와 milestone 을 새 날짜 기준으로 재설정
    private suspend fun handleDayRolloverIfNeeded(now: Long, userId: String) {
        val startToday = getStartOfDayTimestamp()
        if (startToday == todayStartMs) return

        Log.d(TAG, "자정 롤오버 감지 → 상태 재설정")
        todayStartMs = startToday
        dailyCount = scrollHistoryDao.countToday(startToday)
        dailyMilestone = -1

        // pending limit 변경이 있었다면 새 날짜에 맞춰 적용
        userLimitDao.promoteExpiredPending(now)
        userLimitDao.getLimit(userId)?.let {
            hourlyLimit = it.hourlyLimit
            dailyLimit = it.dailyLimit
        }
        savePersistedState()
    }

    private fun getShortsNodeCount(): Int {
        val root = rootInActiveWindow ?: return 0
        val nodes = root.findAccessibilityNodeInfosByViewId(SHORTS_NODE_ID)
        val count = nodes?.size ?: 0
        root.recycle()
        return count
    }

    // 활성 window 가 아니어도 (댓글 시트, 필터 드롭다운 등 오버레이 뒤에) 쇼츠 컨테이너가 어떤 window 에든
    // 살아있는지 확인. 쇼츠 모드 이탈 판정에 사용 — 오버레이 뜨자마자 모드 OFF 되는 것 방지.
    private fun isShortsVisibleInAnyWindow(): Boolean {
        val ws = try { windows } catch (_: Exception) { null }
        if (ws.isNullOrEmpty()) return getShortsNodeCount() > 0  // API 폴백
        for (w in ws) {
            val root = w.root ?: continue
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(SHORTS_NODE_ID)
                if ((nodes?.size ?: 0) > 0) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun getShortsFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        val descs = mutableListOf<String>()
        collectContentDescs(root, descs)
        root.recycle()
        return descs.take(5).joinToString("|")
    }

    private fun collectContentDescs(node: AccessibilityNodeInfo, descs: MutableList<String>) {
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty() && desc.length > 5) {
            descs.add(desc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectContentDescs(child, descs)
            if (descs.size >= 5) return
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val pkg = event.packageName?.toString()

        // YT 외 다른 앱으로 전환되면 popup 만 숨김 — pending 은 유지
        //
        // 단, 다음 패키지의 이벤트는 무시해야 함:
        //   - 우리 앱 자신 (popup overlay 를 띄울 때 TYPE_ACCESSIBILITY_OVERLAY 가 발생시키는 이벤트)
        //   - "android" (시스템 이벤트)
        //   - null (패키지 미식별 이벤트)
        // 이걸 무시하지 않으면 popup 띄우자마자 자기 자신이 다른 앱으로 인식되어 즉시 dismiss 됨
        val ownPackage = packageName
        val isTransientEvent = pkg == null || pkg == ownPackage || pkg == "android"

        if (!isTransientEvent && pkg != TARGET_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (popupViews.isNotEmpty()) {
                    Log.d(TAG, "다른 앱($pkg) 전환 → popup 숨김 (pending 유지)")
                    dismissAllPopups()
                }
                if (isInShortsMode) {
                    isInShortsMode = false
                    lastNodeCount = 0
                }
            }
            return
        }

        // 자기 자신/시스템 이벤트는 무시 — popup 이 띄워지면서 발생하는 이벤트를 우리 자신의 상태 변화로 잘못 해석하지 않기 위함
        if (pkg != TARGET_PACKAGE) return

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val nodeCount = getShortsNodeCount()
                if (nodeCount >= 1 && !isInShortsMode) {
                    isInShortsMode = true
                    shortsEnteredTime = now
                    lastCountTime = now
                    lastNodeCount = nodeCount
                    lastContentDesc = getShortsFingerprint()
                    Log.d(TAG, "쇼츠 진입 노드=$nodeCount")

                    // 1) 5분 차단 중이면 차단 popup + BACK 으로 쇼츠만 빠져나가게 함
                    // (HOME 으로 보내면 YT 가 강종 후 쇼츠로 바로 재진입할 때마다 폰 홈으로 튕겨서
                    //  YT 자체를 못 쓰게 됨 → BACK 으로 YT 안에서 다른 탭으로만 보냄)
                    if (now < stopUntilMs) {
                        val remaining = ((stopUntilMs - now) / 1000).toInt() + 1
                        Log.d(TAG, "차단 기간 중 쇼츠 진입 시도 — 남은 ${remaining}초")
                        showBlockPopup(remaining)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        return
                    }

                    // 2) 미응답 popup 이 있으면 재표시 (YT 강제종료 후 재진입 시나리오)
                    pendingPopupType?.let { type ->
                        Log.d(TAG, "미응답 popup 재표시 — type=$type, overage=$pendingPopupOverage")
                        showLimitPopup(type, pendingPopupOverage)
                    }
                } else if (nodeCount == 0 && isInShortsMode) {
                    // 활성 window 에 쇼츠 노드가 없어도, 다른 window (댓글 시트, 필터 드롭다운 등)
                    // 뒤에 쇼츠 컨테이너가 여전히 살아있으면 모드 유지. 진짜 이탈했을 때만 OFF.
                    if (isShortsVisibleInAnyWindow()) {
                        Log.d(TAG, "오버레이 감지 — 쇼츠모드 유지 (active=$nodeCount)")
                    } else {
                        isInShortsMode = false
                        lastNodeCount = 0
                        if (popupViews.isNotEmpty()) {
                            Log.d(TAG, "쇼츠 이탈 → popup 숨김 (pending 유지)")
                            dismissAllPopups()
                        }
                        Log.d(TAG, "쇼츠모드 OFF")
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isInShortsMode) return

                // 차단 기간 중이면 어떤 스크롤도 무시 + 쇼츠에서 BACK 으로 빠져나감
                if (now < stopUntilMs) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return
                }

                val nodeCount = getShortsNodeCount()

                if (lastNodeCount == 2 && nodeCount == 1) {
                    val currentDesc = getShortsFingerprint()

                    if (currentDesc != lastContentDesc && currentDesc.isNotEmpty()) {
                        lastContentDesc = currentDesc
                        Log.d(TAG, "영상 변경 확인")
                        countShorts(now)
                    } else {
                        Log.d(TAG, "스크롤 취소 (같은 영상)")
                    }
                }

                lastNodeCount = nodeCount
            }
        }
    }

    private fun countShorts(now: Long) {
        if (now - shortsEnteredTime < NOISE_MS) {
            Log.d(TAG, "진입 노이즈 무시")
            return
        }
        if (now - lastCountTime < DEBOUNCE_MS) return

        lastCountTime = now

        serviceScope.launch {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"

            // 자정 롤오버 처리 (limit/카운트/마일스톤 재로드)
            handleDayRolloverIfNeeded(now, userId)

            // 스크롤 이벤트를 Room DB 에 저장
            scrollHistoryDao.insert(ScrollHistory(appPkg = TARGET_PACKAGE, timestamp = now))

            dailyCount++

            // 배치 카운터 증가 — BATCH_SIZE(10개) 쌓이면 즉시 서버 전송
            batchScrollCount++
            if (batchScrollCount >= BATCH_SIZE) {
                flushBatch()
            }

            // 최근 1시간 스크롤 횟수 조회 (슬라이딩 윈도우)
            val oneHourAgo = now - (60 * 60 * 1000L)
            val hourlyCount = scrollHistoryDao.countLastHour(oneHourAgo)
            lastHourlyCount = hourlyCount

            Log.d(TAG, "스크롤 카운트 — hourly: $hourlyCount/$hourlyLimit, daily: $dailyCount/$dailyLimit")

            // hourly 가 limit 아래로 떨어졌으면 milestone 리셋
            // (슬라이딩 윈도우로 옛 카운트가 빠져나가 자연 회복된 경우 — 다음에 다시 limit 도달 시 "첫 팝업" 부터)
            if (hourlyCount < hourlyLimit && hourlyMilestone >= 0) {
                hourlyMilestone = -1
                savePersistedState()
            }

            // hourly 체크 — milestone 단위로 트리거
            if (hourlyCount >= hourlyLimit) {
                val nextTrigger = if (hourlyMilestone < 0) hourlyLimit else hourlyMilestone + HOURLY_STEP
                if (hourlyCount >= nextTrigger) {
                    hourlyMilestone = nextTrigger
                    val overage = nextTrigger - hourlyLimit
                    setPendingPopup("hourly", overage)
                    withContext(Dispatchers.Main) { showLimitPopup("hourly", overage) }
                    return@launch
                }
            }

            // daily 체크 — milestone 단위로 트리거
            if (dailyCount >= dailyLimit) {
                val nextTrigger = if (dailyMilestone < 0) dailyLimit else dailyMilestone + DAILY_STEP
                if (dailyCount >= nextTrigger) {
                    dailyMilestone = nextTrigger
                    val overage = nextTrigger - dailyLimit
                    setPendingPopup("daily", overage)
                    withContext(Dispatchers.Main) { showLimitPopup("daily", overage) }
                }
            }
        }
    }

    // ── Popup 빌더 ────────────────────────────────────────────

    // overage(=초과 분) 에 따라 popup 의 제목/본문 결정
    private fun buildPopupTexts(type: String, overage: Int): Pair<String, String> {
        return when (type) {
            "daily" -> {
                val title = "Daily Limit 초과"
                val body = if (overage == 0) {
                    "Daily limit을 초과했습니다.\n계속 보시겠습니까?"
                } else {
                    "오늘 목표보다 ${overage}회 더 보셨습니다.\n계속 보시겠습니까?"
                }
                title to body
            }
            "hourly" -> {
                val title = "Hourly Limit 초과"
                val body = if (overage == 0) {
                    "Hourly limit을 초과했습니다.\n계속 보시겠습니까?"
                } else {
                    "${overage}회 더 보셨습니다.\n계속 보시겠습니까?"
                }
                title to body
            }
            else -> "알림" to ""
        }
    }

    // 한도 초과 popup — type 과 모드(normal/hard) 에 따라 다른 popup 으로 분기
    // hard 모드 + hourly 만 5가지 variant 중 랜덤. daily 와 normal 모드는 표준 popup.
    private fun showLimitPopup(type: String, overage: Int) {
        if (type == "hourly" && isHardMode()) {
            showHourlyPopupHard(overage)
        } else {
            showLimitPopupNormal(type, overage)
        }
    }

    // 표준 popup (normal 모드 or daily)
    private fun showLimitPopupNormal(type: String, overage: Int) {
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = makeStandardPopupView(
                title = title,
                message = body,
                stopText = "그만보기",
                ignoreText = "계속보기",
                onStopClick = { executeStop(type) },
                onIgnoreClick = { executeIgnore(type) }
            )
            addPopupView(view, standardCenterParams(280))
        }
    }

    // 5분 차단 안내 popup — 버튼 없이 3초 후 자동 dismiss
    private fun showBlockPopup(remainingSec: Int) {
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
            view.findViewById<TextView>(R.id.popupTitle).text = "그만보기를 눌렀습니다"
            view.findViewById<TextView>(R.id.popupMessage).text = "5분간 진입금지!"
            view.findViewById<LinearLayout>(R.id.popupButtonRow).visibility = View.GONE
            addPopupView(view, standardCenterParams(280))
            mainHandler.postDelayed({ dismissAllPopups() }, 3000L)
        }
    }

    // ── 하드 모드 — 5가지 variant 중 랜덤 (hourly 전용) ──────────

    // 사용자가 설정 탭에서 "하드 모드" 선택했는지 확인
    private fun isHardMode(): Boolean {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("appMode", "normal") == "hard"
    }

    private fun showHourlyPopupHard(overage: Int) {
        // [임시] variant 6 (수학) 테스트용 — 다른 variant 주석 처리. 확인 끝나면 원복 필요.
        // val variant = (1..6).random()
        val variant = 6
        Log.d(TAG, "하드 모드 hourly popup variant=$variant")
        when (variant) {
            // 1 -> showHardCountdown(overage)
            // 2 -> showHardVerticalGrid(overage)
            // 3 -> showHardSwappedLabels(overage)
            // 4 -> showHardStacked(overage)
            // 5 -> showHardHorizontalScroll(overage)
            6 -> showHardMath(overage)
        }
    }

    // ── Variant 1 — 무시하기 버튼 60초 카운트다운 후 활성화 ──────
    private fun showHardCountdown(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body

            val btnStop = view.findViewById<Button>(R.id.btnStop)
            val btnIgnore = view.findViewById<Button>(R.id.btnIgnore)
            val countdownTv = view.findViewById<TextView>(R.id.ignoreCountdown)

            btnStop.text = "그만하기"
            btnStop.setOnClickListener { executeStop(type) }

            btnIgnore.text = "무시하기"
            btnIgnore.isEnabled = false
            btnIgnore.alpha = 0.5f
            btnIgnore.setOnClickListener { executeIgnore(type) }

            countdownTv.visibility = View.VISIBLE
            countdownTv.text = "60"

            addPopupView(view, standardCenterParams(280))

            // 1초마다 카운트다운 — popup 이 dismiss 되면 중단
            val tickRunnable = object : Runnable {
                var remaining = 60
                override fun run() {
                    if (!popupViews.contains(view)) return  // dismiss 됐으면 중단
                    remaining--
                    if (remaining > 0) {
                        countdownTv.text = "$remaining"
                        mainHandler.postDelayed(this, 1000)
                    } else {
                        countdownTv.visibility = View.GONE
                        btnIgnore.isEnabled = true
                        btnIgnore.alpha = 1f
                    }
                }
            }
            mainHandler.postDelayed(tickRunnable, 1000)
        }
    }

    // ── Variant 2 — 세로 스크롤 10행 × 2버튼, 9행은 그만하기×2, 1행만 그만/무시 쌍 ──
    private fun showHardVerticalGrid(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_vertical, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            val container = view.findViewById<LinearLayout>(R.id.rowsContainer)

            // 첫번째(0) 줄은 제외. 1..9 중 하나가 mixed row.
            val mixedRowIdx = (1..9).random()
            // mixed row 의 무시하기 위치 (왼/오 랜덤)
            val mixedIgnoreOnRight = (0..1).random() == 1
            val uniformColor = android.graphics.Color.parseColor("#555555")
            val density = resources.displayMetrics.density

            for (i in 0 until 10) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt()) }
                }

                val (leftLabel, rightLabel, leftAction, rightAction) = when {
                    i != mixedRowIdx -> {
                        // 양쪽 모두 그만하기
                        Quadruple("그만하기", "그만하기", { executeStop(type) }, { executeStop(type) })
                    }
                    mixedIgnoreOnRight -> {
                        Quadruple("그만하기", "무시하기", { executeStop(type) }, { executeIgnore(type) })
                    }
                    else -> {
                        Quadruple("무시하기", "그만하기", { executeIgnore(type) }, { executeStop(type) })
                    }
                }

                val leftBtn = Button(this).apply {
                    text = leftLabel
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(uniformColor)
                    setOnClickListener { leftAction() }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(0, 0, (6 * density).toInt(), 0) }
                }
                val rightBtn = Button(this).apply {
                    text = rightLabel
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(uniformColor)
                    setOnClickListener { rightAction() }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(leftBtn)
                row.addView(rightBtn)
                container.addView(row)
            }

            addPopupView(view, fixedSizeCenterParams(320, 480))
        }
    }

    // ── Variant 3 — 색상 위치 고정, 글자만 swap (red=무시하기, gray=그만하기) ──
    private fun showHardSwappedLabels(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            // btnStop (red, 왼쪽) 라벨 = "무시하기" → 클릭 시 ignore
            // btnIgnore (gray, 오른쪽) 라벨 = "그만하기" → 클릭 시 stop
            val view = makeStandardPopupView(
                title = title,
                message = body,
                stopText = "무시하기",
                ignoreText = "그만하기",
                onStopClick = { executeIgnore(type) },  // 라벨 따라 동작
                onIgnoreClick = { executeStop(type) }
            )
            addPopupView(view, standardCenterParams(280))
        }
    }

    // ── Variant 4 — 10개 popup 대각선 스택. 1개(랜덤, 첫번째 제외) 만 라벨 swap ──
    private fun showHardStacked(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()

            val popupCount = 10
            val trapIdx = (1..9).random()  // 0 (맨 아래) 제외
            val density = resources.displayMetrics.density
            val offsetPx = (16 * density).toInt()

            // 각 popup 생성. 맨 아래(idx=0) 부터 맨 위(idx=9) 까지 순서대로 addView.
            // 따라서 idx=9 가 topmost. 사용자가 클릭하는 건 idx=9 부터 시작.
            for (idx in 0 until popupCount) {
                val isTrap = (idx == trapIdx)
                val view = if (isTrap) {
                    makeStandardPopupView(
                        title = title,
                        message = body,
                        stopText = "무시하기",  // red 위치에 무시하기 라벨
                        ignoreText = "그만하기",  // gray 위치에 그만하기 라벨
                        onStopClick = { handleStackedIgnore(type) },  // 라벨 따라 동작
                        onIgnoreClick = { executeStop(type) }
                    )
                } else {
                    makeStandardPopupView(
                        title = title,
                        message = body,
                        stopText = "그만하기",
                        ignoreText = "무시하기",
                        onStopClick = { executeStop(type) },
                        onIgnoreClick = { handleStackedIgnore(type) }
                    )
                }

                val isTop = (idx == popupCount - 1)
                val params = standardCenterParams(260).apply {
                    x = idx * offsetPx
                    y = idx * offsetPx
                    if (!isTop) {
                        // 아래 popup 들은 터치 비활성 — 맨 위만 클릭 가능
                        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    }
                }
                addPopupView(view, params)
            }
        }
    }

    // Stacked 모드에서 무시하기 클릭 시: 현재 top popup 하나만 dismiss, 모두 사라지면 ignore 처리
    private fun handleStackedIgnore(type: String) {
        if (popupViews.isEmpty()) return
        val top = popupViews.last()
        popupViews.remove(top)
        try { windowManager?.removeView(top) } catch (_: Exception) {}

        if (popupViews.isEmpty()) {
            // 마지막 popup 도 사라짐 → 진짜 ignore 처리
            isPopupShowing = false
            sendViolation(type, lastHourlyCount, "ignore")
            clearPendingPopup()
            Log.d(TAG, "스택 popup 전부 처리 → ignore 완료")
        } else {
            // 다음 top popup 의 터치를 활성화
            val newTop = popupViews.last()
            try {
                val params = newTop.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager?.updateViewLayout(newTop, params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "스택 popup 터치 활성화 실패 — ${e.message}")
            }
        }
    }

    // ── Variant 5 — 가로 스크롤 20개 버튼. 1개(랜덤, 첫/두번째 제외) 만 무시하기 ──
    private fun showHardHorizontalScroll(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_horizontal, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            val container = view.findViewById<LinearLayout>(R.id.buttonsContainer)

            val total = 20
            val ignoreIdx = (2 until total).random()  // index 2..19 — 첫/두번째 제외
            val uniformColor = android.graphics.Color.parseColor("#555555")
            val density = resources.displayMetrics.density

            for (i in 0 until total) {
                val isIgnoreBtn = (i == ignoreIdx)
                val btn = Button(this).apply {
                    text = if (isIgnoreBtn) "무시하기" else "그만하기"
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(uniformColor)
                    setOnClickListener {
                        if (isIgnoreBtn) executeIgnore(type) else executeStop(type)
                    }
                    minWidth = (96 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0) }
                }
                container.addView(btn)
            }

            addPopupView(view, standardCenterParams(320))
        }
    }

    // ── Variant 6 — 수학 문제. 정답 선택 시 무시하기 활성화, 오답 선택 시 그만하기 활성화 ──
    // 5지선다 중 1개 정답. 한 번 선택하면 다른 선택지는 잠금 — 재시도 불가.
    private fun showHardMath(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)

        val (questionText, correctAnswer) = generateMathProblem()
        val wrongAnswers = generateWrongAnswers(correctAnswer, 4)
        val allChoices = (wrongAnswers + correctAnswer).shuffled()

        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_math, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            view.findViewById<TextView>(R.id.mathQuestion).text = questionText
            val container = view.findViewById<LinearLayout>(R.id.choicesContainer)

            val btnStop = view.findViewById<Button>(R.id.btnStop)
            val btnIgnore = view.findViewById<Button>(R.id.btnIgnore)

            btnStop.text = "그만하기"
            btnStop.isEnabled = false
            btnStop.alpha = 0.5f
            btnStop.setOnClickListener { executeStop(type) }

            btnIgnore.text = "무시하기"
            btnIgnore.isEnabled = false
            btnIgnore.alpha = 0.5f
            btnIgnore.setOnClickListener { executeIgnore(type) }

            val density = resources.displayMetrics.density
            val uniformColor = android.graphics.Color.parseColor("#555555")
            val choiceButtons = mutableListOf<Button>()
            var answered = false

            for (choice in allChoices) {
                val btn = Button(this).apply {
                    text = choice.toString()
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(uniformColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0) }
                    setOnClickListener {
                        if (answered) return@setOnClickListener
                        answered = true
                        // 다른 선택지 잠금
                        choiceButtons.forEach { it.isEnabled = false }
                        if (choice == correctAnswer) {
                            btnIgnore.isEnabled = true
                            btnIgnore.alpha = 1f
                        } else {
                            btnStop.isEnabled = true
                            btnStop.alpha = 1f
                        }
                    }
                }
                choiceButtons.add(btn)
                container.addView(btn)
            }

            addPopupView(view, standardCenterParams(360))
        }
    }

    // 사칙연산 문제 생성 — 3개 피연산자 + 2개 연산자, 피연산자는 최대 2자리(1..99)
    // 예: "11 + 29 × 6 = ?" (× 우선 적용해서 11 + 174 = 185)
    // 연산자는 + - × 중 랜덤. ÷ 는 다항식에서 정수 보장이 까다로워 제외.
    private fun generateMathProblem(): Pair<String, Long> {
        val a = (1..99).random()
        val b = (1..99).random()
        val c = (1..99).random()
        val ops = listOf("+", "-", "×")
        val op1 = ops.random()
        val op2 = ops.random()

        fun apply(x: Long, op: String, y: Long): Long = when (op) {
            "+" -> x + y
            "-" -> x - y
            "×" -> x * y
            else -> error("unknown op: $op")
        }

        // × 우선순위 처리
        val result: Long = when {
            op1 == "×" && op2 != "×" -> apply(a.toLong() * b, op2, c.toLong())   // (a×b) op2 c
            op2 == "×" && op1 != "×" -> apply(a.toLong(), op1, b.toLong() * c)   // a op1 (b×c)
            else -> apply(apply(a.toLong(), op1, b.toLong()), op2, c.toLong())    // 좌→우
        }

        return "$a $op1 $b $op2 $c = ?" to result
    }

    // 정답의 1의 자리는 그대로 두고, 나머지 자릿수는 모두 다른 숫자로 바꾼 오답을 count 개 생성
    // (1자리 정답이면 앞에 1~2 자리를 새로 붙여 만듦)
    private fun generateWrongAnswers(correct: Long, count: Int): List<Long> {
        val absValue = if (correct < 0) -correct else correct
        val sign = if (correct < 0) -1L else 1L
        val digits = absValue.toString().toCharArray()
        val onesIdx = digits.size - 1
        val candidates = linkedSetOf<Long>()

        var attempt = 0
        while (candidates.size < count && attempt < 500) {
            attempt++
            val candidate: Long = if (digits.size == 1) {
                // 1자리 정답 — 앞에 자릿수 1~2개 prefix
                val prefixLen = (1..2).random()
                val sb = StringBuilder()
                for (k in 0 until prefixLen) {
                    sb.append(if (k == 0) ('1'..'9').random() else ('0'..'9').random())
                }
                sb.append(digits[0])
                sb.toString().toLong() * sign
            } else {
                val newDigits = digits.copyOf()
                for (i in 0 until onesIdx) {
                    val orig = newDigits[i]
                    var pick: Char
                    val pool = if (i == 0) ('1'..'9') else ('0'..'9')  // 맨 앞은 0 불가
                    do { pick = pool.random() } while (pick == orig)
                    newDigits[i] = pick
                }
                String(newDigits).toLong() * sign
            }
            if (candidate != correct) candidates.add(candidate)
        }
        return candidates.toList()
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────

    // overlay_popup.xml 을 inflate 해서 라벨/색/콜백 채워 반환
    private fun makeStandardPopupView(
        title: String,
        message: String,
        stopText: String,
        ignoreText: String,
        onStopClick: () -> Unit,
        onIgnoreClick: () -> Unit
    ): View {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
        view.findViewById<TextView>(R.id.popupTitle).text = title
        view.findViewById<TextView>(R.id.popupMessage).text = message
        view.findViewById<Button>(R.id.btnStop).apply {
            text = stopText
            setOnClickListener { onStopClick() }
        }
        view.findViewById<Button>(R.id.btnIgnore).apply {
            text = ignoreText
            setOnClickListener { onIgnoreClick() }
        }
        return view
    }

    // 화면 중앙, 너비 widthDp dp, 높이 wrap_content 인 popup params
    private fun standardCenterParams(widthDp: Int): WindowManager.LayoutParams {
        val widthPx = (widthDp * resources.displayMetrics.density).toInt()
        return WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    // 화면 중앙, 너비 widthDp / 높이 heightDp 고정 popup params — variant 2 처럼 ScrollView 가 있을 때 사용
    private fun fixedSizeCenterParams(widthDp: Int, heightDp: Int): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    // popup view 를 list 에 등록 + WindowManager 에 추가
    private fun addPopupView(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager?.addView(view, params)
            popupViews.add(view)
            isPopupShowing = true
            Log.d(TAG, "popup add — 현재 ${popupViews.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "popup add 실패 — ${e.message}")
        }
    }

    // ── 사용자 응답 처리 액션 ─────────────────────────────────

    // 그만하기 — 5분 차단 시작, popup 전부 dismiss, 홈으로 강제 이동
    private fun executeStop(type: String) {
        val now = System.currentTimeMillis()
        stopUntilMs = now + STOP_BLOCK_MS
        savePersistedState()
        sendViolation(type, lastHourlyCount, "stop")
        clearPendingPopup()
        dismissAllPopups()
        Log.d(TAG, "그만하기 선택 → 5분 차단 시작")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // 무시하기 (variant 4 제외) — popup 전부 dismiss + violation 전송
    private fun executeIgnore(type: String) {
        sendViolation(type, lastHourlyCount, "ignore")
        clearPendingPopup()
        dismissAllPopups()
        Log.d(TAG, "무시하기 선택 → 다음 milestone 까지 대기")
    }

    // popupViews 의 4-튜플 비구조화 분해를 위한 헬퍼
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ── 상태 저장 헬퍼 ────────────────────────────────────────

    private fun savePersistedState() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .putInt(PK_DAILY_MILESTONE, dailyMilestone)
            .putInt(PK_HOURLY_MILESTONE, hourlyMilestone)
            .putLong(PK_STOP_UNTIL, stopUntilMs)
            .putLong(PK_TODAY_START, todayStartMs)
            .apply()
    }

    // 미응답 popup 기록 — YT 강제종료 후에도 재진입 시 같은 popup 복원
    private fun setPendingPopup(type: String, overage: Int) {
        pendingPopupType = type
        pendingPopupOverage = overage
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PK_PENDING_TYPE, type)
            .putInt(PK_PENDING_OVERAGE, overage)
            .apply()
    }

    // 사용자 응답(Stop/Ignore) 시 pending 해제
    private fun clearPendingPopup() {
        pendingPopupType = null
        pendingPopupOverage = 0
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(PK_PENDING_TYPE)
            .remove(PK_PENDING_OVERAGE)
            .apply()
    }

    // ── 서버 통신 ─────────────────────────────────────────────

    // Firebase ID 토큰 가져오기
    private suspend fun getFirebaseToken(): String? {
        return try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Log.e(TAG, "Firebase 토큰 가져오기 실패 — ${e.message}")
            null
        }
    }

    // violation 발생 시 서버로 POST /violations 전송
    private fun sendViolation(limitType: String, scrollCount: Int, action: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val userId = prefs.getString("userId", "unknown") ?: "unknown"

        val json = """
        {
            "userId": "$userId",
            "timestamp": ${System.currentTimeMillis()},
            "limitType": "$limitType",
            "scrollCount": $scrollCount,
            "action": "$action"
        }
    """.trimIndent()

        serviceScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    Log.e(TAG, "violation 전송 실패 — 토큰 없음")
                    return@launch
                }

                val client = okhttp3.OkHttpClient()
                val mediaType = "application/json".toMediaType()
                val body = json.toRequestBody(mediaType)
                val request = okhttp3.Request.Builder()
                    .url("https://short-cut-server-production.up.railway.app/violations")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "violation 전송 완료 — ${response.code}")
            } catch (e: Exception) {
                Log.e(TAG, "violation 전송 실패 — ${e.message}")
            }
        }
    }

    // 서버로 POST /userlogs 전송 — 배치 기간 동안 누적된 스크롤 횟수 저장
    private fun sendUserLog(scrollCount: Int) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val userId = prefs.getString("userId", "unknown") ?: "unknown"

        val json = """
        {
            "userId": "$userId",
            "timestamp": ${System.currentTimeMillis()},
            "scrollCount": $scrollCount
        }
    """.trimIndent()

        serviceScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    Log.e(TAG, "userLog 전송 실패 — 토큰 없음")
                    return@launch
                }

                val client = okhttp3.OkHttpClient()
                val mediaType = "application/json".toMediaType()
                val body = json.toRequestBody(mediaType)
                val request = okhttp3.Request.Builder()
                    .url("https://short-cut-server-production.up.railway.app/userlogs")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "userLog 배치 전송 완료 — scrollCount: $scrollCount, ${response.code}")
            } catch (e: Exception) {
                Log.e(TAG, "userLog 배치 전송 실패 — ${e.message}")
            }
        }
    }

    private fun scheduleBatchTimer() {
        batchHandler.postDelayed(batchTimerRunnable, 5 * 60 * 1000L)
    }

    private fun flushBatch() {
        val count = batchScrollCount
        if (count <= 0) return
        batchScrollCount = 0
        sendUserLog(count)
    }

    // 모든 popup view 제거 — variant 4 stacked 포함 전체 dismiss
    private fun dismissAllPopups() {
        val copy = popupViews.toList()
        popupViews.clear()
        isPopupShowing = false
        copy.forEach { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        dismissAllPopups()
        Log.d(TAG, "서비스 중단됨")
    }

    override fun onDestroy() {
        dismissAllPopups()
        batchHandler.removeCallbacks(batchTimerRunnable)
        flushBatch()
        super.onDestroy()
    }
}
