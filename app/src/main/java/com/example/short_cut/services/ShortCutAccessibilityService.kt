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
    }

    // 서비스 시작 시 Room DB에서 데이터 초기화
    private suspend fun initializeOnStart() {
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
        val userLimit = userLimitDao.getLimit(userId)
        if (userLimit != null) {
            hourlyLimit = userLimit.hourlyLimit
            dailyLimit = userLimit.dailyLimit
            Log.d(TAG, "limit 로드 완료 — hourly: $hourlyLimit, daily: $dailyLimit")
        } else {
            Log.d(TAG, "저장된 limit 없음 — 기본값 사용 (hourly: $hourlyLimit, daily: $dailyLimit)")
        }
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

            // 최근 1시간 스크롤 횟수 조회 (슬라이딩 윈도우)
            val oneHourAgo = now - (60 * 60 * 1000L)
            val hourlyCount = scrollHistoryDao.countLastHour(oneHourAgo)

            Log.d(TAG, "스크롤 카운트 — hourly: $hourlyCount/$hourlyLimit, daily: $dailyCount/$dailyLimit")

            // 소켓 이벤트 전송
            val prefs = getSharedPreferences("short_cut_prefs", MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"
            SocketManager.emitScrollEvent(
                userId = userId,
                appPkg = TARGET_PACKAGE,
                scrollCount = hourlyCount
            ) { status, message ->
                Log.d(TAG, "ACK: $status / $message")
            }

            // ignore 후 추가 감지 모드인 경우
            if (isPostIgnoreMode) {
                postIgnoreCount++
                Log.d(TAG, "post-ignore 카운트: $postIgnoreCount / $POST_IGNORE_THRESHOLD")

                if (postIgnoreCount >= POST_IGNORE_THRESHOLD) {
                    // 10번 더 본 후 binge 상태인지 확인
                    isPostIgnoreMode = false
                    postIgnoreCount = 0

                    if (hourlyCount >= hourlyLimit) {
                        // 아직 binge 상태 → 팝업 다시 표시
                        Log.d(TAG, "post-ignore binge 감지 → 팝업")
                        withContext(Dispatchers.Main) { showPopup() }
                    } else {
                        // binge 아님 → 일반 모드로 복귀
                        Log.d(TAG, "post-ignore 정상 → 일반 모드 복귀")
                    }
                }
                return@launch
            }

            // hourly limit 초과 체크
            if (hourlyCount >= hourlyLimit) {
                Log.d(TAG, "hourly limit 초과 → 팝업")
                withContext(Dispatchers.Main) { showPopup() }
                return@launch
            }

            // daily limit 초과 체크
            if (dailyCount >= dailyLimit) {
                Log.d(TAG, "daily limit 초과 → 팝업")
                withContext(Dispatchers.Main) { showPopup() }
            }
        }
    }

    private fun showPopup() {
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
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            view.findViewById<Button>(R.id.btnIgnore).setOnClickListener {
                dismissPopup()
                // ignore 선택 → post-ignore 모드 시작
                isPostIgnoreMode = true
                postIgnoreCount = 0
                Log.d(TAG, "ignore 선택 → post-ignore 모드 시작 (${POST_IGNORE_THRESHOLD}회 감지)")
            }

            windowManager?.addView(view, params)
            popupView = view
            Log.d(TAG, "차단 팝업 표시")
        }
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
        super.onDestroy()
    }
}