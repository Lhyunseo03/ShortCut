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

class ShortCutAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortCut"
        const val TARGET_PACKAGE = "com.google.android.youtube"
        const val SHORTS_ACTIVITY = "com.google.android.apps.youtube.app.watchwhile.MainActivity"
        const val LIMIT = 5
    }

    private var isInShortsMode = false
    private var totalShortsCount = 0
    private var lastCountTime = 0L
    private var shortsEnteredTime = 0L
    private var isPopupShowing = false
    private var windowManager: WindowManager? = null
    private var popupView: android.view.View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = arrayOf(TARGET_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
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

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                if (className == "android.widget.FrameLayout") return
                if (className.contains("Dialog")) return
                if (className.contains("Sheet")) return
                Log.d(TAG, "📱 화면: $className")

                if (className != SHORTS_ACTIVITY && isInShortsMode) {
                    isInShortsMode = false
                    Log.d(TAG, "🏠 쇼츠모드 OFF (다른 화면)")
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val itemCount = event.itemCount

                if (itemCount > 0) {
                    if (isInShortsMode) {
                        isInShortsMode = false
                        Log.d(TAG, "🔄 쇼츠모드 OFF (일반스크롤)")
                    }
                    return
                }

                if (itemCount == -1) {
                    if (now - lastCountTime < 1000L) return

                    if (!isInShortsMode) {
                        isInShortsMode = true
                        shortsEnteredTime = now
                        lastCountTime = now
                        Log.d(TAG, "📺 쇼츠 진입!")
                        return
                    }

                    if (now - shortsEnteredTime > 1000L) {
                        lastCountTime = now
                        totalShortsCount++
                        Log.d(TAG, "🎯 쇼츠 스냅! $totalShortsCount / $LIMIT")

                        if (totalShortsCount >= LIMIT) {
                            showPopup()
                            totalShortsCount = 0
                            isInShortsMode = false
                        }
                    }
                }
            }
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
    }
}