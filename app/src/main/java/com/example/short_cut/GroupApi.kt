package com.example.short_cut

import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────
// 그룹 API — 서버 API_SPEC_groups.md 와 맞춘 JSON 계약. (1단계 9/27 배포: POST /groups, GET /groups, GET /groups/{gid})
// 서버와 다른 부분이 생기면 이 파일의 파서만 고치면 됨.
//
//  GET    /groups                      → { "maxGroups": 5, "groups": [ GroupSummary ] }
//  POST   /groups                      ← { name, description, goal: { dailyLimit, hourlyLimit }, approvalRate }
//                                      → { "groupId": "..." }
//  GET    /groups/{gid}                → GroupSummary + { "members": [ Member ] }
//  GET    /groups/{gid}/status         → { "members": [ { userId, todayCount, lastHourCount, lastSeenAt, lastScrollAt } ] }
//  POST   /invites  ← { groupId }      → { "code": "ABC123", "expiresAt": ms }   (2단계, 스펙 §5)
//  GET    /invites/{code}              → GroupSummary + { "code", "expiresAt" }
//  POST   /groups/join                 ← { code }  → { "groupId": "..." }
//  DELETE /groups/{gid}/members/me     → { "status": "ok" }
//
//  GroupSummary = { groupId, name, description, goal: { dailyLimit, hourlyLimit }, approvalRate(60~100),
//                   memberCount, maxMembers(기본 10), myTodayCount(GET /groups 에서만) }
//  Member       = { userId, nickname, todayCount, lastHourCount, lastSeenAt(ms|null), lastScrollAt(ms|null) }
//
//  거절은 4xx + { "error": "사용자에게 그대로 보여 줄 문구" } (예: 409 "정원이 찼어요").
// ─────────────────────────────────────────────────────────────────────────

internal const val MAX_GROUPS_DEFAULT = 5
internal const val MAX_MEMBERS_DEFAULT = 10
internal val VOTE_THRESHOLDS = listOf(60, 70, 80, 90, 100)

// 초대 랜딩 페이지 — 5주차에 서버가 /invite/{코드} 로 띄울 예정. 그때 "$SERVER_BASE_URL/invite" 로 바꾸면 됨.
// 비어 있으면 공유 문구에 링크 없이 코드만 넣는다.
// 초대 랜딩 페이지 — GET /invite/{code} (토큰 없이 열림, 2단계 10/4 배포). 공유 문구에 "$INVITE_LANDING_URL/{code}" 로 넣는다.
internal const val INVITE_LANDING_URL = "$SERVER_BASE_URL/invite"

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
// isFull: 정원이 찼는지 (GET /invites/{code} 미리보기). 미리보기는 이름·설명·인원만 오고 한도·찬성률은 없을 수 있다.
internal data class InviteInfo(val code: String, val expiresAt: Long?, val group: GroupSummary, val isFull: Boolean = false)

// 성공이면 value, 실패면 사용자에게 보여 줄 error 문구.
internal data class GroupResult<T>(val value: T?, val error: String?)

// 서버가 이유(error)를 주면 그대로, 아니면 기본 문구 + HTTP 코드 (원인을 화면에서 바로 알 수 있게)
private fun <T> fail(res: ApiResult, fallback: String): GroupResult<T> =
    GroupResult(null, res.error ?: if (res.code == 0) "네트워크 연결을 확인해 주세요" else "$fallback (HTTP ${res.code})")

// 응답에서 그룹 ID 꺼내기 — 서버가 groupId / id / group.groupId / group.id 중 어떤 이름을 쓰든 받는다
private fun JSONObject.groupIdOrEmpty(): String {
    optString("groupId").takeIf { it.isNotEmpty() }?.let { return it }
    optString("id").takeIf { it.isNotEmpty() }?.let { return it }
    optJSONObject("group")?.let { g ->
        g.optString("groupId").takeIf { it.isNotEmpty() }?.let { return it }
        g.optString("id").takeIf { it.isNotEmpty() }?.let { return it }
    }
    return ""
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null

private fun parseSummary(raw: JSONObject): GroupSummary {
    // 상세 응답이 { group: {...}, members: [...] } 처럼 감싸져 오면 안쪽을 읽는다
    val o = raw.optJSONObject("group") ?: raw
    // 한도: goal { dailyLimit, hourlyLimit } 우선, 없으면 최상위 dailyLimit/hourlyLimit
    val goal = o.optJSONObject("goal") ?: o.optJSONObject("limits")
    return GroupSummary(
        groupId = o.groupIdOrEmpty(),
        name = o.optString("name"),
        description = o.optString("description"),
        dailyLimit = goal?.optInt("dailyLimit", 0)?.takeIf { it > 0 } ?: o.optInt("dailyLimit", 0),
        hourlyLimit = goal?.optInt("hourlyLimit", 0)?.takeIf { it > 0 } ?: o.optInt("hourlyLimit", 0),
        // 서버는 approvalRate 로 내려줌. 옛 이름(voteThreshold)도 혹시 몰라 같이 읽음
        voteThreshold = if (o.has("approvalRate")) o.optInt("approvalRate", 0) else o.optInt("voteThreshold", 0),
        memberCount = o.optInt("memberCount", 0).takeIf { it > 0 }
            ?: raw.optJSONArray("members")?.length() ?: o.optJSONArray("memberIds")?.length() ?: 0,
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
            // 서버가 nickname / name / displayName 중 무엇으로 주든 받는다
            nickname = m.optString("nickname").ifBlank { m.optString("name") }.ifBlank { m.optString("displayName") }
                .ifBlank { "이름 없음" },
            todayCount = m.optInt("todayCount", 0),
            lastHourCount = m.optInt("lastHourCount", 0),
            lastSeenAt = m.optLongOrNull("lastSeenAt"),
            lastScrollAt = m.optLongOrNull("lastScrollAt")
        )
    }
}

internal suspend fun fetchGroups(): GroupResult<GroupList> {
    val res = authedRequest("GET", "/groups")
    val json = res.json
    if (!res.ok || json == null) return fail(res, "그룹 목록을 불러오지 못했어요")
    val arr = json.optJSONArray("groups")
    val groups = if (arr == null) emptyList() else
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseSummary) }
    return GroupResult(GroupList(groups, json.optInt("maxGroups", MAX_GROUPS_DEFAULT)), null)
}

// 성공 시 새 그룹의 groupId.
internal suspend fun createGroup(
    name: String, description: String, dailyLimit: Int, hourlyLimit: Int, voteThreshold: Int,
    nickname: String = ""
): GroupResult<String> {
    val body = JSONObject()
        .put("name", name)
        .put("nickname", nickname)   // 만든 사람의 그룹 표시 이름 (설정 > 닉네임)
        .put("description", description)
        .put("goal", JSONObject().put("dailyLimit", dailyLimit).put("hourlyLimit", hourlyLimit))
        .put("approvalRate", voteThreshold)   // 서버 칸 이름은 approvalRate (1~100 정수)
    val res = authedRequest("POST", "/groups", body)
    val gid = res.json?.groupIdOrEmpty().orEmpty()
    if (res.ok && gid.isEmpty()) android.util.Log.w("GroupApi", "POST /groups 성공인데 groupId 없음 — 응답: ${res.json}")
    return if (res.ok && gid.isNotEmpty()) GroupResult(gid, null)
           else fail(res, if (res.ok) "응답에 그룹 ID가 없어요" else "그룹을 만들지 못했어요")
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
    // 스펙 §5: POST /invites (그룹은 body 의 groupId 로). 2단계 배포 전까지는 404.
    val res = authedRequest("POST", "/invites", JSONObject().put("groupId", groupId))
    val code = res.json?.optString("code").orEmpty()
    return if (res.ok && code.isNotEmpty()) GroupResult(code, null) else fail(res, "초대 코드를 만들지 못했어요")
}

internal suspend fun fetchInvite(code: String): GroupResult<InviteInfo> {
    val res = authedRequest("GET", "/invites/$code")
    val json = res.json
    if (!res.ok || json == null) return fail(res, "초대 코드를 확인해 주세요")
    val g = parseSummary(json)
    val full = json.optBoolean("isFull", false) || (json.optJSONObject("group")?.optBoolean("isFull", false) ?: false) ||
        (g.maxMembers > 0 && g.memberCount >= g.maxMembers)
    return GroupResult(InviteInfo(code, json.optLongOrNull("expiresAt"), g, full), null)
}

// 성공 시 가입한 그룹의 groupId.
internal suspend fun joinGroup(code: String, nickname: String = ""): GroupResult<String> {
    val res = authedRequest("POST", "/groups/join", JSONObject().put("code", code).put("nickname", nickname))
    val gid = res.json?.groupIdOrEmpty().orEmpty()
    return if (res.ok && gid.isNotEmpty()) GroupResult(gid, null) else fail(res, "그룹에 참여하지 못했어요")
}

internal suspend fun leaveGroup(groupId: String): GroupResult<Unit> {
    val res = authedRequest("DELETE", "/groups/$groupId/members/me")
    return if (res.ok) GroupResult(Unit, null) else fail(res, "탈퇴하지 못했어요")
}
