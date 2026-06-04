package com.example.short_cut.services.detectors

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.short_cut.services.ShortCutAccessibilityService

// YouTube 쇼츠 검출 — 기존 (리팩터 전) 로직 그대로.
// 1) WINDOW_STATE_CHANGED 시 reel_player_page_container 노드 개수로 진입/이탈 판단
// 2) CONTENT_CHANGED 시 노드 카운트 2→1 전환 + contentDescription fingerprint 변경 으로 스크롤 검출
//    (YT 는 TYPE_VIEW_SCROLLED 의 scrollY/dy 가 항상 0 이라 모듈러식 못 씀)
// [변경됨] 느린 스크롤(2.5초+ 간격) 시 2→1 시점에 새 영상의 contentDescription 이 아직
// rootInActiveWindow 에 채워지지 않아 fingerprint 가 옛 값으로 읽혀 "같은 영상" 으로
// 잘못 취소되던 문제(약 30% 누락) 보정 — 같은 지문이면 즉시 취소 대신 짧은 지연 후 재확인.
class YoutubeDetector : AppDetector(PACKAGE) {
    companion object {
        const val PACKAGE = "com.google.android.youtube"
        const val SHORTS_NODE_ID = "com.google.android.youtube:id/reel_player_page_container"
        const val DEBOUNCE_MS = 500L
        const val NOISE_MS = 1500L
        // 2→1 전환은 잡혔지만 fingerprint 가 아직 갱신 안 됐을 때 이만큼 뒤에 재확인.
        // 측정상 느린 스크롤에서 새 영상 desc 가 들어오기까지의 갭을 덮을 정도로 충분히 길고,
        // 사용자가 곧바로 다음 스와이프를 시도할 정도로 짧지 않은 값.
        const val FINGERPRINT_RECHECK_MS = 250L
        private const val TAG = "ShortCut"
    }

    private var shortsEnteredTime = 0L
    private var lastCountTime = 0L
    private var lastNodeCount = 0
    private var lastContentDesc = ""

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRecheck: Runnable? = null

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
                        cancelPendingRecheck()
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
                        // 동기 경로에서 이미 확정 — 예약된 재확인이 있으면 중복 카운트 방지로 취소
                        cancelPendingRecheck()
                        if (now - shortsEnteredTime < NOISE_MS) {
                            Log.d(TAG, "[YT] 진입 노이즈 무시")
                            return Outcome.NONE
                        }
                        if (now - lastCountTime < DEBOUNCE_MS) return Outcome.NONE
                        lastCountTime = now
                        Log.d(TAG, "[YT] 영상 변경 확인")
                        return Outcome(scrolled = true)
                    } else {
                        // [변경됨] 즉시 취소 → 짧은 지연 후 재확인. 느린 스크롤(또박또박 넘기는 경우)에는
                        // 이 시점에 새 영상 desc 가 아직 안 들어와 fingerprint 가 옛 값으로 읽힘.
                        // FINGERPRINT_RECHECK_MS 후 다시 읽어서 그새 바뀌었으면 비동기로 스크롤 카운트.
                        Log.d(TAG, "[YT] 지문 동일 — ${FINGERPRINT_RECHECK_MS}ms 후 재확인 예약")
                        scheduleRecheck(service)
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
        cancelPendingRecheck()
    }

    // [신규] 2→1 인데 fingerprint 가 아직 옛 값일 때 호출 — FINGERPRINT_RECHECK_MS 뒤 다시 읽어
    // 그새 영상이 바뀌었으면 서비스로 비동기 스크롤 카운트를 알린다.
    // 이 시점엔 lastNodeCount 가 이미 1 로 갱신되어 후속 CONTENT_CHANGED 가 2→1 을 다시 못 잡으므로,
    // 이 타이머가 그 스크롤을 잡는 유일한 경로다.
    private fun scheduleRecheck(service: AccessibilityService) {
        cancelPendingRecheck()
        val task = Runnable {
            pendingRecheck = null
            if (!inShortsMode) return@Runnable
            val currentDesc = service.getContentFingerprint()
            if (currentDesc.isEmpty() || currentDesc == lastContentDesc) {
                Log.d(TAG, "[YT] 재확인 — 정말 같은 영상 (취소 확정)")
                return@Runnable
            }
            val now = System.currentTimeMillis()
            if (now - shortsEnteredTime < NOISE_MS) {
                Log.d(TAG, "[YT] 재확인 — 진입 노이즈 무시")
                return@Runnable
            }
            if (now - lastCountTime < DEBOUNCE_MS) {
                Log.d(TAG, "[YT] 재확인 — debounce")
                return@Runnable
            }
            lastContentDesc = currentDesc
            lastCountTime = now
            Log.d(TAG, "[YT] 재확인 — 영상 변경 확인 (느린 스크롤 복구)")
            (service as? ShortCutAccessibilityService)?.onDeferredScroll(packageName)
        }
        pendingRecheck = task
        handler.postDelayed(task, FINGERPRINT_RECHECK_MS)
    }

    private fun cancelPendingRecheck() {
        pendingRecheck?.let { handler.removeCallbacks(it) }
        pendingRecheck = null
    }
}
