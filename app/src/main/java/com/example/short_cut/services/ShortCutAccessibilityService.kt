package com.example.short_cut.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.widget.Button
import com.example.short_cut.R
import com.example.short_cut.SocketManager
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

        // ignore 후 추가로 볼 수 있는 스크롤 횟수 (고정값)
        const val POST_IGNORE_THRESHOLD = 10
    }

    // ── Room DB ───────────────────────────────────────────────
    // Room DB DAO — 스크롤 기록 저장 및 조회에 사용
    private lateinit var scrollHistoryDao: com.example.short_cut.db.ScrollHistoryDao

    // Room DB DAO — 사용자 limit 설정 조회에 사용
    private lateinit var userLimitDao: com.example.short_cut.db.UserLimitDao

    // 코루틴 스코프 — DB 작업은 메인 스레드에서 실행하면 안 되므로 별도 스코프 사용
    // SupervisorJob: 하나의 코루틴이 실패해도 나머지에 영향 없음
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Limit 설정 ────────────────────────────────────────────
    // hourly limit — Room DB에서 로드, 기본값 50
    private var hourlyLimit = 50

    // daily limit — Room DB에서 로드, 기본값 100
    private var dailyLimit = 100

    // ── 카운터 ────────────────────────────────────────────────
    // 오늘 총 스크롤 횟수 — 앱 재시작 시 Room DB에서 복원
    private var dailyCount = 0

    // ignore 후 추가 스크롤 카운터 — 0이면 일반 모드
    private var postIgnoreCount = 0

    // ignore 후 추가 감지 모드 여부
    private var isPostIgnoreMode = false

    // ── 스크롤 감지 상태 ──────────────────────────────────────
    private var isInShortsMode = false
    private var lastCountTime = 0L
    private var shortsEnteredTime = 0L
    private var lastNodeCount = 0
    private var lastContentDesc = ""
    private var isPopupShowing = false
    private var windowManager: WindowManager? = null
    private var popupView: android.view.View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // daily limit 초과 여부 — true이면 하루 동안 팝업 재발생 안 함
    private var isDailyLimitReached = false

    // ── 배치 전송 ─────────────────────────────────────────────
    // 아직 서버에 전송하지 않은 누적 스크롤 횟수 — 10개 쌓이거나 5분 경과 시 전송
    private var batchScrollCount = 0

    // 배치 전송 트리거 횟수 — 이 횟수만큼 누적되면 즉시 전송
    private val BATCH_SIZE = 10

    // 5분 타이머를 관리하는 핸들러 — 메인 스레드에서 실행
    private val batchHandler = Handler(Looper.getMainLooper())

    // 5분마다 실행되는 타이머 Runnable
    // 누적된 스크롤을 전송하고 다시 5분 예약
    private val batchTimerRunnable = Runnable {
        flushBatch()         // 누적된 배치 전송
        scheduleBatchTimer() // 다시 5분 예약
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            packageNames = arrayOf(TARGET_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
        serviceInfo = info
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Room DB 인스턴스 초기화
        val db = AppDatabase.getDatabase(this)
        scrollHistoryDao = db.scrollHistoryDao()
        userLimitDao = db.userLimitDao()

        // 앱 시작 시 초기화 작업 (코루틴으로 비동기 실행)
        serviceScope.launch {
            initializeOnStart()
        }

        Log.d(TAG, "서비스 연결 | API ${android.os.Build.VERSION.SDK_INT}")
        SocketManager.connect()
        Log.d(TAG, "소켓 연결")

        // DB 및 소켓 초기화 완료 후 배치 타이머 시작
        // 5분마다 누적된 스크롤 횟수를 서버로 전송
        scheduleBatchTimer()
    }

    // 서비스 시작 시 Room DB에서 데이터 초기화
    private suspend fun initializeOnStart() {
        // 테스트용 — limit 설정 UI 구현 후 삭제
        hourlyLimit = 5
        dailyLimit = 10

        val now = System.currentTimeMillis()

        // 1주일 이상 된 스크롤 기록 삭제
        val oneWeekAgo = now - (7 * 24 * 60 * 60 * 1000L)
        scrollHistoryDao.deleteOlderThan(oneWeekAgo)
        Log.d(TAG, "오래된 스크롤 기록 삭제 완료")

        // 오늘 자정 이후 스크롤 횟수를 Room DB에서 불러와 dailyCount 복원
        val startOfDay = getStartOfDayTimestamp()
        dailyCount = scrollHistoryDao.countToday(startOfDay)
        Log.d(TAG, "오늘 스크롤 복원: $dailyCount")

        // 사용자 limit 설정 불러오기 (없으면 기본값 유지)
        val prefs = getSharedPreferences("short_cut_prefs", MODE_PRIVATE)
        val userId = prefs.getString("userId", "unknown") ?: "unknown"

        // userId가 바뀌었으면 Room DB 초기화 (다른 유저 데이터 복원 방지)
        val lastUserId = prefs.getString("lastUserId", "") ?: ""
        if (userId != lastUserId) {
            scrollHistoryDao.deleteOlderThan(System.currentTimeMillis() + 1L) // 전체 삭제
            prefs.edit().putString("lastUserId", userId).apply()
            dailyCount = 0
            isDailyLimitReached = false
            Log.d(TAG, "userId 변경 감지 → Room DB 초기화 (이전: $lastUserId, 현재: $userId)")
            return
        }

        val userLimit = userLimitDao.getLimit(userId)
        if (userLimit != null) {
            hourlyLimit = userLimit.hourlyLimit
            dailyLimit = userLimit.dailyLimit
            Log.d(TAG, "limit 로드 완료 — hourly: $hourlyLimit, daily: $dailyLimit")
        } else {
            Log.d(TAG, "저장된 limit 없음 — 기본값 사용 (hourly: $hourlyLimit, daily: $dailyLimit)")
        }

        // dailyCount가 이미 limit 이상이면 플래그 true로 복원 (앱 재시작 시 팝업 중복 방지)
        // limit 로드 후에 판단해야 정확함
        isDailyLimitReached = dailyCount >= dailyLimit
        Log.d(TAG, "daily limit 플래그 복원: $isDailyLimitReached (dailyCount: $dailyCount, dailyLimit: $dailyLimit)")
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

    private fun getShortsNodeCount(): Int {
        val root = rootInActiveWindow ?: return 0
        val nodes = root.findAccessibilityNodeInfosByViewId(SHORTS_NODE_ID)
        val count = nodes?.size ?: 0
        root.recycle()
        return count
    }

    private fun getShortsFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        val descs = mutableListOf<String>()
        collectContentDescs(root, descs)
        root.recycle()
        // 처음 5개만 합쳐서 fingerprint로 사용
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
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        val now = System.currentTimeMillis()

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
                } else if (nodeCount == 0 && isInShortsMode) {
                    isInShortsMode = false
                    lastNodeCount = 0
                    Log.d(TAG, "쇼츠모드 OFF")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isInShortsMode) return

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

        // Room DB 저장 및 limit 체크는 코루틴으로 실행
        serviceScope.launch {
            // 스크롤 이벤트를 Room DB에 저장
            scrollHistoryDao.insert(
                ScrollHistory(appPkg = TARGET_PACKAGE, timestamp = now)
            )

            // daily 카운트 증가 (메모리 변수)
            dailyCount++

            // 배치 카운터 증가 — BATCH_SIZE(10개) 쌓이면 즉시 서버 전송
            batchScrollCount++
            if (batchScrollCount >= BATCH_SIZE) {
                flushBatch()
            }

            // 최근 1시간 스크롤 횟수 조회 (슬라이딩 윈도우)
            val oneHourAgo = now - (60 * 60 * 1000L)
            val hourlyCount = scrollHistoryDao.countLastHour(oneHourAgo)
            lastHourlyCount = hourlyCount  // ← 추가

            Log.d(TAG, "스크롤 카운트 — hourly: $hourlyCount/$hourlyLimit, daily: $dailyCount/$dailyLimit")

            // 소켓 이벤트 전송
            val prefs = getSharedPreferences("short_cut_prefs", MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"
            // 1씩 전송하는 코드 있었는데 지웠어용

            // ignore 후 추가 감지 모드인 경우
            if (isPostIgnoreMode) {
                postIgnoreCount++
                Log.d(TAG, "post-ignore 카운트: $postIgnoreCount / $POST_IGNORE_THRESHOLD")

                // daily limit 초과 시 post-ignore 모드 중에도 개입
                if (dailyCount >= dailyLimit && !isDailyLimitReached) {
                    isDailyLimitReached = true
                    isPostIgnoreMode = false
                    postIgnoreCount = 0
                    Log.d(TAG, "daily limit 초과 (post-ignore 중) → 팝업")
                    withContext(Dispatchers.Main) { showPopup("daily") }
                    return@launch
                }

                if (postIgnoreCount >= POST_IGNORE_THRESHOLD) {
                    isPostIgnoreMode = false
                    postIgnoreCount = 0

                    if (hourlyCount >= hourlyLimit) {
                        Log.d(TAG, "post-ignore binge 감지 → 팝업")
                        withContext(Dispatchers.Main) { showPopup("hourly") }
                    } else {
                        Log.d(TAG, "post-ignore 정상 → 일반 모드 복귀")
                    }
                }
                return@launch
            }

            // hourly limit 초과 체크
            if (hourlyCount >= hourlyLimit) {
                Log.d(TAG, "hourly limit 초과 → 팝업")
                withContext(Dispatchers.Main) { showPopup("hourly") }
                return@launch
            }

            // daily limit 초과 체크
            if (dailyCount >= dailyLimit && !isDailyLimitReached) {
                isDailyLimitReached = true
                Log.d(TAG, "daily limit 초과 → 팝업")
                withContext(Dispatchers.Main) { showPopup("daily") }
            }
        }
    }

    private fun showPopup(limitType: String) {
        if (isPopupShowing) return
        isPopupShowing = true

        mainHandler.post {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.overlay_popup, null)

            val widthInDp = 250
            val widthInPx = (widthInDp * resources.displayMetrics.density).toInt()

            val params = WindowManager.LayoutParams(
                widthInPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            view.findViewById<Button>(R.id.btnStop).setOnClickListener {
                dismissPopup()
                // stop 선택 → violation 서버 전송
                sendViolation(limitType, lastHourlyCount, "stop")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            view.findViewById<Button>(R.id.btnIgnore).setOnClickListener {
                dismissPopup()
                // ignore 선택 → violation 서버 전송
                sendViolation(limitType, lastHourlyCount, "ignore")
                isPostIgnoreMode = true
                postIgnoreCount = 0
                Log.d(TAG, "ignore 선택 → post-ignore 모드 시작")
            }

            windowManager?.addView(view, params)
            popupView = view
            Log.d(TAG, "차단 팝업 표시")
        }
    }
    // Firebase ID 토큰 가져오기
    // 서버 요청 시 Authorization: Bearer <token> 헤더에 사용
    // getIdToken(false): 토큰 유효하면 캐시 사용, 만료됐으면 자동 갱신
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
    // limitType: "hourly" 또는 "daily", action: "stop" 또는 "ignore"
    // Authorization 헤더에 Firebase 토큰 포함 — 서버 미들웨어가 검증
    private fun sendViolation(limitType: String, scrollCount: Int, action: String) {
        val prefs = getSharedPreferences("short_cut_prefs", MODE_PRIVATE)
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
                // Firebase 토큰 가져오기 — 없으면 인증 실패로 전송 중단
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
                    .addHeader("Authorization", "Bearer $token") // Firebase 인증 토큰 헤더
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
    // Firestore userLogs에 저장되어 일간/주간/월간 통계에 활용
    // Authorization 헤더에 Firebase 토큰 포함 — 서버 미들웨어가 검증
    private fun sendUserLog(scrollCount: Int) {
        val prefs = getSharedPreferences("short_cut_prefs", MODE_PRIVATE)
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
                // Firebase 토큰 가져오기 — 없으면 인증 실패로 전송 중단
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
                    .addHeader("Authorization", "Bearer $token") // Firebase 인증 토큰 헤더
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "userLog 배치 전송 완료 — scrollCount: $scrollCount, ${response.code}")
            } catch (e: Exception) {
                Log.e(TAG, "userLog 배치 전송 실패 — ${e.message}")
            }
        }
    }

    // 5분 후 batchTimerRunnable 실행 예약
// onServiceConnected에서 최초 1회 호출, 이후 타이머가 스스로 재예약
    private fun scheduleBatchTimer() {
        batchHandler.postDelayed(batchTimerRunnable, 5 * 60 * 1000L)
    }

    // 누적된 스크롤 횟수를 서버로 전송하고 카운터 초기화
// 누적 횟수가 0이면 전송하지 않음 (불필요한 요청 방지)
    private fun flushBatch() {
        val count = batchScrollCount
        if (count <= 0) return
        batchScrollCount = 0
        sendUserLog(count)
    }

    private fun dismissPopup() {
        popupView?.let {
            windowManager?.removeView(it)
            popupView = null
        }
        isPopupShowing = false
    }

    override fun onInterrupt() {
        dismissPopup()
        Log.d(TAG, "서비스 중단됨")
    }

    override fun onDestroy() {
        dismissPopup()
        // 타이머 취소 + 남은 배치 전송 (서비스 종료 전에 실행해야 함)
        batchHandler.removeCallbacks(batchTimerRunnable)
        flushBatch()
        super.onDestroy()
    }
}