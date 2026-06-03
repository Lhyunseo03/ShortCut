package com.example.short_cut.services.detectors

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent

// TikTok For You 피드 검출.
//
// 조사 결과 (PROBE 로그):
//   - viewpager 의 TYPE_VIEW_SCROLLED 이벤트가 유효한 scrollY/dy 를 줌
//     (한 페이지당 ~1989px, viewpager 의 실제 높이와 일치).
//   - 사용자가 원래 짜려고 했던 `scrollY mod H ≈ 0` 알고리즘이 그대로 통함.
//
// 페이지 높이 H 는 src.getBoundsInScreen() 으로 매번 읽어 동적 적용 — 단말/회전 변동 대응.
// 패키지 ID 가 두 종류 (com.zhiliaoapp.musically / com.ss.android.ugc.trill) 라
// 인스턴스를 두 개 생성해서 메인 서비스에 등록. view-id 는 ":id/viewpager" 접미사로 매치.
class TiktokDetector(packageName: String) : AppDetector(packageName) {
    companion object {
        const val PACKAGE_GLOBAL = "com.zhiliaoapp.musically"
        const val PACKAGE_TRILL = "com.ss.android.ugc.trill"
        const val DEBOUNCE_MS = 300L
        const val NOISE_MS = 1500L
        // scrollY 가 페이지 경계에 얼마나 가까워야 "snap 완료" 로 볼지 — H 의 1/10
        const val SNAP_TOLERANCE_DIV = 10
        private const val TAG = "ShortCut"
        @Suppress("unused")
        private const val VIEWPAGER_ID_SUFFIX = ":id/viewpager"
        private const val VIEWPAGER_CLASS = "androidx.viewpager.widget.ViewPager"
    }

    private var modeEnteredTime = 0L
    private var lastCountTime = 0L
    private var lastPageIdx = 0
    private var pageHeight = 0

    override fun onEvent(service: AccessibilityService, event: AccessibilityEvent): Outcome {
        val now = System.currentTimeMillis()

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return Outcome.NONE

        val src = event.source ?: return Outcome.NONE
        try {
            val srcClass = src.className?.toString() ?: ""
            // For You 피드의 ViewPager 스크롤만 — 다른 RecyclerView 등은 무시.
            // [변경됨] 예전엔 resource-id 가 ":id/viewpager" 로 끝나야 통과했는데, 트릴 버전은
            //   id 가 난독화돼(예: com.ss.android.ugc.trill:id/hhd) 매칭에 실패 → 트릴에서
            //   스크롤이 전혀 감지되지 않는 버그가 있었다.
            //   이벤트는 서비스가 이미 틱톡 패키지로 라우팅하므로(packageName 매칭, 서비스 line 300),
            //   여기선 ViewPager 클래스만으로 피드를 식별한다.
            //   (글로벌 musically / 트릴 trill 모두 동일 클래스 androidx.viewpager.widget.ViewPager → 둘 다 커버)
            if (srcClass != VIEWPAGER_CLASS) return Outcome.NONE

            // page height — viewpager 실제 화면 높이
            val bounds = Rect()
            src.getBoundsInScreen(bounds)
            val h = bounds.height()
            if (h > 0) pageHeight = h
            val H = pageHeight
            if (H <= 0) return Outcome.NONE

            val scrollY = event.scrollY
            if (scrollY < 0) return Outcome.NONE

            // 첫 viewpager scroll = For You 진입 신호
            // TT 는 ViewPager SCROLLED 가 swipe snap 완료 시점에 1번만 오는 경우가 많음 → 첫 SCROLLED 가
            // 곧 사용자의 첫 스와이프인 셈. 진입 신호로만 처리하면 첫 스크롤이 씹힘.
            // → scrollY 가 페이지 경계에 정확히 snap 된 상태면 첫 스와이프로 보고 카운트도 같이 보고.
            //   (단순히 화면 진입만 한 시점이면 보통 scrollY=0 이라 snapped=false → baseline 만)
            if (!inShortsMode) {
                inShortsMode = true
                modeEnteredTime = now
                val firstPageIdx = (scrollY + H / 2) / H
                val tol = H / SNAP_TOLERANCE_DIV
                val snapped = firstPageIdx > 0 && Math.abs(scrollY - firstPageIdx * H) <= tol
                // baseline 설정:
                //  - snapped: 이미 스와이프 완료된 채 진입 → 이전 페이지를 baseline 으로 잡고 이번 진입에서 카운트.
                //  - 그 외(fling 중간에 첫 SCROLLED 도착 포함): 목적지(반올림)가 아니라 출발 페이지(내림 floor)를
                //    baseline 으로. 이렇게 해야 뒤따르는 snap 완료 이벤트의 pageIdx 가 baseline 과 달라져 첫 스크롤이
                //    카운트된다. (과거엔 반올림한 목적지를 baseline 으로 잡아 완료 이벤트가 dedup 돼 첫 스크롤이 씹혔음)
                lastPageIdx = if (snapped) (firstPageIdx - 1).coerceAtLeast(0) else (scrollY / H)
                if (snapped) lastCountTime = now
                Log.d(TAG, "[TT] For You 진입 H=$H scrollY=$scrollY idx=$firstPageIdx snapped=$snapped → baseline=$lastPageIdx")
                return Outcome(entered = true, scrolled = snapped)
            }

            // 현재 페이지 인덱스(반올림). lastPageIdx 와 같으면 fling 중 — 스킵.
            val pageIdx = (scrollY + H / 2) / H
            if (pageIdx == lastPageIdx) return Outcome.NONE

            // snap 근접 검증 — fling 중간값(예: scrollY=3348, H=1989, idx=2지만 실제론 미정착) 제외.
            // 정착했다면 scrollY 가 pageIdx*H 에 가까움.
            val expected = pageIdx * H
            val tolerance = H / SNAP_TOLERANCE_DIV
            if (Math.abs(scrollY - expected) > tolerance) return Outcome.NONE

            if (now - lastCountTime < DEBOUNCE_MS) {
                lastPageIdx = pageIdx
                return Outcome.NONE
            }

            lastPageIdx = pageIdx
            lastCountTime = now
            Log.d(TAG, "[TT] 스크롤 scrollY=$scrollY H=$H idx=$pageIdx")
            return Outcome(scrolled = true)
        } finally {
            src.recycle()
        }
    }

    override fun onPackageLeft() {
        super.onPackageLeft()
        lastPageIdx = 0
    }
}
