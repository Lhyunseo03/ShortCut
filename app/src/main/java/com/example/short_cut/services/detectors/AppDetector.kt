package com.example.short_cut.services.detectors

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// 앱별 쇼츠 검출기 베이스.
// onEvent() 는 한 이벤트를 받아 (진입/이탈/스크롤) 셋 중 하나의 outcome 을 돌려줌.
// 앱 고유 상태(view-id, scrollY 누적, fingerprint 등)는 각 detector 가 보관.
abstract class AppDetector(val packageName: String) {

    // 현재 그 앱의 쇼츠 피드를 보고 있는지. 메인 서비스가 5분 차단/팝업 재표시 결정에 사용.
    var inShortsMode = false
        protected set

    abstract fun onEvent(service: AccessibilityService, event: AccessibilityEvent): Outcome

    // 사용자가 그 앱을 떠난 게 확인됐을 때 — 상태 리셋
    open fun onPackageLeft() {
        inShortsMode = false
    }

    data class Outcome(
        val entered: Boolean = false,
        val exited: Boolean = false,
        val scrolled: Boolean = false
    ) {
        companion object { val NONE = Outcome() }
    }
}

// ── AccessibilityService 헬퍼 ─────────────────────────────────
// 활성 window 루트에서 viewId 매치 노드 개수
fun AccessibilityService.countNodesById(viewId: String): Int {
    val root = rootInActiveWindow ?: return 0
    return try {
        root.findAccessibilityNodeInfosByViewId(viewId)?.size ?: 0
    } finally {
        root.recycle()
    }
}

// 활성 window 밖(오버레이/시트 등) 까지 포함해서 한 군데라도 viewId 노드가 있는지
fun AccessibilityService.isNodeVisibleInAnyWindow(viewId: String): Boolean {
    val ws = try { windows } catch (_: Exception) { null }
    if (ws.isNullOrEmpty()) return countNodesById(viewId) > 0
    for (w in ws) {
        val root = w.root ?: continue
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if ((nodes?.size ?: 0) > 0) return true
        } finally {
            root.recycle()
        }
    }
    return false
}

// rootInActiveWindow 의 contentDescription 들 중 길이 5 초과 첫 N개로 만든 fingerprint
// 영상 변경 검증용 — 같은 영상에서 노드 재구성될 때 같은 fingerprint 가 나와 false positive 방지
fun AccessibilityService.getContentFingerprint(limit: Int = 5): String {
    val root = rootInActiveWindow ?: return ""
    return try {
        val descs = mutableListOf<String>()
        collectContentDescs(root, descs, limit)
        descs.take(limit).joinToString("|")
    } finally {
        root.recycle()
    }
}

private fun collectContentDescs(node: AccessibilityNodeInfo, descs: MutableList<String>, limit: Int) {
    val desc = node.contentDescription?.toString()
    if (!desc.isNullOrEmpty() && desc.length > 5) descs.add(desc)
    for (i in 0 until node.childCount) {
        if (descs.size >= limit) return
        val child = node.getChild(i) ?: continue
        collectContentDescs(child, descs, limit)
    }
}
