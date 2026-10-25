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
    val myTodayCount: Int?,     // GET /groups 에서만 옴. 없으면 호출부가 로컬 카운트로 대체
    // 투표 통과 후 다음 주 월요일 적용 대기 한도 (서버 6-1: pendingGoal { dailyLimit, hourlyLimit, effectiveAt }). 없으면 null
    val pendingDaily: Int? = null,
    val pendingHourly: Int? = null,
    val pendingEffectiveAt: Long? = null
)

internal data class GroupMember(
    val userId: String,
    val nickname: String,
    val todayCount: Int,
    val lastHourCount: Int,
    val lastSeenAt: Long?,
    val lastScrollAt: Long?,
    val loggedOut: Boolean = false,       // 기기가 전부 로그아웃 (G10)
    val permissionsOk: Boolean? = null    // 로그인 기기 중 권한 꺼진 게 있으면 false (G11). 모르면 null
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

// org.json 의 optString 은 JSON null 을 "null" 글자로 주므로, 문자열은 전부 이걸로 읽는다
private fun JSONObject.str(key: String): String =
    if (has(key) && !isNull(key)) optString(key) else ""

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null

private fun parseSummary(raw: JSONObject): GroupSummary {
    // 상세 응답이 { group: {...}, members: [...] } 처럼 감싸져 오면 안쪽을 읽는다
    val o = raw.optJSONObject("group") ?: raw
    // 한도: goal { dailyLimit, hourlyLimit } 우선, 없으면 최상위 dailyLimit/hourlyLimit
    val goal = o.optJSONObject("goal") ?: o.optJSONObject("limits")
    return GroupSummary(
        groupId = o.groupIdOrEmpty(),
        name = o.str("name"),
        description = o.str("description"),
        dailyLimit = goal?.optInt("dailyLimit", 0)?.takeIf { it > 0 } ?: o.optInt("dailyLimit", 0),
        hourlyLimit = goal?.optInt("hourlyLimit", 0)?.takeIf { it > 0 } ?: o.optInt("hourlyLimit", 0),
        // 서버는 approvalRate 로 내려줌. 옛 이름(voteThreshold)도 혹시 몰라 같이 읽음
        voteThreshold = if (o.has("approvalRate")) o.optInt("approvalRate", 0) else o.optInt("voteThreshold", 0),
        memberCount = o.optInt("memberCount", 0).takeIf { it > 0 }
            ?: raw.optJSONArray("members")?.length() ?: o.optJSONArray("memberIds")?.length() ?: 0,
        maxMembers = o.optInt("maxMembers", MAX_MEMBERS_DEFAULT),
        myTodayCount = if (o.has("myTodayCount")) o.optInt("myTodayCount") else null,
        pendingDaily = o.optJSONObject("pendingGoal")?.optInt("dailyLimit", 0)?.takeIf { it > 0 },
        pendingHourly = o.optJSONObject("pendingGoal")?.optInt("hourlyLimit", 0)?.takeIf { it > 0 },
        pendingEffectiveAt = o.optJSONObject("pendingGoal")?.optLongOrNull("effectiveAt")
    )
}

private fun parseMembers(o: JSONObject): List<GroupMember> {
    val arr = o.optJSONArray("members") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val m = arr.optJSONObject(i) ?: return@mapNotNull null
        GroupMember(
            userId = m.optString("userId"),
            // 서버가 nickname / name / displayName 중 무엇으로 주든 받는다
            nickname = m.str("nickname").ifBlank { m.str("name") }.ifBlank { m.str("displayName") }
                .ifBlank { "이름 없음" },
            todayCount = m.optInt("todayCount", 0),
            lastHourCount = m.optInt("lastHourCount", 0),
            lastSeenAt = m.optLongOrNull("lastSeenAt"),
            lastScrollAt = m.optLongOrNull("lastScrollAt"),
            loggedOut = m.optBoolean("loggedOut", false),
            permissionsOk = if (m.has("permissionsOk") && !m.isNull("permissionsOk")) m.optBoolean("permissionsOk") else null
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

// 가입 결과 — alreadyMember: 서버가 200(이미 멤버)을 준 경우. 새로 가입하면 201.
internal data class JoinOutcome(val groupId: String, val alreadyMember: Boolean)

internal suspend fun joinGroup(code: String, nickname: String = ""): GroupResult<JoinOutcome> {
    val res = authedRequest("POST", "/groups/join", JSONObject().put("code", code).put("nickname", nickname))
    val gid = res.json?.groupIdOrEmpty().orEmpty()
    return if (res.ok && gid.isNotEmpty()) GroupResult(JoinOutcome(gid, alreadyMember = res.code == 200), null)
           else fail(res, "그룹에 참여하지 못했어요")
}

internal suspend fun leaveGroup(groupId: String): GroupResult<Unit> {
    val res = authedRequest("DELETE", "/groups/$groupId/members/me")
    return if (res.ok) GroupResult(Unit, null) else fail(res, "탈퇴하지 못했어요")
}


// ─────────────────────────────────────────────────────────────────────────
// 7~9주차 — 앱이 가정한 계약 (서버 미구현 10/22 기준. 서버와 맞추면서 파서만 고치면 됨)
//
//  POST /groups/{gid}/alerts   ← { type: "daily"|"hourly", count }            → { status: "ok" }
//       (G2·G8: 그룹 한도 초과 알림. 서버가 나머지 구성원에게 FCM, 같은 날/같은 시(hour)엔 중복 발송 없음)
//  POST /groups/{gid}/nudge    ← { targetId }                                 → 200 / 429 "잠시 후에 다시"
//       (G7: 보낸 사람→받는 사람 쌍으로 10분에 1번)
//  GET  /groups/{gid}/members/{uid}                                           (G5·G6·G11)
//       → { userId, displayName, todayCount, lastHourCount, todayPopups,
//           devices: [ { deviceName, lastSeenAt, lastScrollAt, loggedOutAt, permissionsOk, permissionsOffSince } ],
//           last7Days: [ { date: "yyyy-MM-dd", count } ] }
//  GET  /groups/{gid}/proposal                                                 (G9: 제안 버튼 상태 + 열린 제안)
//       → { state: "none"|"open"|"passed"|"used",
//           proposal: { proposalId, proposedBy, proposedByName, dailyLimit, hourlyLimit, createdAt, closesAt,
//                       yes, no, electorate, required, myVote: "yes"|"no"|null, canVote } | null,
//           lockedUntil: ms|null, pendingGoal: { dailyLimit, hourlyLimit } | null }
//  POST /groups/{gid}/proposals ← { dailyLimit, hourlyLimit }                 → 201 { proposalId } / 409 { error }
//  POST /proposals/{id}/vote    ← { vote: "yes"|"no" }                        → { status: "ok" } / 409 { error }
//
//  FCM data message (서버 → 앱): { type, groupId, groupName, title, body }
//    type = GROUP_ALERT(한도 초과) | NUDGE | PROPOSAL(제안 등록) | VOTE_RESULT(통과/부결) | LIMIT_APPLIED(월요일 적용)
//         | MEMBER_JOINED | MEMBER_LEFT | MEMBER_LOGOUT
// ─────────────────────────────────────────────────────────────────────────

internal data class MemberDevice(
    val deviceName: String,
    val lastSeenAt: Long?,
    val lastScrollAt: Long?,
    val loggedOutAt: Long?,
    val permissionsOk: Boolean?,
    val permissionsOffSince: Long?
)

internal data class MemberDaily(val date: String, val count: Int)

internal data class MemberDetail(
    val userId: String,
    val nickname: String,
    val todayCount: Int,
    val lastHourCount: Int,
    val todayPopups: Int,
    val devices: List<MemberDevice>,
    val last7Days: List<MemberDaily>
)

// 제안 버튼 상태 (스펙 G9 표): NONE=제안 가능, OPEN=진행 중인 투표, PASSED=이번 주 이미 통과, USED=내가 이번 주 제안함
internal enum class ProposalState { NONE, OPEN, PASSED, USED }

internal data class Proposal(
    val proposalId: String,
    val proposedBy: String,
    val proposedByName: String,
    val dailyLimit: Int,
    val hourlyLimit: Int,
    val createdAt: Long,
    val closesAt: Long,
    val yes: Int,
    val no: Int,
    val electorate: Int,
    val required: Int,
    val myVote: String?,      // "yes" / "no" / null
    val canVote: Boolean      // 투표권자(제안 시점 구성원)인지
)

internal data class ProposalStatus(
    val state: ProposalState,
    val proposal: Proposal?,
    val lockedUntil: Long?,
    val pendingDaily: Int?,
    val pendingHourly: Int?
)

// 그룹 한도 초과 알림 (G2·G8). 서버 미구현이면 404 — 실패해도 앱 동작엔 영향 없음.
internal suspend fun postGroupAlert(groupId: String, type: String, count: Int): Boolean {
    val res = authedRequest("POST", "/groups/$groupId/alerts", JSONObject().put("type", type).put("count", count))
    return res.ok
}

// 넛지 (G7). 429 면 서버가 준 "잠시 후에" 문구를 그대로.
internal suspend fun sendNudge(groupId: String, targetId: String): GroupResult<Unit> {
    val res = authedRequest("POST", "/groups/$groupId/nudge", JSONObject().put("targetId", targetId))
    return if (res.ok) GroupResult(Unit, null) else fail(res, "넛지를 보내지 못했어요")
}

internal suspend fun fetchMemberDetail(groupId: String, userId: String): GroupResult<MemberDetail> {
    val res = authedRequest("GET", "/groups/$groupId/members/$userId")
    val json = res.json
    if (!res.ok || json == null) return fail(res, "구성원 정보를 불러오지 못했어요")
    val o = json.optJSONObject("member") ?: json
    val devices = o.optJSONArray("devices")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            MemberDevice(
                deviceName = d.optString("deviceName").ifBlank { "기기" },
                lastSeenAt = d.optLongOrNull("lastSeenAt"),
                lastScrollAt = d.optLongOrNull("lastScrollAt"),
                loggedOutAt = d.optLongOrNull("loggedOutAt"),
                permissionsOk = if (d.has("permissionsOk") && !d.isNull("permissionsOk")) d.optBoolean("permissionsOk") else null,
                permissionsOffSince = d.optLongOrNull("permissionsOffSince")
            )
        }
    } ?: emptyList()
    val days = o.optJSONArray("last7Days")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            MemberDaily(d.optString("date"), d.optInt("count", 0))
        }
    } ?: emptyList()
    return GroupResult(
        MemberDetail(
            userId = o.optString("userId", userId),
            nickname = o.str("nickname").ifBlank { o.str("displayName") }.ifBlank { "이름 없음" },
            todayCount = o.optInt("todayCount", 0),
            lastHourCount = o.optInt("lastHourCount", 0),
            todayPopups = o.optInt("todayPopups", 0),
            devices = devices,
            last7Days = days
        ), null
    )
}

private fun parseProposal(p: JSONObject?): Proposal? {
    if (p == null) return null
    val id = p.optString("proposalId").ifBlank { p.optString("id") }
    if (id.isBlank()) return null
    val goal = p.optJSONObject("goal")
    return Proposal(
        proposalId = id,
        proposedBy = p.optString("proposedBy"),
        proposedByName = p.str("proposedByName").ifBlank { "구성원" },
        dailyLimit = goal?.optInt("dailyLimit", 0)?.takeIf { it > 0 } ?: p.optInt("dailyLimit", 0),
        hourlyLimit = goal?.optInt("hourlyLimit", 0)?.takeIf { it > 0 } ?: p.optInt("hourlyLimit", 0),
        createdAt = p.optLong("createdAt", 0L),
        closesAt = p.optLong("closesAt", 0L),
        yes = p.optInt("yes", 0),
        no = p.optInt("no", 0),
        electorate = p.optInt("electorate", 0),
        required = p.optInt("required", 0),
        myVote = p.optString("myVote").takeIf { it == "yes" || it == "no" },
        canVote = p.optBoolean("canVote", true)
    )
}

// 제안 버튼 상태 + 열린 제안. 서버 미구현(404)이면 "제안 가능" 으로 보여 주고, 제안 시도 때 서버 문구가 뜬다.
internal suspend fun fetchProposalStatus(groupId: String): GroupResult<ProposalStatus> {
    val res = authedRequest("GET", "/groups/$groupId/proposal")
    if (res.code == 404) return GroupResult(ProposalStatus(ProposalState.NONE, null, null, null, null), null)
    val json = res.json
    if (!res.ok || json == null) return fail(res, "투표 상태를 불러오지 못했어요")
    val state = when (json.optString("state")) {
        "open" -> ProposalState.OPEN
        "passed" -> ProposalState.PASSED
        "used" -> ProposalState.USED
        else -> ProposalState.NONE
    }
    val pending = json.optJSONObject("pendingGoal")
    return GroupResult(
        ProposalStatus(
            state = state,
            proposal = parseProposal(json.optJSONObject("proposal")),
            lockedUntil = json.optLongOrNull("lockedUntil"),
            pendingDaily = pending?.optInt("dailyLimit", 0)?.takeIf { it > 0 },
            pendingHourly = pending?.optInt("hourlyLimit", 0)?.takeIf { it > 0 }
        ), null
    )
}

internal suspend fun createProposal(groupId: String, dailyLimit: Int, hourlyLimit: Int): GroupResult<String> {
    val body = JSONObject().put("dailyLimit", dailyLimit).put("hourlyLimit", hourlyLimit)
        .put("goal", JSONObject().put("dailyLimit", dailyLimit).put("hourlyLimit", hourlyLimit))
    val res = authedRequest("POST", "/groups/$groupId/proposals", body)
    val id = res.json?.optString("proposalId").orEmpty().ifBlank { res.json?.optString("id").orEmpty() }
    return if (res.ok && id.isNotEmpty()) GroupResult(id, null) else fail(res, "제안을 등록하지 못했어요")
}

internal suspend fun castVote(proposalId: String, yes: Boolean): GroupResult<Unit> {
    val res = authedRequest("POST", "/proposals/$proposalId/vote", JSONObject().put("vote", if (yes) "yes" else "no"))
    return if (res.ok) GroupResult(Unit, null) else fail(res, "투표하지 못했어요")
}
