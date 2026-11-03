package com.example.short_cut

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── 그룹원 일간 통계 (G6) ─────────────────────────────────────────────────
// 개인 통계 탭의 일간(달력 히트맵 + 선택일 시간대 누적 그래프)과 같은 모양. 한도만 개인 한도 대신 **그룹 한도**.
// 데이터는 서버 그룹 경로 (POINTS_API.md §10, 11/1 요청):
//   GET /groups/{gid}/members/{uid}/monthly?date=YYYY-MM  → 달력 (그날 그룹 일간 한도 포함)
//   GET /groups/{gid}/members/{uid}/daily?date=YYYY-MM-DD → 시간대 그래프 + 그룹 팝업(alert) 시각
// 서버에 아직 없으면(404) 안내만 띄우고, 내 화면이면 그동안 /stats 개인 집계로 채운다 (한도는 그룹 한도 그대로).
// 개인 화면과 달리 로컬 Room 은 안 본다 — 다른 사람 기록은 서버만 알고, 내 것도 순위표와 같은 서버 기준으로 맞춘다.

private val Ink = Color(0xFF1A1A1A)
private val SubInk = Color(0xFF888888)

@Composable
internal fun MemberStatsScreen(group: GroupSummary, row: GroupMember, isMe: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val myUid = remember { context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null) }
    val dayFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    // 달력이 보여주는 달 (0=이번 달, -1=저번 달 …)
    var monthOffset by remember { mutableIntStateOf(0) }
    val monthCal = remember(monthOffset) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val year = monthCal.get(Calendar.YEAR)
    val month = monthCal.get(Calendar.MONTH)
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = (monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // MON=0..SUN=6
    val monthKey = remember(monthOffset) { SimpleDateFormat("yyyy-MM", Locale.US).format(monthCal.time) }
    val todayMidnight = remember { startOfDayMs(0) }
    val todayKey = remember { dayFmt.format(Date(todayMidnight)) }

    // 달력 데이터 — yyyy-MM-dd → 스크롤 수 / 그날 적용됐던 그룹 일간 한도(서버가 주면)
    var dayCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var dayLimits by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var monthLoading by remember { mutableStateOf(true) }
    var serverNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(monthOffset) {
        monthLoading = true
        val keys = (1..daysInMonth).map { d ->
            Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis; set(Calendar.DAY_OF_MONTH, d) }.let { dayFmt.format(it.time) }
        }.filter { it <= todayKey }   // 미래는 안 물어봄
        val fromGroup = fetchMemberMonthDays(group.groupId, row.userId, monthKey)
        when {
            fromGroup != null -> {
                val byDate = fromGroup.associateBy { it.date }
                dayCounts = keys.associateWith { byDate[it]?.count ?: 0 }
                dayLimits = fromGroup.mapNotNull { d -> d.dailyLimit?.let { d.date to it } }.toMap()
                serverNote = null
            }
            isMe && myUid != null -> {
                // 그룹 경로가 아직 없으면 내 개인 집계로 (한도는 그룹 한도 그대로)
                val server = fetchDailyStatsForDays(myUid, keys)
                dayCounts = keys.associateWith { server[it]?.totalScroll ?: 0 }
                dayLimits = emptyMap()
                serverNote = "그룹별 집계가 아직 없어 내 통계로 보여 줘요"
            }
            else -> {
                dayCounts = keys.associateWith { 0 }
                dayLimits = emptyMap()
                serverNote = "구성원별 일간 통계는 서버 작업이 끝나면 채워져요"
            }
        }
        // 오늘은 순위표에서 넘겨받은 숫자보다 작게 보이지 않게
        if (monthOffset == 0) dayCounts = dayCounts + (todayKey to maxOf(dayCounts[todayKey] ?: 0, row.todayCount))
        monthLoading = false
    }

    // 선택일 — 기본 오늘. 달을 옮기면 그 달 안으로
    var selectedDayMs by remember { mutableStateOf(todayMidnight) }
    LaunchedEffect(monthOffset) {
        val c = Calendar.getInstance().apply { timeInMillis = selectedDayMs }
        if (c.get(Calendar.YEAR) != year || c.get(Calendar.MONTH) != month) {
            selectedDayMs = if (monthOffset == 0) todayMidnight else monthCal.timeInMillis
        }
    }
    val selectedDateStr = remember(selectedDayMs) { dayFmt.format(Date(selectedDayMs)) }
    val selectedLabel = remember(selectedDayMs) { SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREAN).format(Date(selectedDayMs)) }

    // 선택일 상세 — 그룹 경로 → (내 화면이면) 개인 /stats → 없으면 빈 그래프
    var detail by remember { mutableStateOf<DailyStatsRemote?>(null) }
    var detailLoading by remember { mutableStateOf(true) }
    LaunchedEffect(selectedDayMs) {
        detailLoading = true
        detail = fetchMemberDailyStats(group.groupId, row.userId, selectedDateStr)
            ?: if (isMe && myUid != null) fetchDailyStats(myUid, selectedDateStr) else null
        detailLoading = false
    }
    // 그날의 그룹 한도 — 서버가 그날 값을 주면 그것, 아니면 지금 그룹 한도
    val dailyLimit = detail?.dailyLimit?.takeIf { it > 0 } ?: dayLimits[selectedDateStr] ?: group.dailyLimit
    val hourlyLimit = detail?.hourlyLimit ?: group.hourlyLimit
    val counts = detail?.hourlyCounts ?: IntArray(24)
    val total = maxOf(detail?.totalScroll ?: 0, dayCounts[selectedDateStr] ?: 0, counts.sum())
    // 그룹 팝업 시각 (서버 alerts). 개인 /stats 로 대체 중이면 개인 팝업 시각이라 그룹 한도와 안 맞을 수 있다 — 안내문으로 충분
    val hourlyTimes = detail?.violations?.filter { it.limitType == "hourly" }?.map { it.timestamp }?.sorted() ?: emptyList()
    val dailyTimes = detail?.violations?.filter { it.limitType == "daily" }?.map { it.timestamp }?.sorted() ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "${row.nickname} · 일간", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Text("그룹 한도 기준 (일 ${group.dailyLimit} / 시 ${group.hourlyLimit})", fontSize = 13.sp, color = SubInk,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            serverNote?.let { Box(modifier = Modifier.padding(bottom = 8.dp)) { Notice(it) } }

            // 월 네비 ← 2026.11 →
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { monthOffset -= 1 }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 달") }
                Text("${year}.%02d".format(month + 1), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink,
                    modifier = Modifier.padding(horizontal = 12.dp))
                IconButton(onClick = { if (monthOffset < 0) monthOffset += 1 }, enabled = monthOffset < 0) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "다음 달")
                }
            }

            // 요일 헤더
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEach { d ->
                    Text(d, modifier = Modifier.weight(1f).padding(vertical = 6.dp), textAlign = TextAlign.Center,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SubInk)
                }
            }

            // 달력 셀 — 색은 그날 그룹 일간 한도 기준 (개인 일간과 같은 goalColor)
            val countByDay = (1..daysInMonth).associateWith { d -> dayCounts[dayKeyOf(monthCal, d, dayFmt)] ?: 0 }
            val limitByDay = (1..daysInMonth).associateWith { d -> dayLimits[dayKeyOf(monthCal, d, dayFmt)] ?: group.dailyLimit }
            val maxCount = (countByDay.values.maxOrNull() ?: 0).coerceAtLeast(1)
            val maxOverage = (1..daysInMonth).maxOf { d -> (countByDay[d] ?: 0) - (limitByDay[d] ?: 0) }
            val totalRows = (firstDayOfWeek + daysInMonth + 6) / 7
            if (monthLoading) {
                StatsLoadingSpinner(modifier = Modifier.fillMaxWidth().height(200.dp))
            } else for (rowIdx in 0 until totalRows) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (col in 0 until 7) {
                        val dayNum = rowIdx * 7 + col - firstDayOfWeek + 1
                        if (dayNum !in 1..daysInMonth) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp))
                            continue
                        }
                        val cellMs = Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis; set(Calendar.DAY_OF_MONTH, dayNum) }.timeInMillis
                        val isFuture = cellMs > todayMidnight
                        val isSelected = cellMs == selectedDayMs
                        val count = countByDay[dayNum] ?: 0
                        val (bg, isDarkBg) =
                            if (isFuture) Color(0xFFFAFAFA) to false
                            else goalColor(count, limitByDay[dayNum] ?: 0, maxCount, maxOverage)
                        var cellMod = Modifier.weight(1f).aspectRatio(1f).padding(2.dp).background(bg, RoundedCornerShape(6.dp))
                        if (isSelected) cellMod = cellMod.border(2.dp, Ink, RoundedCornerShape(6.dp))
                        if (!isFuture) cellMod = cellMod.clickable { selectedDayMs = cellMs }
                        Box(modifier = cellMod, contentAlignment = Alignment.TopCenter) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 3.dp)) {
                                Text(dayNum.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp,
                                    color = when { isFuture -> Color(0xFFCCCCCC); isDarkBg -> Color.White; else -> Ink })
                                if (!isFuture) Text(count.toString(), fontSize = 10.sp, lineHeight = 11.sp,
                                    color = if (isDarkBg) Color.White else Color(0xFF666666))
                            }
                        }
                    }
                }
            }

            // 범례
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(14.dp).background(Color(0xFF66BB6A), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(4.dp)); Text("한도 이내", fontSize = 11.sp, color = SubInk)
                Spacer(Modifier.width(12.dp))
                listOf(0.1f, 0.55f, 1.0f).forEach { f ->
                    Box(modifier = Modifier.size(14.dp).padding(end = 2.dp)
                        .background(lerp(Color(0xFFEF9A9A), Color(0xFFB71C1C), 0.15f + 0.85f * f), RoundedCornerShape(4.dp)))
                }
                Spacer(Modifier.width(4.dp)); Text("초과 (많을수록 진함)", fontSize = 11.sp, color = SubInk)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Spacer(Modifier.height(16.dp))

            // ── 선택일 상세 ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                Text(selectedLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (detailLoading) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF4FC3F7))
                }
            }
            if (detailLoading) {
                StatsLoadingSpinner(modifier = Modifier.fillMaxWidth().height(240.dp))
            } else {
                Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                    DailyGoalProgressBox(
                        total = total, dailyLimit = dailyLimit, exceeded = dailyLimit > 0 && total > dailyLimit, source = "server",
                        dailyExceedTime = dailyTimes.firstOrNull(), dailyExtraPopupTimes = dailyTimes.drop(1)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                    HourlyGoalBox(hourlyLimit = hourlyLimit, hourlyExceedTime = hourlyTimes.firstOrNull(), hourlyExtraPopupTimes = hourlyTimes.drop(1))
                }
                Spacer(Modifier.height(12.dp))
                detail?.byPlatform?.let { byApp ->
                    Text("앱별 스크롤 비율", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) { AppShareDonut(byApp) }
                }
                CumulativeDayChart(counts = counts, total = total, dailyLimit = dailyLimit)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun dayKeyOf(monthCal: Calendar, day: Int, fmt: SimpleDateFormat): String =
    Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis; set(Calendar.DAY_OF_MONTH, day) }.let { fmt.format(it.time) }
