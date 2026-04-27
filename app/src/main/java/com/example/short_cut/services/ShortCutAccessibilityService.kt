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

class ShortCutAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortCut"
        const val TARGET_PACKAGE = "com.google.android.youtube"
        const val SHORTS_NODE_ID = "com.google.android.youtube:id/reel_player_page_container"
        const val LIMIT = 5
        const val DEBOUNCE_MS = 500L
        const val NOISE_MS = 1500L
    }

    private var isInShortsMode = false
    private var totalShortsCount = 0
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
        Log.d(TAG, "✅ 서비스 연결됨 | API ${android.os.Build.VERSION.SDK_INT}")
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
                    Log.d(TAG, "📺 쇼츠 진입! 노드=$nodeCount")
                    Log.d(TAG, "📝 fingerprint=$lastContentDesc")
                } else if (nodeCount == 0 && isInShortsMode) {
                    isInShortsMode = false
                    lastNodeCount = 0
                    Log.d(TAG, "🏠 쇼츠모드 OFF")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isInShortsMode) return

                val nodeCount = getShortsNodeCount()

                if (lastNodeCount == 2 && nodeCount == 1) {
                    val currentDesc = getShortsFingerprint()
                    Log.d(TAG, "📡 2→1 | 이전=$lastContentDesc")
                    Log.d(TAG, "📡 2→1 | 현재=$currentDesc")

                    if (currentDesc != lastContentDesc && currentDesc.isNotEmpty()) {
                        lastContentDesc = currentDesc
                        Log.d(TAG, "📡 영상 변경 확인!")
                        countShorts(now)
                    } else {
                        Log.d(TAG, "📡 스크롤 취소 (같은 영상)")
                    }
                }

                lastNodeCount = nodeCount
            }
        }
    }

    private fun countShorts(now: Long) {
        if (now - shortsEnteredTime < NOISE_MS) {
            Log.d(TAG, "⏳ 진입 노이즈 무시")
            return
        }
        if (now - lastCountTime < DEBOUNCE_MS) return

        lastCountTime = now
        totalShortsCount++
        Log.d(TAG, "🎯 쇼츠 스냅! $totalShortsCount / $LIMIT")

        if (totalShortsCount >= LIMIT) {
            showPopup()
            totalShortsCount = 0
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