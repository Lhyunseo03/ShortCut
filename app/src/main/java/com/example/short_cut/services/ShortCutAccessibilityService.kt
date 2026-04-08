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
import java.util.concurrent.Executors

class ShortCutAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortCut"
        const val TARGET_PACKAGE = "com.google.android.youtube"
        const val SHORTS_ACTIVITY = "com.google.android.apps.youtube.app.watchwhile.MainActivity"
        const val LIMIT = 5
        const val NODE_THRESHOLD = 20
    }

    private var isInShortsMode = false
    private var totalShortsCount = 0
    private var lastCountTime = 0L
    private var shortsEnteredTime = 0L
    private var isPopupShowing = false
    private var windowManager: WindowManager? = null
    private var popupView: android.view.View? = null

    // 노드 체크용 별도 스레드
    private val executor = Executors.newSingleThreadExecutor()
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
        Log.d(TAG, "✅ 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 1. 화면이 통째로 바뀌었을 때 (홈 탭 클릭 등)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                // 유튜브 메인 액티비티가 아니면 일단 쇼츠 모드 해제 (오작동 방지)
                if (className != SHORTS_ACTIVITY && isInShortsMode) {
                    isInShortsMode = false
                    Log.d(TAG, "🏠 다른 화면 진입 → 쇼츠 모드 OFF")
                }
            }

            // 2. 오직 스크롤(스와이프) 이벤트에만 집중!
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val itemCount = event.itemCount

                // [A] 일반 피드 (홈, 검색 결과 등) 스크롤
                if (itemCount > 0) {
                    if (isInShortsMode) {
                        isInShortsMode = false
                        Log.d(TAG, "🔄 일반 피드 스크롤 감지 → 쇼츠 모드 OFF")
                    }
                    return
                }

                // [B] 쇼츠 스와이프 (itemCount == -1)
                if (itemCount == -1) {
                    // 연속 스와이프 방지 (1초 디바운스)
                    if (now - lastCountTime < 1000L) return

                    // 쇼츠 탭에 처음 들어왔을 때
                    if (!isInShortsMode) {
                        isInShortsMode = true
                        shortsEnteredTime = now
                        lastCountTime = now
                        Log.d(TAG, "📺 쇼츠 진입!")
                        return
                    }

                    // 진입 후 1초가 지난 뒤의 스크롤만 '넘기기'로 인정
                    if (now - shortsEnteredTime > 1000L) {
                        lastCountTime = now
                        totalShortsCount++
                        Log.d(TAG, "🎯 쇼츠 스냅! 현재: $totalShortsCount / $LIMIT")

                        if (totalShortsCount >= LIMIT) {
                            showPopup()
                            totalShortsCount = 0
                            isInShortsMode = false // 팝업 띄우고 초기화
                        }
                    }
                }
            }
        }
    }

    private fun countTextNodes(node: AccessibilityNodeInfo, depth: Int = 0): Int {
        if (depth > 8) return 0
        var count = 0
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.length > 2 || desc.length > 2) count++
        for (i in 0 until node.childCount) {
            count += countTextNodes(node.getChild(i) ?: continue, depth + 1)
        }
        return count
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
        executor.shutdown()
        dismissPopup()
        super.onDestroy()
    }
}