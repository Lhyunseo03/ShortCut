package com.example.short_cut

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────
// 그룹 탭 — 구성원 상세(G5·G11) / 그룹 기준 일간 통계(G6) / 한도 변경 제안·투표(G9)
// 서버 7~9주차 API 는 GroupApi.kt 상단 계약대로 가정. 서버가 없으면 404 → 화면은 뜨고 "서버 집계 대기" 로 표시.
// ─────────────────────────────────────────────────────────────────────────

private val Ink = Color(0xFF1A1A1A)
private val SubInk = Color(0xFF888888)
private val OverRed = Color(0xFFC62828)
private val OverRedBg = Color(0xFFFFEBEE)
private val OkGreen = Color(0xFF2E7D32)
private val OkGreenBg = Color(0xFFE8F5E9)
private val CardBg = Color(0xFFF7F7F7)

private val DATE_TIME_FMT = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
private fun fmtDateTime(ms: Long): String = DATE_TIME_FMT.format(Date(ms))
private fun fmtAgo(ms: Long?): String {
    if (ms == null) return "기록 없음"
    val diffMin = ((TrustedClock.now() - ms) / 60_000L).coerceAtLeast(0)
    return when {
        diffMin < 1 -> "방금"
        diffMin < 60 -> "${diffMin}분 전"
        diffMin < 24 * 60 -> "${diffMin / 60}시간 전"
        else -> fmtDateTime(ms)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Notice(text: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = CardBg) {
        Text(text, modifier = Modifier.padding(14.dp), fontSize = 13.sp, color = SubInk)
    }
}

// ── 구성원 상세 (G5 · G11) ─────────────────────────────────────────────────
// 순위표에서 넘겨받은 행(row)으로 먼저 그리고, GET /groups/{gid}/members/{uid} 가 오면 덮어쓴다.
// 내 행이면 기기 목록은 GET /devices(내 계정) 로, 카운트는 홈 탭과 같은 로컬 값으로 채운다.
@Composable
internal fun MemberDetailScreen(
    group: GroupSummary,
    row: GroupMember,
    isMe: Boolean,
    onBack: () -> Unit,
    onOpenStats: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<MemberDetail?>(null) }
    var serverNote by remember { mutableStateOf<String?>(null) }
    var nudging by remember { mutableStateOf(false) }

    LaunchedEffect(group.groupId, row.userId) {
        val res = fetchMemberDetail(group.groupId, row.userId)
        var d = res.value
        if (d == null) serverNote = "구성원별 집계는 서버 6주차 작업이라 아직 숫자가 비어 있을 수 있어요"
        if (isMe) {
            // 내 값은 로컬로 채운다 — 홈 탭과 같은 숫자
            val repo = com.example.short_cut.db.ScrollCountRepository.get(context)
            val today = accountTodayCount(context)
            val hour = repo.hourlyCount(TrustedClock.now())
            val myDevices = ServerDevicesSource().load(DeviceId.get(context))?.map {
                MemberDevice(
                    deviceName = it.deviceName + if (it.isThisDevice) " (이 기기)" else "",
                    lastSeenAt = it.lastSeenAt, lastScrollAt = null,
                    loggedOutAt = it.loggedOutAt, permissionsOk = it.permissionsOk, permissionsOffSince = null
                )
            } ?: emptyList()
            d = (d ?: MemberDetail(row.userId, row.nickname, 0, 0, 0, emptyList(), emptyList())).let {
                it.copy(
                    nickname = row.nickname,
                    todayCount = maxOf(it.todayCount, today),
                    lastHourCount = maxOf(it.lastHourCount, hour),
                    devices = if (it.devices.isEmpty()) myDevices else it.devices
                )
            }
        } else if (d == null) {
            d = MemberDetail(row.userId, row.nickname, row.todayCount, row.lastHourCount, 0, emptyList(), emptyList())
        }
        detail = d
    }

    val d = detail
    val overHourly = d != null && group.hourlyLimit > 0 && d.lastHourCount >= group.hourlyLimit
    val overDaily = d != null && group.dailyLimit > 0 && d.todayCount >= group.dailyLimit

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = row.nickname, onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (d == null) { StatsLoadingSpinner(); return@Column }

            // 오늘 요약
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = if (overDaily) OverRedBg else OkGreenBg) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("오늘", fontSize = 13.sp, color = SubInk)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${d.todayCount}회", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = if (overDaily) OverRed else OkGreen)
                        Spacer(Modifier.width(8.dp))
                        Text("/ 그룹 일간 한도 ${group.dailyLimit}회", fontSize = 13.sp, color = SubInk, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text("최근 1시간 ${d.lastHourCount}회 / 시간당 한도 ${group.hourlyLimit}회" +
                        if (overHourly) " · 지금 초과 중" else "", fontSize = 13.sp, color = if (overHourly) OverRed else SubInk)
                    Text("오늘 팝업 ${d.todayPopups}회", fontSize = 13.sp, color = SubInk)
                }
            }
            serverNote?.let { Notice(it) }

            // 기기별 한 줄 (G11)
            SectionTitle("기기")
            if (d.devices.isEmpty()) {
                Text("기기 정보 없음", fontSize = 14.sp, color = SubInk)
            } else d.devices.forEach { dev ->
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = CardBg) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(dev.deviceName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        val line = when {
                            dev.loggedOutAt != null -> "로그아웃 ${fmtDateTime(dev.loggedOutAt)}"
                            else -> "접속 ${fmtAgo(dev.lastSeenAt)}" + (dev.lastScrollAt?.let { " · 스크롤 ${fmtAgo(it)}" } ?: "")
                        }
                        Text(line, fontSize = 12.sp, color = SubInk)
                        if (dev.permissionsOk == false) {
                            Text("권한 꺼짐" + (dev.permissionsOffSince?.let { " (${fmtDateTime(it)}부터)" } ?: ""),
                                fontSize = 12.sp, color = OverRed, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFEEEEEE))
            OutlinedButton(onClick = onOpenStats, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("그룹 한도 기준 일간 통계 →", color = Ink, fontWeight = FontWeight.SemiBold)
            }
            if (!isMe && overHourly) {
                Button(
                    onClick = {
                        nudging = true
                        scope.launch {
                            val res = sendNudge(group.groupId, row.userId)
                            nudging = false
                            Toast.makeText(context, res.error ?: "넛지를 보냈어요", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !nudging,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OverRed)
                ) { Text(if (nudging) "보내는 중…" else "넛지 보내기 (지금 몰아 보는 중)", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── 그룹 한도 기준 일간 통계 (G6) ───────────────────────────────────────────
// 최근 7일. 구성원 상세 응답의 last7Days 를 쓰고, 내 것이면 서버 /stats 일간으로 채운다.
@Composable
internal fun MemberStatsScreen(group: GroupSummary, row: GroupMember, isMe: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var days by remember { mutableStateOf<List<MemberDaily>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(group.groupId, row.userId) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val keys = (6 downTo 0).map { fmt.format(Date(startOfDayMs(-it))) }
        val fromServer = fetchMemberDetail(group.groupId, row.userId).value?.last7Days?.takeIf { it.isNotEmpty() }
        days = when {
            fromServer != null -> keys.map { k -> MemberDaily(k, fromServer.firstOrNull { it.date == k }?.count ?: 0) }
            isMe -> {
                val uid = context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
                val server = if (uid != null) fetchDailyStatsForDays(uid, keys) else emptyMap()
                val local = com.example.short_cut.db.AppDatabase.getDatabase(context).scrollHistoryDao().countByDay().associate { it.day to it.count }
                keys.map { k -> MemberDaily(k, maxOf(server[k]?.totalScroll ?: 0, local[k] ?: 0)) }
            }
            else -> { note = "구성원별 일간 통계는 서버 6주차 작업이라 아직 비어 있어요"; keys.map { MemberDaily(it, 0) } }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "${row.nickname} · 일간", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("그룹 일간 한도 ${group.dailyLimit}회 기준 · 넘은 날은 빨강", fontSize = 13.sp, color = SubInk)
            note?.let { Notice(it) }
            val list = days
            if (list == null) { StatsLoadingSpinner(); return@Column }
            val maxCount = (list.maxOfOrNull { it.count } ?: 0).coerceAtLeast(group.dailyLimit).coerceAtLeast(1)
            val labelFmt = SimpleDateFormat("M/d (E)", Locale.KOREAN)
            val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            list.forEach { d ->
                val over = group.dailyLimit > 0 && d.count > group.dailyLimit
                val label = try { labelFmt.format(parse.parse(d.date)!!) } catch (_: Exception) { d.date }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.width(72.dp))
                    Box(modifier = Modifier.weight(1f).height(18.dp).background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = (d.count.toFloat() / maxCount).coerceIn(0f, 1f))
                                .height(18.dp)
                                .background(if (over) OverRed else OkGreen, RoundedCornerShape(4.dp))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${d.count}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (over) OverRed else Ink, modifier = Modifier.width(36.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── 제안 버튼 (그룹 상세 하단) — 스펙 G9 표의 네 상태 ─────────────────────────
@Composable
internal fun ProposalButton(status: ProposalStatus?, onPropose: () -> Unit, onOpenVote: () -> Unit) {
    val st = status
    val (label, hint, enabled, action) = when (st?.state) {
        ProposalState.OPEN -> {
            val left = st.proposal?.closesAt?.let { ((it - TrustedClock.now()) / 3_600_000L).coerceAtLeast(0) }
            Quad("투표 보기", "진행 중인 투표가 있어요" + (left?.let { " (${it}시간 남음)" } ?: ""), true, onOpenVote)
        }
        ProposalState.PASSED -> Quad(
            "한도 변경 제안",
            "다음 주 한도 투표가 이미 완료됐어요" +
                (if (st.pendingDaily != null) " (월요일부터 일 ${st.pendingDaily} / 시 ${st.pendingHourly ?: "-"})" else ""),
            false, {}
        )
        ProposalState.USED -> Quad("한도 변경 제안", "이번 주 제안을 이미 사용했어요", false, {})
        else -> Quad("한도 변경 제안", null, true, onPropose)
    }
    Column {
        hint?.let { Text(it, fontSize = 12.sp, color = SubInk, modifier = Modifier.padding(bottom = 6.dp)) }
        OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text(label, color = if (enabled) Ink else SubInk, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Quad(val label: String, val hint: String?, val enabled: Boolean, val action: () -> Unit)

// ── 제안 화면 (G9) ───────────────────────────────────────────────────────
@Composable
internal fun ProposeScreen(group: GroupSummary, onBack: () -> Unit, onProposed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daily by remember { mutableIntStateOf(snapToStep(group.dailyLimit, 100, 100)) }
    var hourly by remember { mutableIntStateOf(snapToStep(group.hourlyLimit, 10, 10)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val unchanged = daily == group.dailyLimit && hourly == group.hourlyLimit
    val hourlyTooHigh = hourly > daily

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "한도 변경 제안", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InfoRow("현재 한도", "일 ${group.dailyLimit} / 시 ${group.hourlyLimit}")
            HorizontalDivider(color = Color(0xFFEEEEEE))
            SectionTitle("새 한도")
            LimitRow(label = "Daily Limit", value = daily, step = 100, minValue = 100, onChange = { daily = it })
            LimitRow(label = "Hourly Limit", value = hourly, step = 10, minValue = 10, onChange = { hourly = it })
            if (hourlyTooHigh) Text("Hourly Limit 은 Daily Limit 보다 클 수 없어요", fontSize = 13.sp, color = OverRed)
            Text(
                "제안하면 24시간 동안 투표해요. 구성원의 ${group.voteThreshold}% 이상이 찬성하면 다음 주 월요일부터 적용돼요. " +
                    "제안한 사람은 자동으로 찬성이에요. 제안은 한 주에 한 번만 할 수 있어요.",
                fontSize = 13.sp, color = SubInk
            )
            error?.let { Text(it, fontSize = 14.sp, color = OverRed) }
        }
        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val res = createProposal(group.groupId, daily, hourly)
                        busy = false
                        if (res.value != null) {
                            Toast.makeText(context, "제안을 등록했어요. 24시간 동안 투표해요", Toast.LENGTH_SHORT).show()
                            onProposed()
                        } else error = res.error
                    }
                },
                enabled = !busy && !unchanged && !hourlyTooHigh,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) { Text(if (busy) "등록 중…" else "제안하기", fontWeight = FontWeight.Bold) }
        }
    }
}

// ── 투표 화면 (G9) ───────────────────────────────────────────────────────
@Composable
internal fun VoteScreen(group: GroupSummary, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ProposalStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(group.groupId, reloadKey) {
        while (true) {
            val res = fetchProposalStatus(group.groupId)
            status = res.value
            error = res.error
            delay(30_000)   // 투표 수는 30초마다 갱신
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "한도 변경 투표", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val st = status
            val p = st?.proposal
            when {
                st == null && error == null -> StatsLoadingSpinner()
                st == null -> Text(error ?: "", fontSize = 14.sp, color = OverRed)
                p == null -> Text("진행 중인 투표가 없어요", fontSize = 15.sp, color = SubInk, modifier = Modifier.padding(vertical = 24.dp))
                else -> {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = CardBg) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${p.proposedByName} 님의 제안", fontSize = 13.sp, color = SubInk)
                            Text("일 ${group.dailyLimit} / 시 ${group.hourlyLimit}  →  일 ${p.dailyLimit} / 시 ${p.hourlyLimit}",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                            val leftMin = ((p.closesAt - TrustedClock.now()) / 60_000L).coerceAtLeast(0)
                            Text(if (leftMin >= 60) "${leftMin / 60}시간 ${leftMin % 60}분 남음" else "${leftMin}분 남음", fontSize = 13.sp, color = SubInk)
                        }
                    }
                    InfoRow("찬성", "${p.yes}명")
                    InfoRow("반대", "${p.no}명")
                    InfoRow("아직 투표 안 함", "${(p.electorate - p.yes - p.no).coerceAtLeast(0)}명")
                    InfoRow("통과에 필요한 찬성", "${p.required}명 (투표권자 ${p.electorate}명의 ${group.voteThreshold}%)")
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                    when {
                        !p.canVote -> Notice("투표 중에 가입해서 이번 투표에는 참여할 수 없어요. 다음 투표부터 참여할 수 있어요.")
                        p.myVote != null -> Notice(if (p.myVote == "yes") "찬성에 투표했어요" else "반대에 투표했어요")
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = castVote(p.proposalId, true); busy = false
                                        Toast.makeText(context, r.error ?: "찬성했어요", Toast.LENGTH_SHORT).show()
                                        reloadKey++
                                    }
                                },
                                enabled = !busy, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OkGreen)
                            ) { Text("찬성", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = castVote(p.proposalId, false); busy = false
                                        Toast.makeText(context, r.error ?: "반대했어요", Toast.LENGTH_SHORT).show()
                                        reloadKey++
                                    }
                                },
                                enabled = !busy, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp)
                            ) { Text("반대", fontWeight = FontWeight.Bold, color = OverRed) }
                        }
                    }
                    Text("통과하면 다음 주 월요일 0시부터 적용돼요. 24시간이 지나거나 모두 투표하면 마감돼요.", fontSize = 12.sp, color = SubInk)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
