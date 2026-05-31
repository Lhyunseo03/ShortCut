package com.example.short_cut.services.detectors

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent

// Instagram Reels 검출.
//
// 조사 결과:
//   - reels feed = com.instagram.android:id/clips_viewer_view_pager (ViewPager).
//   - TYPE_VIEW_SCROLLED 이벤트는 scrollY 는 항상 0 으로 옴 (누적 픽셀값 안 줌).
//   - 그러나 scrollDeltaY 는 매 이벤트마다 유효 — 한 swipe 의 fling 이벤트들 dy 합 ≈ H (페이지 높이).
//     예: H=1952 일 때 swipe 1번 = 865 + 1083 + 4 = 1952px.
//
// 알고리즘: dy 를 누적해서 |accumDy| >= H * 0.9 도달 시 1회 카운트, accumDy reset.
//   - dy=0 SCROLL (진입 시 ViewPager 초기화/포커스 이벤트) 은 무시 — 가짜 카운트 방지.
//   - debounce 불필요 (누적 방식이라 fling 잔여가 자연스럽게 한 카운트로 묶임).
//
// API 36 단말은 Reels inflate 가 늦어 WINDOW_STATE_CHANGED 시점에 노드가 안 보일 수 있음 → fallback 진입 보정.
class InstagramDetector : AppDetector(PACKAGE) {
    companion object {
        const val PACKAGE = "com.instagram.android"
        const val REEL_PAGER_ID = "com.instagram.android:id/clips_viewer_view_pager"
        // 진입 직후 ViewPager inflate/settle 버스트만 거르는 짧은 창.
        // 과거 1500ms 는 진입 1.5초 안의 첫 스와이프까지 같이 삼켜 "첫 스크롤 씹힘"의 원인이었다.
        // 가짜 이벤트는 dy==0 필터 + accumDy>=0.9*H 임계값이 이미 걸러주므로 짧게 둬도 안전.
        // (정확한 값은 실기기 logcat 으로 조정 — 진입 settle 이 더 길면 소폭 늘릴 것)
        const val NOISE_MS = 400L
        // 한 페이지 이동으로 인정할 dy 누적 임계값 — H 의 90% (snap 안 끝난 fling 도 충분히 잡힘)
        const val PAGE_THRESHOLD_NUM = 9
        const val PAGE_THRESHOLD_DEN = 10
        private const val TAG = "ShortCut"
    }

    private var shortsEnteredTime = 0L
    private var accumDy = 0   // 누적 픽셀 (위/아래 부호 보존)

    override fun onEvent(service: AccessibilityService, event: AccessibilityEvent): Outcome {
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val visible = service.isNodeVisibleInAnyWindow(REEL_PAGER_ID)
                if (visible && !inShortsMode) {
                    inShortsMode = true
                    shortsEnteredTime = now
                    accumDy = 0
                    Log.d(TAG, "[IG] Reels 진입")
                    return Outcome(entered = true)
                } else if (!visible && inShortsMode) {
                    inShortsMode = false
                    accumDy = 0
                    Log.d(TAG, "[IG] Reels 이탈")
                    return Outcome(exited = true)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val src = event.source ?: return Outcome.NONE
                val srcId: String
                val H: Int
                try {
                    srcId = src.viewIdResourceName ?: ""
                    val b = Rect()
                    src.getBoundsInScreen(b)
                    H = b.height()
                } finally {
                    src.recycle()
                }
                val dy = event.scrollDeltaY

                // fallback 진입 — WINDOW_STATE 누락 케이스 (API 36 같은 환경)
                if (!inShortsMode) {
                    if (srcId == REEL_PAGER_ID) {
                        inShortsMode = true
                        shortsEnteredTime = now
                        accumDy = 0
                        Log.d(TAG, "[IG] (fallback) Reels 진입")
                        return Outcome(entered = true)
                    }
                    return Outcome.NONE
                }

                if (srcId != REEL_PAGER_ID) return Outcome.NONE
                if (now - shortsEnteredTime < NOISE_MS) return Outcome.NONE
                if (dy == 0) return Outcome.NONE   // ViewPager 의 가짜 SCROLL (포커스/초기화 등) 거름
                if (H <= 0) return Outcome.NONE

                accumDy += dy
                val threshold = H * PAGE_THRESHOLD_NUM / PAGE_THRESHOLD_DEN
                if (Math.abs(accumDy) >= threshold) {
                    Log.d(TAG, "[IG] Reels 스크롤 검출 accumDy=$accumDy H=$H")
                    accumDy = 0
                    return Outcome(scrolled = true)
                }
                // 아직 누적 중 — 카운트 안 함 (디버그 정보용 로그)
                Log.d(TAG, "[IG] dy 누적 dy=$dy accumDy=$accumDy H=$H")
                return Outcome.NONE
            }
        }
        return Outcome.NONE
    }
}
