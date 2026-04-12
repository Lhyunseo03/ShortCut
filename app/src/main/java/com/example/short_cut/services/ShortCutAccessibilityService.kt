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
import android.util.Log
import android.widget.Button
import com.example.short_cut.R
// SocketManager.kt 파일을 이 파일에서 쓰겠다고 선언하는 거예요.
// 이게 없으면 아래 코드들이 "SocketManager가 뭔지 모르겠다"고 에러 내요.
import com.example.short_cut.SocketManager


class ShortCutAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortCut"
        const val TARGET_PACKAGE = "com.google.android.youtube"
        const val SHORTS_ACTIVITY = "com.google.android.apps.youtube.app.watchwhile.MainActivity"
        const val LIMIT = 5
        const val DEBOUNCE_MS = 300L
        const val NOISE_MS = 1500L
        const val NORMAL_SCROLL_LIMIT = 5
    }

    private var isInShortsMode = false
    private var totalShortsCount = 0
    private var lastCountTime = 0L
    private var shortsEnteredTime = 0L
    private var isPopupShowing = false
    private var normalScrollCount = 0
    private var windowManager: WindowManager? = null
    private var popupView: android.view.View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    //로그인할 때 닉네임을 short_cut_prefs에 저장해뒀으면 그 값을 읽어오고, 없으면 "unknown_user"로 대신 써요.
    private val userId: String
        get() = getSharedPreferences("short_cut_prefs", MODE_PRIVATE)
            .getString("userId", "unknown_user") ?: "unknown_user"

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
        Log.d(TAG, "✅ 서비스 연결됨 | API ${android.os.Build.VERSION.SDK_INT}")
        // 서비스가 켜지는 순간 서버에 연결해요.
        // 접근성 서비스가 활성화되자마자 소켓 연결도 같이 시작되는 거예요. 여기서 안 하면 스크롤 감지해도 보낼 곳이 없어요.
        SocketManager.connect()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        val now = System.currentTimeMillis()

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                if (className == "android.widget.FrameLayout") return
                if (className == "android.view.ViewGroup") return
                if (className.contains("Dialog")) return
                if (className.contains("Sheet")) return
                Log.d(TAG, "📱 화면: $className")

                if (className != SHORTS_ACTIVITY && isInShortsMode) {
                    isInShortsMode = false
                    normalScrollCount = 0
                    Log.d(TAG, "🏠 쇼츠모드 OFF (다른 화면)")
                }
            }

            // API 31: TYPE_VIEW_SCROLLED(4096) + itemCount=-1
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val itemCount = event.itemCount
                if (itemCount != -1) {
                    if (isInShortsMode) {
                        normalScrollCount++
                        if (normalScrollCount >= NORMAL_SCROLL_LIMIT) {
                            isInShortsMode = false
                            normalScrollCount = 0
                            Log.d(TAG, "🔄 쇼츠모드 OFF (일반스크롤)")
                        }
                    }
                    return
                }
                normalScrollCount = 0
                handleShortsScroll(now)
            }

            // API 36: TYPE_WINDOW_CONTENT_CHANGED(2048)
            // changeTypes=4099 → 쇼츠 진입
            // changeTypes=0 → 쇼츠 스와이프
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val changeTypes = event.contentChangeTypes
                if (changeTypes != 4099 && changeTypes != 0) return
                Log.d(TAG, "📡 쇼츠 감지! changeTypes=$changeTypes")
                handleShortsScroll(now)
            }
        }
    }

    private fun handleShortsScroll(now: Long) {
        if (now - lastCountTime < DEBOUNCE_MS) return

        if (!isInShortsMode) {
            isInShortsMode = true
            shortsEnteredTime = now
            lastCountTime = now
            Log.d(TAG, "📺 쇼츠 진입!")
            return
        }

        if (now - shortsEnteredTime < NOISE_MS) {
            Log.d(TAG, "⏳ 진입 노이즈 무시")
            return
        }

        lastCountTime = now
        totalShortsCount++

        //쇼츠를 한 번 스와이프할 때마다 서버로 데이터를 전송하는 부분이에요.
        // totalShortsCount++ 바로 다음에 있어서 카운트가 올라갈 때마다 즉시 서버에 알려줘요.
        SocketManager.emitScrollEvent(
            userId      = userId,
            appPkg      = TARGET_PACKAGE,
            scrollCount = totalShortsCount
        )
        Log.d(TAG, "🎯 쇼츠 스냅! $totalShortsCount / $LIMIT")

        if (totalShortsCount >= LIMIT) {
            showPopup()
            totalShortsCount = 0
            isInShortsMode = false
        }
    }

    private fun showPopup() {
        if (isPopupShowing) return
        isPopupShowing = true

        mainHandler.post {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.overlay_popup, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
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
                Log.d(TAG, "⚠️ 사용자가 무시함 → 그룹 알림 예정")
            }

            windowManager?.addView(view, params)
            popupView = view
            Log.d(TAG, "🚨 팝업 표시!")
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
        Log.d(TAG, "⚠️ 서비스 중단됨")
    }

    override fun onDestroy() {
        dismissPopup()
        super.onDestroy()
        // 서비스가 꺼질 때 소켓 연결도 같이 정리해요.
        // 이게 없으면 앱을 꺼도 연결이 계속 남아서 배터리랑 네트워크를 낭비해요.
        SocketManager.disconnect()
    }
}