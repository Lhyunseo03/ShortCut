package com.example.short_cut

import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────
// 그룹 API — 앱이 가정한 JSON 계약 (서버 미구현 상태에서 작성. 서버와 다르면 이 파일의 파서만 고치면 됨)
//
//  GET    /groups                      → { "maxGroups": 5, "groups": [ GroupSummary ] }
//  POST   /groups                      ← { name, description, goal: { daily, hourly }, voteThreshold }
//                                      → { "groupId": "..." }
//  GET    /groups/{gid}                → GroupSummary + { "members": [ Member ] }
//  GET    /groups/{gid}/status         → { "members": [ { userId, todayCount, lastHourCount, lastSeenAt, lastScrollAt } ] }
//  POST   /groups/{gid}/invites        → { "code": "ABC123", "expiresAt": ms }
//  GET    /invites/{code}              → GroupSummary + { "code", "expiresAt" }
//  POST   /groups/join                 ← { code }  → { "groupId": "..." }
//  DELETE /groups/{gid}/members/me     → { "status": "ok" }
//
//  GroupSummary = { groupId, name, description, goal: { daily, hourly }, voteThreshold(60~100),
//                   memberCount, maxMembers(기본 10), myTodayCount(GET /groups 에서만) }
//  Member       = { userId, nickname, todayCount, lastHourCount, lastSeenAt(ms|null), lastScrollAt(ms|null) }
//
//  거절은 4xx + { "error": "사용자에게 그대로 보여 줄 문구" } (예: 409 "정원이 찼어요").
// ─────────────────────────────────────────────────────────────────────────

internal const val MAX_GROUPS_DEFAULT = 5
internal const val MAX_MEMBERS_DEFAULT = 10
internal val VOTE_THRESHOLDS = listOf(60, 70, 80, 90, 100)

// 초대 링크를 여는 랜딩 페이지 주소. TODO: 랜딩 페이지(GitHub Pages 등) 배포 후 실제 주소로 교체.
// 비어 있으면 공유 문구에 링크 없이 코드만 넣는다.
internal const val INVITE_LANDING_URL = ""

internal data class GroupSummary(
    val groupId: String,
    val name: String,
    val description: String,
    val dailyLimit: Int,
    val hourlyLimit: Int,
    val voteThreshold: Int,
    val memberCount: Int,
    val maxMembers: Int,
    val myTodayCount: Int?      // GET /groups 에서만 옴. 없으면 호출부가 로컬 카운트로 대체
)

internal data class GroupMember(
    val userId: String,
    val nickname: String,
    val todayCount: Int,
    val lastHourCount: Int,
    val lastSeenAt: Long?,
    val lastScrollAt: Long?
)

internal data class GroupList(val groups: List<GroupSummary>, val maxGroups: Int)
internal data class GroupDetail(val summary: GroupSummary, val members: List<GroupMember>)
internal data class InviteInfo(val code: String, val expiresAt: Long?, val group: GroupSummary)

// 성공이면 value, 실패면 사용자에게 보여 줄 error 문구.
internal data class GroupResult<T>(val value: T?, val error: String?)

private fun <T> fail(res: ApiResult, fallback: String): GroupResult<T> =
    GroupResult(null, res.error ?: if (res.code == 0) "네트워크 연결을 확인해 주세요" else fallback)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null

private fun parseSummary(o: JSONObject): GroupSummary {
    val goal = o.optJSONObject("goal")
    return GroupSummary(
        groupId = o.optString("groupId"),
        name = o.optString("name"),
        description = o.optString("description"),
        dailyLimit = goal?.optInt("daily", 0) ?: 0,
        hourlyLimit = goal?.optInt("hourly", 0) ?: 0,
        voteThreshold = o.optInt("voteThreshold", 0),
        memberCount = o.optInt("memberCount", 0),
        maxMembers = o.optInt("maxMembers", MAX_MEMBERS_DEFAULT),
        myTodayCount = if (o.has("myTodayCount")) o.optInt("myTodayCount") else null
    )
}

private fun parseMembers(o: JSONObject): List<GroupMember> {
    val arr = o.optJSONArray("members") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val m = arr.optJSONObject(i) ?: return@mapNotNull null
        GroupMember(
            userId = m.optString("userId"),
            nickname = m.optString("nickname").ifBlank { "이름 없음" },
            todayCount = m.optInt("todayCount", 0),
            lastHourCount = m.optInt("lastHourCount", 0),
            lastSeenAt = m.optLongOrNull("lastSeenAt"),
            lastScrollAt = m.optLongOrNull("lastScrollAt")
        )
    }
}

internal suspend fun fetchGroups(): GroupResult<GroupList> {
    val res = authedRequest("GET", "/groups")
    // 404 = 서버에 그룹 API 가 아직 배포되지 않음 → 오류 대신 "가입한 그룹 없음"으로 취급.
    // TODO: 서버에 GET /groups 가 배포되면 이 분기 삭제 (그때의 404 는 진짜 오류).
    if (res.code == 404) return GroupResult(GroupList(emptyList(), MAX_GROUPS_DEFAULT), null)
    val json = res.json
    if (!res.ok || json == null) return fail(res, "그룹 목록을 불러오지 못했어요")
    val arr = json.optJSONArray("groups")
    val groups = if (arr == null) emptyList() else
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseSummary) }
    return GroupResult(GroupList(groups, json.optInt("maxGroups", MAX_GROUPS_DEFAULT)), null)
}

// 성공 시 새 그룹의 groupId.
internal suspend fun createGroup(
    name: String, description: String, dailyLimit: Int, hourlyLimit: Int, voteThreshold: Int
): GroupResult<String> {
    val body = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("goal", JSONObject().put("daily", dailyLimit).put("hourly", hourlyLimit))
        .put("voteThreshold", voteThreshold)
    val res = authedRequest("POST", "/groups", body)
    val gid = res.json?.optString("groupId").orEmpty()
    return if (res.ok && gid.isNotEmpty()) GroupResult(gid, null) else fail(res, "그룹을 만들지 못했어요")
}

internal suspend fun fetchGroupDetail(groupId: String): GroupResult<GroupDetail> {
    val res = authedRequest("GET", "/groups/$groupId")
    val json = res.json
    if (!res.ok || json == null) return fail(res, "그룹 정보를 불러오지 못했어요")
    return GroupResult(GroupDetail(parseSummary(json), parseMembers(json)), null)
}

// 카운트만 가볍게 새로고침 (30초 주기). 실패하면 null — 화면은 직전 값을 유지.
internal suspend fun fetchGroupStatus(groupId: String): List<GroupMember>? {
    val res = authedRequest("GET", "/groups/$groupId/status")
    val json = res.json
    return if (res.ok && json != null) parseMembers(json) else null
}

// 성공 시 6자리 초대 코드.
internal suspend fun createInvite(groupId: String): GroupResult<String> {
    val res = authedRequest("POST", "/groups/$groupId/invites")
    val code = res.json?.optString("code").orEmpty()
    return if (res.ok && code.isNotEmpty()) GroupResult(code, null) else fail(res, "초대 코드를 만들지 못했어요")
}

internal suspend fun fetchInvite(code: String): GroupResult<InviteInfo> {
    val res = authedRequest("GET", "/invites/$code")
    val json = res.json
    if (!res.ok || json == null) return fail(res, "초대 코드를 확인해 주세요")
    return GroupResult(InviteInfo(code, json.optLongOrNull("expiresAt"), parseSummary(json)), null)
}

// 성공 시 가입한 그룹의 groupId.
internal suspend fun joinGroup(code: String): GroupResult<String> {
    val res = authedRequest("POST", "/groups/join", JSONObject().put("code", code))
    val gid = res.json?.optString("groupId").orEmpty()
    return if (res.ok && gid.isNotEmpty()) GroupResult(gid, null) else fail(res, "그룹에 참여하지 못했어요")
}

internal suspend fun leaveGroup(groupId: String): GroupResult<Unit> {
    val res = authedRequest("DELETE", "/groups/$groupId/members/me")
    return if (res.ok) GroupResult(Unit, null) else fail(res, "탈퇴하지 못했어요")
}
