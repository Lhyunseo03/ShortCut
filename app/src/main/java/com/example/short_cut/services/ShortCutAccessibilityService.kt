package com.example.short_cut.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.util.Log

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
        Log.d(TAG, "✅ 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                if (className == "android.widget.FrameLayout") return
                Log.d(TAG, "📱 화면: $className")

                if (className != SHORTS_ACTIVITY && isInShortsMode) {
                    isInShortsMode = false
                    Log.d(TAG, "🔄 쇼츠모드: OFF | 총: $totalShortsCount")
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val fromIndex = event.fromIndex
                val toIndex = event.toIndex
                val itemCount = event.itemCount
                val now = System.currentTimeMillis()

                // count=-1이 아닌 스크롤은 무조건 일반 스크롤
                if (itemCount != -1) {
                    // 쇼츠 모드였으면 해제
                    if (isInShortsMode) {
                        isInShortsMode = false
                        Log.d(TAG, "🔄 쇼츠모드: OFF (일반스크롤 감지)")
                    }
                    return
                }

                // 여기부터는 count=-1인 쇼츠 스크롤
                if (!isInShortsMode) {
                    isInShortsMode = true
                    shortsEnteredTime = now
                    lastCountTime = now
                    Log.d(TAG, "🔄 쇼츠모드: ON")
                    return  // 첫 진입 이벤트는 카운트 안함
                }

                // 진입 후 2초 이내 노이즈 무시
                if (now - shortsEnteredTime < 2000L) {
                    Log.d(TAG, "⏳ 진입 노이즈 무시")
                    return
                }

                // 1000ms 디바운스
                if (now - lastCountTime > 1000L) {
                    lastCountTime = now
                    totalShortsCount++
                    Log.d(TAG, "🎯 쇼츠 스냅! 총: $totalShortsCount / $LIMIT")

                    if (totalShortsCount >= LIMIT) {
                        Log.d(TAG, "🚨 제한 초과! 홈으로")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        totalShortsCount = 0
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ 서비스 중단됨")
    }
}