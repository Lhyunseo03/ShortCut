package com.example.short_cut.services.detectors

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

// YouTube 쇼츠 검출 — 기존 (리팩터 전) 로직 그대로.
// 1) WINDOW_STATE_CHANGED 시 reel_player_page_container 노드 개수로 진입/이탈 판단
// 2) CONTENT_CHANGED 시 노드 카운트 2→1 전환 + contentDescription fingerprint 변경 으로 스크롤 검출
//    (YT 는 TYPE_VIEW_SCROLLED 의 scrollY/dy 가 항상 0 이라 모듈러식 못 씀)
class YoutubeDetector : AppDetector(PACKAGE) {
    companion object {
        const val PACKAGE = "com.google.android.youtube"
        const val SHORTS_NODE_ID = "com.google.android.youtube:id/reel_player_page_container"
        const val DEBOUNCE_MS = 500L
        const val NOISE_MS = 1500L
        private const val TAG = "ShortCut"
    }

    private var shortsEnteredTime = 0L
    private var lastCountTime = 0L
    private var lastNodeCount = 0
    private var lastContentDesc = ""

    override fun onEvent(service: AccessibilityService, event: AccessibilityEvent): Outcome {
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val nodeCount = service.countNodesById(SHORTS_NODE_ID)
                if (nodeCount >= 1 && !inShortsMode) {
                    inShortsMode = true
                    shortsEnteredTime = now
                    lastCountTime = now
                    lastNodeCount = nodeCount
                    lastContentDesc = service.getContentFingerprint()
                    Log.d(TAG, "[YT] 쇼츠 진입 노드=$nodeCount")
                    return Outcome(entered = true)
                } else if (nodeCount == 0 && inShortsMode) {
                    // 활성 window 에 노드가 없어도 다른 window (댓글 시트 등) 뒤에 살아있으면 모드 유지
                    if (service.isNodeVisibleInAnyWindow(SHORTS_NODE_ID)) {
                        Log.d(TAG, "[YT] 오버레이 감지 — 쇼츠모드 유지")
                    } else {
                        inShortsMode = false
                        lastNodeCount = 0
                        Log.d(TAG, "[YT] 쇼츠모드 OFF")
                        return Outcome(exited = true)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!inShortsMode) return Outcome.NONE
                val nodeCount = service.countNodesById(SHORTS_NODE_ID)
                if (lastNodeCount == 2 && nodeCount == 1) {
                    val currentDesc = service.getContentFingerprint()
                    if (currentDesc != lastContentDesc && currentDesc.isNotEmpty()) {
                        lastContentDesc = currentDesc
                        lastNodeCount = nodeCount
                        if (now - shortsEnteredTime < NOISE_MS) {
                            Log.d(TAG, "[YT] 진입 노이즈 무시")
                            return Outcome.NONE
                        }
                        if (now - lastCountTime < DEBOUNCE_MS) return Outcome.NONE
                        lastCountTime = now
                        Log.d(TAG, "[YT] 영상 변경 확인")
                        return Outcome(scrolled = true)
                    } else {
                        Log.d(TAG, "[YT] 스크롤 취소 (같은 영상)")
                    }
                }
                lastNodeCount = nodeCount
            }
        }
        return Outcome.NONE
    }

    override fun onPackageLeft() {
        super.onPackageLeft()
        lastNodeCount = 0
    }
}
