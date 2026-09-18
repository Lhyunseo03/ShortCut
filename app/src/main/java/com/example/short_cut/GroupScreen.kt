package com.example.short_cut

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.short_cut.db.AppDatabase
import com.example.short_cut.db.GroupLimit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────
// 그룹 탭 — 그룹 목록 / 그룹 만들기 / 그룹 상세
// (한도 변경 투표 · 넛지 · 구성원 상세는 아직 없음)
// ─────────────────────────────────────────────────────────────────────────

private val Ink = Color(0xFF1A1A1A)
private val SubInk = Color(0xFF888888)
private val OverRed = Color(0xFFC62828)
private val OverRedBg = Color(0xFFFFEBEE)
private val OkGreen = Color(0xFF2E7D32)
private val OkGreenBg = Color(0xFFE8F5E9)
private val CardBg = Color(0xFFF7F7F7)

private sealed interface GroupNav {
    data object List : GroupNav
    data object Create : GroupNav
    data class Detail(val groupId: String) : GroupNav
}

@Composable
internal fun GroupTabContent() {
    var nav by remember { mutableStateOf<GroupNav>(GroupNav.List) }

    // 하위 화면에서 시스템 뒤로가기 → 목록으로
    BackHandler(enabled = nav != GroupNav.List) { nav = GroupNav.List }

    when (val n = nav) {
        GroupNav.List -> GroupListScreen(
            onCreate = { nav = GroupNav.Create },
            onOpen = { nav = GroupNav.Detail(it) }
        )
        GroupNav.Create -> GroupCreateScreen(
            onBack = { nav = GroupNav.List },
            onCreated = { nav = GroupNav.Detail(it) }
        )
        is GroupNav.Detail -> GroupDetailScreen(
            groupId = n.groupId,
            onBack = { nav = GroupNav.List }
        )
    }
}

// 상단 뒤로가기 + 타이틀 — 설정 하위 화면과 같은 모양
@Composable
private fun GroupTopBar(title: String, onBack: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(
            start = if (onBack != null) 8.dp else 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp
        )
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
        }
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, fontSize = 15.sp, color = SubInk)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("다시 시도") }
    }
}

// ── 1. 그룹 목록 ─────────────────────────────────────────────────────────
@Composable
private fun GroupListScreen(onCreate: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var maxGroups by remember { mutableIntStateOf(MAX_GROUPS_DEFAULT) }
    var localToday by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showJoin by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        // 서버가 myTodayCount 를 안 주면 이 기기의 오늘 카운트로 대체
        localToday = com.example.short_cut.db.ScrollCountRepository.get(context).dailyCount(startOfDayMs())

        val res = fetchGroups()
        val list = res.value
        if (list != null) {
            groups = list.groups
            maxGroups = list.maxGroups
            // 그룹 한도 캐시 갱신 — 접근성 서비스가 쇼츠 시청 중 "그룹 한도 초과" 판단에 사용
            db.groupLimitDao().replaceAll(list.groups.map {
                GroupLimit(groupId = it.groupId, name = it.name, dailyLimit = it.dailyLimit, hourlyLimit = it.hourlyLimit)
            })
        } else {
            error = res.error
        }
        loading = false
    }

    val atMax = groups.size >= maxGroups

    Column(modifier = Modifier.fillMaxSize()) {
        GroupTopBar(title = "그룹", onBack = null)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                loading -> StatsLoadingSpinner()
                error != null -> ErrorWithRetry(error!!) { reloadKey++ }
                groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "가입한 그룹 없음", fontSize = 16.sp, color = SubInk)
                }
                else -> groups.forEach { g ->
                    GroupCard(group = g, myToday = g.myTodayCount ?: localToday, onClick = { onOpen(g.groupId) })
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // 하단 고정 버튼
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (atMax && !loading && error == null) {
                Text(
                    text = "그룹은 최대 ${maxGroups}개까지 참여할 수 있어요",
                    fontSize = 13.sp,
                    color = SubInk,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCreate,
                    enabled = !loading && !atMax,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text("+ 그룹 만들기", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { showJoin = true },
                    enabled = !loading && !atMax,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("코드로 참여", fontWeight = FontWeight.Bold, color = Ink) }
            }
        }
    }

    if (showJoin) {
        JoinByCodeDialog(
            onDismiss = { showJoin = false },
            onJoined = { gid ->
                showJoin = false
                onOpen(gid)
            }
        )
    }
}

@Composable
private fun GroupCard(group: GroupSummary, myToday: Int, onClick: () -> Unit) {
    val over = group.dailyLimit > 0 && myToday >= group.dailyLimit
    val progress = if (group.dailyLimit > 0) (myToday.toFloat() / group.dailyLimit).coerceIn(0f, 1f) else 0f
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = CardBg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${group.memberCount}/${group.maxMembers}", fontSize = 14.sp, color = SubInk)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (over) OverRed else OkGreen,
                trackColor = Color(0xFFE0E0E0)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "오늘 ${myToday}회 / 그룹 일간 한도 ${group.dailyLimit}회",
                fontSize = 13.sp,
                color = if (over) OverRed else SubInk
            )
        }
    }
}

// 코드 입력 → GET /invites/{code} 로 그룹 정보 확인 → 참여(POST /groups/join)
@Composable
private fun JoinByCodeDialog(onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<InviteInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (invite == null) "코드로 참여" else "이 그룹에 참여할까요?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val info = invite
                if (info == null) {
                    OutlinedTextField(
                        value = code,
                        // 6자리 영문/숫자 대문자만
                        onValueChange = { v ->
                            code = v.uppercase().filter { it.isLetterOrDigit() }.take(6)
                            error = null
                        },
                        label = { Text("초대 코드 6자리") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )
                } else {
                    Text(info.group.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                    if (info.group.description.isNotBlank()) {
                        Text(info.group.description, fontSize = 14.sp, color = SubInk)
                    }
                    InfoRow("한도", "일 ${info.group.dailyLimit} / 시 ${info.group.hourlyLimit}")
                    InfoRow("찬성률", "${info.group.voteThreshold}%")
                    InfoRow("인원", "${info.group.memberCount}/${info.group.maxMembers}")
                }
                error?.let { Text(it, fontSize = 13.sp, color = OverRed) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        if (invite == null) {
                            val res = fetchInvite(code)
                            invite = res.value
                            error = res.error
                        } else {
                            val res = joinGroup(code)
                            val gid = res.value
                            if (gid != null) onJoined(gid) else error = res.error
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "확인 중…" else if (invite == null) "확인" else "참여") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } }
    )
}

// ── 2. 그룹 만들기 ───────────────────────────────────────────────────────
@Composable
private fun GroupCreateScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var daily by remember { mutableIntStateOf(200) }
    var hourly by remember { mutableIntStateOf(50) }
    var threshold by remember { mutableIntStateOf(70) }
    var thresholdOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canSubmit = !busy && name.isNotBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        GroupTopBar(title = "그룹 만들기", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("그룹 정보")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20); error = null },
                label = { Text("이름") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.replace("\n", " ").take(50) },
                label = { Text("소개 한 줄") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = Color(0xFFEEEEEE))

            // 개인 한도 설정 화면과 같은 스테퍼 (daily 100 단위 / hourly 10 단위)
            SectionTitle("초기 한도")
            LimitRow(label = "Daily Limit", value = daily, step = 100, minValue = 100, onChange = { daily = it })
            LimitRow(label = "Hourly Limit", value = hourly, step = 10, minValue = 10, onChange = { hourly = it })

            HorizontalDivider(color = Color(0xFFEEEEEE))

            SectionTitle("한도 변경 찬성률")
            Box {
                OutlinedButton(
                    onClick = { thresholdOpen = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${threshold}% 이상 찬성 시 통과", color = Ink, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Ink)
                }
                DropdownMenu(expanded = thresholdOpen, onDismissRequest = { thresholdOpen = false }) {
                    VOTE_THRESHOLDS.forEach { t ->
                        DropdownMenuItem(
                            text = { Text("${t}%") },
                            onClick = { threshold = t; thresholdOpen = false }
                        )
                    }
                }
            }
            Text(
                text = "그룹을 만든 뒤에는 한도를 투표로만 바꿀 수 있어요. 만든 사람도 다른 구성원과 권한이 같아요.",
                fontSize = 13.sp,
                color = SubInk
            )
            error?.let { Text(it, fontSize = 14.sp, color = OverRed) }
            Spacer(Modifier.height(8.dp))
        }

        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val res = createGroup(name.trim(), description.trim(), daily, hourly, threshold)
                        val gid = res.value
                        busy = false
                        if (gid != null) {
                            Toast.makeText(context, "그룹을 만들었어요", Toast.LENGTH_SHORT).show()
                            onCreated(gid)
                        } else {
                            error = res.error
                        }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) { Text(if (busy) "만드는 중…" else "만들기", fontWeight = FontWeight.Bold) }
        }
    }
}

// ── 4. 그룹 상세 ─────────────────────────────────────────────────────────
@Composable
private fun GroupDetailScreen(groupId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<GroupDetail?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var inviting by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(groupId, reloadKey) {
        loading = true
        error = null
        val res = fetchGroupDetail(groupId)
        detail = res.value
        error = res.error
        loading = false
        if (res.value == null) return@LaunchedEffect

        // 30초마다 카운트만 새로고침. 화면을 떠나면 LaunchedEffect 가 취소되며 같이 멈춘다.
        while (true) {
            delay(30_000)
            val fresh = fetchGroupStatus(groupId) ?: continue
            val byId = fresh.associateBy { it.userId }
            detail = detail?.let { d ->
                // status 응답엔 닉네임이 없을 수 있으므로 기존 닉네임 유지
                d.copy(members = d.members.map { m ->
                    byId[m.userId]?.copy(nickname = m.nickname) ?: m
                })
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GroupTopBar(title = detail?.summary?.name ?: "그룹", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val d = detail
            when {
                loading -> StatsLoadingSpinner()
                d == null -> ErrorWithRetry(error ?: "그룹 정보를 불러오지 못했어요") { reloadKey++ }
                else -> {
                    val g = d.summary
                    if (g.description.isNotBlank()) {
                        Text(g.description, fontSize = 15.sp, color = SubInk)
                    }
                    InfoRow("현재 한도", "일 ${g.dailyLimit} / 시 ${g.hourlyLimit}")
                    InfoRow("찬성률", "${g.voteThreshold}%")
                    InfoRow("인원", "${g.memberCount}/${g.maxMembers}")

                    HorizontalDivider(color = Color(0xFFEEEEEE))
                    SectionTitle("오늘 순위")
                    // 오늘 스크롤 많은 순
                    d.members.sortedByDescending { it.todayCount }.forEachIndexed { i, m ->
                        MemberRow(rank = i + 1, member = m, group = g)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        if (detail != null) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        inviting = true
                        scope.launch {
                            val res = createInvite(groupId)
                            inviting = false
                            val code = res.value
                            if (code != null) shareInvite(context, detail?.summary?.name.orEmpty(), code)
                            else Toast.makeText(context, res.error, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !inviting && !leaving,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text(if (inviting) "코드 만드는 중…" else "초대", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { showLeave = true },
                    enabled = !inviting && !leaving,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("탈퇴", fontWeight = FontWeight.Bold, color = OverRed) }
            }
        }
    }

    if (showLeave) {
        AlertDialog(
            onDismissRequest = { if (!leaving) showLeave = false },
            title = { Text("그룹에서 탈퇴할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("탈퇴하면 다시 초대를 받아야 들어올 수 있어요. 마지막 구성원이 탈퇴하면 그룹이 사라져요.") },
            confirmButton = {
                TextButton(
                    enabled = !leaving,
                    onClick = {
                        leaving = true
                        scope.launch {
                            val res = leaveGroup(groupId)
                            leaving = false
                            showLeave = false
                            if (res.error == null) onBack()   // 목록이 다시 로드되며 group_limit 캐시도 갱신됨
                            else Toast.makeText(context, res.error, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(if (leaving) "탈퇴 중…" else "탈퇴", color = OverRed) }
            },
            dismissButton = { TextButton(enabled = !leaving, onClick = { showLeave = false }) { Text("취소") } }
        )
    }
}

// 순위표 한 줄 — 배경: 오늘 카운트 ≥ 그룹 일간 한도면 빨강, 아니면 초록.
// 벨: 최근 1시간 카운트 ≥ 그룹 시간당 한도일 때만 (지금 몰아 보는 중인 사람).
@Composable
private fun MemberRow(rank: Int, member: GroupMember, group: GroupSummary) {
    val overDaily = group.dailyLimit > 0 && member.todayCount >= group.dailyLimit
    val overHourly = group.hourlyLimit > 0 && member.lastHourCount >= group.hourlyLimit
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (overDaily) OverRedBg else OkGreenBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$rank", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SubInk, modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.nickname,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "접속 ${agoText(member.lastSeenAt)} · 스크롤 ${agoText(member.lastScrollAt)}",
                    fontSize = 12.sp,
                    color = SubInk
                )
            }
            if (overHourly) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = "시간당 한도 초과 중",
                    tint = OverRed,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "${member.todayCount}회",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (overDaily) OverRed else OkGreen
            )
        }
    }
}

private fun agoText(ms: Long?): String {
    if (ms == null) return "기록 없음"
    val diffMin = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
    return when {
        diffMin < 1 -> "방금"
        diffMin < 60 -> "${diffMin}분 전"
        diffMin < 24 * 60 -> "${diffMin / 60}시간 전"
        else -> "${diffMin / (24 * 60)}일 전"
    }
}

// 안드로이드 공유 시트로 초대 문구 보내기. 랜딩 주소가 아직 없으면 코드만 넣는다.
private fun shareInvite(context: Context, groupName: String, code: String) {
    val link = if (INVITE_LANDING_URL.isNotBlank()) "\n$INVITE_LANDING_URL/join?code=$code" else ""
    val text = "Short-Cut 그룹 '$groupName'에 초대해요.\n초대 코드: $code (24시간 동안 유효)$link"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "초대 보내기"))
}
