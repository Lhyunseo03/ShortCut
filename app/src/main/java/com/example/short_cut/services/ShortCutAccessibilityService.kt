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
        const val PK_PENDING_USERLOGS = "pendingUserLogs"      // 서버 전송 대기/실패한 userlog 큐 (JSON 배열 [{ts,count}])
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

    // ── 앱별 쇼츠 검출기 ──────────────────────────────────────
    // 각 detector 가 자기 앱의 진입/이탈/스크롤 상태를 따로 관리. 메인 서비스는 outcome 만 받아 처리.
    private val detectors = listOf(
        com.example.short_cut.services.detectors.YoutubeDetector(),
        com.example.short_cut.services.detectors.InstagramDetector(),
        com.example.short_cut.services.detectors.TiktokDetector(
            com.example.short_cut.services.detectors.TiktokDetector.PACKAGE_GLOBAL
        ),
        com.example.short_cut.services.detectors.TiktokDetector(
            com.example.short_cut.services.detectors.TiktokDetector.PACKAGE_TRILL
        )
    )

    // ── popup 상태 ────────────────────────────────────────────
    private var isPopupShowing = false
    private var windowManager: WindowManager? = null
    // popup view 들 — variant 4(stacked) 를 위해 list 로 관리. 단일 popup variant 에서도 list 에 1개로 저장.
    private val popupViews = mutableListOf<View>()
    // 팝업 뒤 전체화면 차단막 — 카드 밖 영역 터치를 흡수해 뒤(유튜브)가 스크롤/터치되지 않게 함.
    private var scrimView: View? = null
    // 5분 차단 안내 popup 표시 중 여부 — 쇼츠 이탈(BACK) 로 조기 dismiss 되지 않고 6초 타이머로만 닫히게 구분
    private var blockPopupShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 배치 전송 ─────────────────────────────────────────────
    // 현재 쌓이는 중인 배치. batchFirstScrollMs = 이 배치의 첫 스크롤 시각(전송 timestamp 로 사용).
    // batchAppPkg = 이 배치가 어느 앱에서 발생한 스크롤인지 — 다른 앱 스크롤이 끼면 먼저 flush 후 새 배치 시작
    // (서버에 플랫폼별로 분리 집계되도록).
    private var batchScrollCount = 0
    private var batchFirstScrollMs = 0L
    private var batchAppPkg: String = ""
    // 마지막으로 감지된 스크롤의 앱 패키지 — violation 전송 시 어느 플랫폼에서 발생했는지 식별용
    @Volatile private var lastScrollPkg: String = ""
    private val BATCH_SIZE = 10
    private val batchHandler = Handler(Looper.getMainLooper())
    private val batchTimerRunnable = Runnable {
        flushBatch()
        scheduleBatchTimer()
    }
    // batchScrollCount/batchFirstScrollMs 와 pending 큐(prefs) 접근을 보호하는 락 (네트워크 I/O 는 락 밖에서 수행)
    private val pendingLock = Any()
    // 동시에 두 전송이 큐 앞쪽을 중복 제거하지 않도록 — 전송은 한 번에 하나만
    private val isSending = java.util.concurrent.atomic.AtomicBoolean(false)

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

        // 지난 실행에서 전송 못 하고 남은 userlog 가 있으면 재시도 (앱 강제종료 등으로 유실 방지)
        sendPendingUserLogs()
    }

    // 두 시각이 같은 '시(hour)'(로컬 타임존, 같은 날 같은 시)인지 — 배치가 시 경계를 넘는지 판단용
    private fun sameHour(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR) &&
                ca.get(java.util.Calendar.HOUR_OF_DAY) == cb.get(java.util.Calendar.HOUR_OF_DAY)
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
        // 날짜가 바뀌기 전에 이전 날 배치를 먼저 flush — batchFirstScrollMs(이전 날 시각)로 전송돼
        // 어제 스크롤이 어제 날짜로 집계됨
        flushBatch()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val pkg = event.packageName?.toString()

        // 자기 자신/시스템 이벤트는 무시 — popup 이 띄워지면서 발생하는 이벤트(TYPE_ACCESSIBILITY_OVERLAY 등),
        // "android" 시스템 이벤트, SystemUI (잠금화면/알림창) 는 우리 상태 변화로 잘못 해석하지 않기 위해 제외.
        val ownPackage = packageName
        val isTransientEvent = pkg == null || pkg == ownPackage || pkg == "android" ||
                pkg == "com.android.systemui"

        // 매칭되는 detector 찾기
        val detector = if (pkg != null) detectors.firstOrNull { it.packageName == pkg } else null

        if (detector == null) {
            // self/system 이벤트는 조용히 무시
            if (isTransientEvent) return
            // 지원 안 하는 다른 앱으로 전환된 경우 — popup 만 숨기고 모든 detector 의 모드 리셋
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (popupViews.isNotEmpty()) {
                    Log.d(TAG, "다른 앱($pkg) 전환 → popup 숨김 (pending 유지)")
                    dismissAllPopups()
                }
                detectors.forEach { if (it.inShortsMode) it.onPackageLeft() }
            }
            return
        }

        // detector 에 위임
        val outcome = detector.onEvent(this, event)

        // 5분 차단 기간 중 — 쇼츠 모드라면 계속 BACK 으로 밀어냄.
        // (HOME 보내면 앱 강종 후 쇼츠로 재진입할 때마다 폰 홈으로 튕겨서 앱 자체를 못 쓰게 됨 → BACK 으로 앱 안 다른 화면으로 보냄)
        if (detector.inShortsMode && now < stopUntilMs) {
            if (outcome.entered) {
                val remaining = ((stopUntilMs - now) / 1000).toInt() + 1
                Log.d(TAG, "차단 기간 중 쇼츠 진입 시도 (${detector.packageName}) — 남은 ${remaining}초")
                showBlockPopup(remaining)
            }
            // WINDOW_STATE/CONTENT/SCROLLED 변화 때만 BACK — 다른 작은 이벤트로 매번 호출되는 것 방지
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            return
        }

        if (outcome.entered) {
            // 미응답 popup 이 있으면 재표시 (앱 강제종료 후 재진입 시나리오)
            pendingPopupType?.let { type ->
                Log.d(TAG, "미응답 popup 재표시 — type=$type, overage=$pendingPopupOverage")
                showLimitPopup(type, pendingPopupOverage)
            }
        }

        if (outcome.exited) {
            // 쇼츠 이탈 — popup 숨김 (pending 유지). 차단 안내 popup 은 자체 6초 타이머에 맡김.
            if (popupViews.isNotEmpty() && !blockPopupShowing) {
                Log.d(TAG, "쇼츠 이탈 → popup 숨김 (pending 유지)")
                dismissAllPopups()
            }
        }

        if (outcome.scrolled) {
            countShorts(now, detector.packageName)
        }
    }

    // 스크롤 한 건 처리 — debounce/noise 검사는 각 detector 가 이미 수행함.
    // appPkg: 어느 앱(YT/IG/TT)에서 발생한 스크롤인지 — DB 저장용.
    private fun countShorts(now: Long, appPkg: String) {
        lastScrollPkg = appPkg
        serviceScope.launch {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"

            // 자정 롤오버 처리 (limit/카운트/마일스톤 재로드)
            handleDayRolloverIfNeeded(now, userId)

            // 스크롤 이벤트를 Room DB 에 저장
            scrollHistoryDao.insert(ScrollHistory(appPkg = appPkg, timestamp = now))

            dailyCount++

            // 배치 카운터 증가 — BATCH_SIZE(10개) 쌓이면 즉시 서버 전송.
            // 배치의 첫 스크롤 시각(now)을 timestamp 로 보냄 → 서버가 실제 스크롤 시각 기준으로 집계.
            // 이번 스크롤이 배치 첫 스크롤과 다른 시(hour)/다른 앱이면 먼저 flush → 한 배치가 두 시간대/두 플랫폼에 안 걸치게.
            val needFlush = synchronized(pendingLock) {
                batchScrollCount > 0 && (!sameHour(batchFirstScrollMs, now) || batchAppPkg != appPkg)
            }
            if (needFlush) flushBatch()

            val reachedBatch: Boolean
            synchronized(pendingLock) {
                if (batchScrollCount == 0) {
                    batchFirstScrollMs = now
                    batchAppPkg = appPkg
                }
                batchScrollCount++
                reachedBatch = batchScrollCount >= BATCH_SIZE
            }
            if (reachedBatch) flushBatch()

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

            // hourly 체크 — milestone 단위로 트리거.
            // milestone=-1 (서비스 재시작/슬라이딩 윈도우로 reset 직후) 인데 카운트가 이미 limit+N*STEP 이상이면,
            // 가장 가까운 STEP 배수로 첫 트리거를 맞춤. (아니면 limit→limit+STEP→... 까지 매 스크롤마다 popup 폭주)
            if (hourlyCount >= hourlyLimit) {
                val nextTrigger = if (hourlyMilestone < 0) {
                    hourlyLimit + ((hourlyCount - hourlyLimit) / HOURLY_STEP) * HOURLY_STEP
                } else {
                    hourlyMilestone + HOURLY_STEP
                }
                if (hourlyCount >= nextTrigger) {
                    hourlyMilestone = nextTrigger
                    val overage = nextTrigger - hourlyLimit
                    setPendingPopup("hourly", overage)
                    withContext(Dispatchers.Main) { showLimitPopup("hourly", overage) }
                    return@launch
                }
            }

            // daily 체크 — milestone 단위로 트리거. (hourly 와 동일한 따라잡기 처리)
            if (dailyCount >= dailyLimit) {
                val nextTrigger = if (dailyMilestone < 0) {
                    dailyLimit + ((dailyCount - dailyLimit) / DAILY_STEP) * DAILY_STEP
                } else {
                    dailyMilestone + DAILY_STEP
                }
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
    // hard 모드 + hourly 만 3단계 하드 popup. daily 와 normal 모드는 표준 popup.
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

    // 5분 차단 안내 popup — 버튼 없이 6초 후 자동 dismiss (튕겨내기[BACK]는 호출부에서 그대로 수행)
    private fun showBlockPopup(remainingSec: Int) {
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
            view.findViewById<TextView>(R.id.popupTitle).text = "그만보기를 눌렀습니다"
            view.findViewById<TextView>(R.id.popupMessage).text = "5분간 진입금지!"
            view.findViewById<LinearLayout>(R.id.popupButtonRow).visibility = View.GONE
            blockPopupShowing = true
            addPopupView(view, standardCenterParams(280))
            mainHandler.postDelayed({ dismissAllPopups() }, 6000L)
        }
    }

    // ── 하드 모드 — hourly 전용, 3단계 ──────────
    //   1단계 (처음 초과, overage == 0): 일반 팝업 (normal 모드와 동일)
    //   2단계 (초과 ~ 2배 이하, 0 < overage <= hourlyLimit): 수학(variant 6) 제외한 variant 1~5 중 랜덤
    //   3단계 (2배 초과, overage > hourlyLimit): 수학 문제(variant 6) 만

    // 사용자가 설정 탭에서 "하드 모드" 선택했는지 확인
    private fun isHardMode(): Boolean {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("appMode", "normal") == "hard"
    }

    private fun showHourlyPopupHard(overage: Int) {
        // overage = (현재 시간당 카운트 - hourlyLimit). 첫 초과 팝업은 overage == 0.
        // "2배 초과" = overage > hourlyLimit (카운트 > 2 × hourlyLimit ⟺ hourlyLimit + overage > 2 × hourlyLimit)
        when {
            overage == 0 -> {
                // 1단계 — 처음 초과: 일반 팝업
                Log.d(TAG, "하드 모드 hourly popup — 첫 초과 → 일반 팝업")
                showLimitPopupNormal("hourly", overage)
            }
            overage > hourlyLimit -> {
                // 3단계 — limit 2배 초과: 수학 문제만
                Log.d(TAG, "하드 모드 hourly popup — 2배 초과 → 수학(variant 6)")
                showHardMath(overage)
            }
            else -> {
                // 2단계 — 초과 ~ 2배 이하 추가 팝업: 수학 제외 1~5 랜덤
                val variant = (1..5).random()
                Log.d(TAG, "하드 모드 hourly popup — 추가 팝업 → variant=$variant")
                when (variant) {
                    1 -> showHardCountdown(overage)
                    2 -> showHardVerticalGrid(overage)
                    3 -> showHardSwappedLabels(overage)
                    4 -> showHardStacked(overage)
                    5 -> showHardHorizontalScroll(overage)
                }
            }
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

    // ── Variant 2 — 세로 스크롤. 처음엔 일반 팝업과 똑같은 크기/버튼 위치로 보이되 둘 다 "그만하기"(=stop).
    //   ScrollView 높이를 딱 한 줄로 고정해 첫 줄만 노출 → 아래로 스크롤하면 나오는 줄들 중 1곳에만 진짜 무시하기. ──
    private fun showHardVerticalGrid(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_vertical, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            val container = view.findViewById<LinearLayout>(R.id.rowsContainer)

            val density = resources.displayMetrics.density
            val rowCount = 10
            // 진짜 무시하기 — 첫 줄(0)은 제외, 1..9 줄 중 한 곳의 좌/우 랜덤
            val realRow = (1 until rowCount).random()
            val realRight = (0..1).random() == 1

            val red = android.graphics.Color.parseColor("#FF4444")   // 일반 팝업 그만보기 색(왼쪽)
            val gray = android.graphics.Color.parseColor("#888888")  // 일반 팝업 무시하기 색(오른쪽)
            val gapPx = (20 * density).toInt()                       // 일반 팝업과 동일한 버튼 간격
            val btnHeightPx = (48 * density).toInt()
            val rowMarginPx = (4 * density).toInt()

            fun cell(label: String, bg: Int, marginEnd: Int, action: () -> Unit): Button =
                Button(this).apply {
                    text = label
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(bg)
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(0, btnHeightPx, 1f)
                        .apply { setMargins(0, 0, marginEnd, 0) }
                }

            for (r in 0 until rowCount) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, rowMarginPx, 0, rowMarginPx) }
                }
                // 모든 줄을 빨강|회색 쌍으로 — 첫 줄은 일반 팝업과 동일하게 보이고, 둘 다 그만하기.
                val leftReal = (r == realRow && !realRight)
                val rightReal = (r == realRow && realRight)
                row.addView(cell(if (leftReal) "무시하기" else "그만하기", red, gapPx) {
                    if (leftReal) executeIgnore(type) else executeStop(type)
                })
                row.addView(cell(if (rightReal) "무시하기" else "그만하기", gray, 0) {
                    if (rightReal) executeIgnore(type) else executeStop(type)
                })
                container.addView(row)
            }

            // ScrollView 높이를 딱 한 줄로 고정 → 첫 줄(버튼 2개)만 보이고 나머지는 아래로 스크롤해야 노출
            val scrollView = container.parent as android.widget.ScrollView
            scrollView.layoutParams = scrollView.layoutParams.apply {
                height = btnHeightPx + rowMarginPx * 2
            }

            addPopupView(view, standardCenterParams(280))
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
            removeScrim()
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

    // ── Variant 5 — 가로 스크롤. 처음엔 일반 팝업과 똑같은 크기/버튼 위치로 보이되 둘 다 "그만하기"(=stop).
    //   버튼 폭을 딱 2개만 보이게 고정 → 오른쪽으로 스크롤하면 나오는 버튼들 중 단 1개만 진짜 무시하기. ──
    private fun showHardHorizontalScroll(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_horizontal, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            val container = view.findViewById<LinearLayout>(R.id.buttonsContainer)

            val density = resources.displayMetrics.density
            val total = 20
            // 진짜 무시하기 — 처음 보이는 2칸(0,1) 밖에 한 개만 숨김
            val ignoreIdx = (2 until total).random()

            // 일반 팝업과 동일: 폭 280dp, padding 24, 버튼 간격 20dp → 버튼 폭 = (280-48-20)/2
            val popupWidthDp = 280
            val gapDp = 20
            val btnWidthPx = (((popupWidthDp - 48 - gapDp) / 2) * density).toInt()
            val gapPx = (gapDp * density).toInt()

            val red = android.graphics.Color.parseColor("#FF4444")   // 일반 팝업 그만보기 색(왼쪽)
            val gray = android.graphics.Color.parseColor("#888888")  // 일반 팝업 무시하기 색(오른쪽)

            for (i in 0 until total) {
                val isReal = (i == ignoreIdx)
                val btn = Button(this).apply {
                    text = if (isReal) "무시하기" else "그만하기"
                    setTextColor(android.graphics.Color.WHITE)
                    // 빨강|회색 번갈아 → 첫 화면(0,1)이 빨강+회색 = 일반 팝업 모양
                    setBackgroundColor(if (i % 2 == 0) red else gray)
                    setOnClickListener { if (isReal) executeIgnore(type) else executeStop(type) }
                    layoutParams = LinearLayout.LayoutParams(btnWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, 0, gapPx, 0) }
                }
                container.addView(btn)
            }

            addPopupView(view, standardCenterParams(popupWidthDp))
        }
    }

    // ── Variant 6 — 수학 문제. 정답이면 그만하기/무시하기 둘 다 활성화(선택권), 오답이면 그만하기만 활성화 ──
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
                            // 정답 — 그만하기/무시하기 둘 다 활성화해 사용자가 직접 선택
                            btnStop.isEnabled = true
                            btnStop.alpha = 1f
                            btnIgnore.isEnabled = true
                            btnIgnore.alpha = 1f
                        } else {
                            // 오답 — 그만하기만 활성화
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
    // 난이도 규약: 연산자 두 개 중 하나는 무조건 곱셈(×), 나머지 하나는 덧셈/뺄셈.
    //   곱셈이 앞(op1)에 올지 뒤(op2)에 올지는 랜덤.
    // 예: "11 + 29 × 6 = ?" (× 우선 적용해서 11 + 174 = 185)
    // ÷ 는 다항식에서 정수 보장이 까다로워 제외.
    private fun generateMathProblem(): Pair<String, Long> {
        val a = (1..99).random()
        val b = (1..99).random()
        val c = (1..99).random()

        val addSub = listOf("+", "-").random()   // 나머지 한 자리는 덧셈/뺄셈 중 하나
        val mulFirst = (0..1).random() == 0       // 곱셈을 앞/뒤 어디에 둘지 랜덤
        val op1 = if (mulFirst) "×" else addSub
        val op2 = if (mulFirst) addSub else "×"

        fun apply(x: Long, op: String, y: Long): Long = when (op) {
            "+" -> x + y
            "-" -> x - y
            "×" -> x * y
            else -> error("unknown op: $op")
        }

        // 곱셈이 항상 정확히 하나이므로 × 위치를 먼저 계산해 우선순위 반영
        val result: Long = if (mulFirst) {
            apply(a.toLong() * b, op2, c.toLong())   // (a×b) op2 c
        } else {
            apply(a.toLong(), op1, b.toLong() * c)   // a op1 (b×c)
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

    // popup view 를 list 에 등록 + WindowManager 에 추가
    private fun addPopupView(view: View, params: WindowManager.LayoutParams) {
        try {
            // 팝업보다 먼저 차단막을 깔아 두면(z-order 상 아래) 카드 밖 터치가 유튜브로 통과되지 않음
            ensureScrim()
            windowManager?.addView(view, params)
            popupViews.add(view)
            isPopupShowing = true
            Log.d(TAG, "popup add — 현재 ${popupViews.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "popup add 실패 — ${e.message}")
        }
    }

    // 전체화면 차단막을 (없으면) 추가. 모든 팝업 뒤에 깔려 카드 밖 터치를 전부 흡수 → 뒤 화면 조작 불가.
    private fun ensureScrim() {
        if (scrimView != null) return
        val scrim = View(this).apply {
            setBackgroundColor(0x40000000)  // 살짝 어둡게 — 모달(뒤 비활성) 상태 표시
            setOnTouchListener { _, _ -> true }  // 카드 밖 모든 터치 흡수(소비)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE 만 — 키(뒤로가기) 포커스는 안 가져가되 터치는 받도록(NOT_TOUCHABLE 미설정)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(scrim, params)
            scrimView = scrim
        } catch (e: Exception) {
            Log.e(TAG, "scrim add 실패 — ${e.message}")
        }
    }

    // 차단막 제거
    private fun removeScrim() {
        scrimView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        scrimView = null
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
            "action": "$action",
            "platform": "${platformOf(lastScrollPkg)}"
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

    // ── userlog pending 큐 (prefs 영속) ───────────────────────
    // 큐는 JSON 배열 [{"id":String,"ts":Long,"count":Int}, ...] 로 prefs 에 저장 — 앱이 강제종료돼도 살아남음.
    // 전송 성공이 확인된 항목만 큐에서 빠지므로, 네트워크/토큰 실패 시 스크롤이 유실되지 않음.
    // id 는 중복 방지(idempotency)용 고유 키 — 재전송돼도 서버가 같은 id 를 dedup 하면 중복 집계 안 됨.
    // 아래 read/write/append 헬퍼는 모두 synchronized(pendingLock) 안에서만 호출해야 함.

    private data class PendingLog(val id: String, val ts: Long, val count: Int, val appPkg: String)

    // 패키지명 → 서버 전송용 정규화된 플랫폼 이름. 구버전 큐에는 appPkg 가 비어있을 수 있음("unknown").
    private fun platformOf(pkg: String): String = when (pkg) {
        "com.google.android.youtube" -> "youtube"
        "com.instagram.android" -> "instagram"
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "tiktok"
        else -> "unknown"
    }

    private fun readPendingLocked(): List<PendingLog> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PK_PENDING_USERLOGS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    // 구버전 큐(id/appPkg 없음) 호환 — 없으면 새 id 부여, appPkg 는 빈 문자열로
                    val id = o.optString("id").takeIf { it.isNotEmpty() }
                        ?: java.util.UUID.randomUUID().toString()
                    val appPkg = o.optString("appPkg", "")
                    PendingLog(id, o.optLong("ts"), o.optInt("count"), appPkg)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writePendingLocked(list: List<PendingLog>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject()
                .put("id", p.id).put("ts", p.ts).put("count", p.count).put("appPkg", p.appPkg))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PK_PENDING_USERLOGS, arr.toString()).apply()
    }

    private fun appendPendingLocked(ts: Long, count: Int, appPkg: String) {
        val entry = PendingLog(java.util.UUID.randomUUID().toString(), ts, count, appPkg)
        writePendingLocked(readPendingLocked() + entry)
    }

    // /userlogs 1건 전송(블로킹). 성공하면 true. ts = 배치 첫 스크롤 시각(실제 발생 시각).
    // logId 를 함께 보내 서버가 재전송 중복을 dedup 하도록 함.
    private fun postUserLogBlocking(token: String, userId: String, log: PendingLog): Boolean {
        return try {
            val json = """
            {
                "userId": "$userId",
                "logId": "${log.id}",
                "timestamp": ${log.ts},
                "scrollCount": ${log.count},
                "platform": "${platformOf(log.appPkg)}"
            }
        """.trimIndent()
            val client = okhttp3.OkHttpClient()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/userlogs")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            val code = response.code
            response.close()
            Log.d(TAG, "userLog 전송 — id=${log.id}, count=${log.count}, ts=${log.ts}, success=$ok ($code)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "userLog 전송 실패 — ${e.message}")
            false
        }
    }

    // pending 큐를 서버로 전송. 성공분만 큐에서 제거, 실패분은 남겨 다음 기회에 재시도.
    // isSending 가드로 한 번에 하나만 실행 — 큐 앞쪽 항목을 중복 전송/중복 제거하지 않게 함.
    private suspend fun sendPendingUserLogs() {
        if (!isSending.compareAndSet(false, true)) return  // 이미 전송 중
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"
            while (true) {
                val queue = synchronized(pendingLock) { readPendingLocked() }
                if (queue.isEmpty()) return

                val token = getFirebaseToken()
                if (token == null) {
                    Log.e(TAG, "userLog 전송 보류 — 토큰 없음 (다음 기회 재시도)")
                    return
                }

                // 네트워크 I/O 는 락 밖에서 — 전송 중에도 스크롤 카운팅이 막히지 않게
                val failed = ArrayList<PendingLog>()
                for (log in queue) {
                    if (log.count <= 0) continue
                    if (!postUserLogBlocking(token, userId, log)) failed.add(log)
                }

                // 처리한 앞쪽 queue.size 개를 제거하고, 그 사이 새로 들어온 항목(newer)과 실패분을 다시 씀.
                // 앞쪽에서 제거하는 건 이 전송(isSending 가드로 단일)뿐이고 enqueue 는 뒤에만 붙으므로 안전.
                val keepLooping = synchronized(pendingLock) {
                    val current = readPendingLocked()
                    val newer = if (current.size > queue.size) current.subList(queue.size, current.size).toList()
                                else emptyList()
                    writePendingLocked(failed + newer)
                    // 실패가 있으면 네트워크 불안정으로 보고 중단(다음 flush/시작 시 재시도). 없으면 newer 만큼 더 처리.
                    failed.isEmpty() && newer.isNotEmpty()
                }
                if (!keepLooping) return
            }
        } finally {
            isSending.set(false)
        }
    }

    private fun scheduleBatchTimer() {
        batchHandler.postDelayed(batchTimerRunnable, 5 * 60 * 1000L)
    }

    // 현재 배치를 pending 큐에 영속화(즉시, 동기)하고 전송을 트리거.
    // 큐에 넣은 뒤에만 카운터를 비우므로, 전송 코루틴이 끝나기 전에 프로세스가 죽어도 유실되지 않음.
    private fun flushBatch() {
        synchronized(pendingLock) {
            if (batchScrollCount > 0) {
                appendPendingLocked(batchFirstScrollMs, batchScrollCount, batchAppPkg)
                batchScrollCount = 0
                batchFirstScrollMs = 0L
                batchAppPkg = ""
            }
        }
        serviceScope.launch { sendPendingUserLogs() }
    }

    // 모든 popup view 제거 — variant 4 stacked 포함 전체 dismiss
    private fun dismissAllPopups() {
        val copy = popupViews.toList()
        popupViews.clear()
        isPopupShowing = false
        blockPopupShowing = false
        copy.forEach { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        removeScrim()
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
