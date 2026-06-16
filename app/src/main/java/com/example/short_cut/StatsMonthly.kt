package com.example.short_cut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.short_cut.db.AppCount
import com.example.short_cut.db.AppDatabase
import com.example.short_cut.db.HourlyCount
import com.example.short_cut.db.UserLimit
import com.example.short_cut.services.ShortCutAccessibilityService
import com.example.short_cut.ui.theme.ShortCutTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ── 월간 인라인 펼침 영역 — 그리드 셀 클릭 시 그 자리 아래에 펼쳐짐 ──
// 기간 / 총 스크롤 / 일평균 / 피크일 / 목표 달성 일수 / 중단·무시 횟수 / 앱별 도넛 / 일별 막대.
// 서버 /monthly + 일별 /daily(서버 우선, 로컬 폴백). 도넛은 서버 byPlatform 우선, 없으면 로컬 Room.
@Composable
internal fun MonthInlineDetail(monthOffset: Int) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }

    // 표시 중인 달의 첫 자정 / 다음 달 첫 자정
    val monthCal = remember(monthOffset) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val monthStartMs = monthCal.timeInMillis
    val nextMonthStartMs = remember(monthOffset) {
        Calendar.getInstance().apply {
            timeInMillis = monthStartMs
            add(Calendar.MONTH, 1)
        }.timeInMillis
    }
    val year = monthCal.get(Calendar.YEAR)
    val month = monthCal.get(Calendar.MONTH) // 0..11
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val monthKey = remember(monthOffset) { SimpleDateFormat("yyyy-MM", Locale.US).format(monthCal.time) }

    var summary by remember(monthOffset) {
        mutableStateOf<MonthStatsRemote?>(
            userId?.let { StatsCache.get<MonthStatsRemote>("monthSummary:$it:$monthKey") }
        )
    }
    var dayCounts by remember(monthOffset) {
        mutableStateOf<Map<String, Int>>(
            userId?.let { StatsCache.get<Map<String, Int>>("monthDays:$it:$monthKey") } ?: emptyMap()
        )
    }
    var appCounts by remember(monthOffset) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // 목표 달성 일수 계산용 — 사용자 daily limit
    var dailyLimit by remember { mutableStateOf(0) }
    var loading by remember(monthOffset) { mutableStateOf(summary == null || dayCounts.isEmpty()) }

    LaunchedEffect(monthOffset, userId) {
        loading = true
        if (userId != null) {
            dailyLimit = db.userLimitDao().getLimit(userId)?.dailyLimit ?: 0
        }
        if (summary == null && userId != null) {
            val s = fetchMonthlyStats(userId, monthKey)
            if (s != null) {
                summary = s
                StatsCache.put("monthSummary:$userId:$monthKey", s)
            }
        }
        if (dayCounts.isEmpty() && userId != null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val tmp = Calendar.getInstance().apply { timeInMillis = monthStartMs }
            val monthDays = (1..daysInMonth).map { day ->
                tmp.set(Calendar.DAY_OF_MONTH, day); fmt.format(tmp.time)
            }
            // 로컬 우선 — 로컬에 행이 있는 날짜는 그 값, 없는 날짜만 서버 폴백
            val local = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
            val missing = monthDays.filter { !local.containsKey(it) }
            val server = if (missing.isNotEmpty()) fetchDailyStatsForDays(userId, missing) else emptyMap()
            val merged = monthDays.associateWith { d -> local[d] ?: server[d]?.totalScroll ?: 0 }
            dayCounts = merged
            StatsCache.put("monthDays:$userId:$monthKey", merged)
        }
        appCounts = db.scrollHistoryDao()
            .countByAppForRange(monthStartMs, nextMonthStartMs)
            .associate { it.appPkg to it.count }
        loading = false
    }

    val totalScroll = summary?.totalScroll ?: dayCounts.values.sum()
    val avgPerDay = summary?.avgScrollPerDay ?: (if (dayCounts.isNotEmpty()) totalScroll / daysInMonth else 0)
    val peakDay = dayCounts.maxByOrNull { it.value }
    val peakDate = peakDay?.key ?: summary?.peakDate
    val peakCount = peakDay?.value ?: summary?.peakCount ?: 0
    // 목표 달성 일수 = limit > 0 이고 그 날 totalScroll ≤ limit 이면서 활동이 있었던(>0) 날
    val goalAchievedDays = if (dailyLimit > 0) {
        dayCounts.values.count { it in 1..dailyLimit }
    } else 0
    val activeDays = dayCounts.values.count { it > 0 }

    val parse = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val peakLabel = remember(peakDate) {
        peakDate?.let {
            try { SimpleDateFormat("M월 d일(E)", Locale.KOREAN).format(parse.parse(it)!!) } catch (_: Exception) { it }
        }
    }
    // 도넛 데이터 — 서버 byPlatform 있으면 우선, 없으면 로컬 Room
    // 도넛 — 로컬 우선(달력/일별 막대와 일치). 로컬 비어있을 때만 서버 byPlatform 폴백.
    val donutCounts = if (appCounts.values.sum() > 0) appCounts else summary?.byPlatform ?: emptyMap()

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 기간 표시
            Text(
                "${year}.%02d.01 ~ ${year}.%02d.%02d".format(month + 1, month + 1, daysInMonth),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF555555)
            )
            Spacer(Modifier.height(10.dp))

            // 핵심 수치 3개 — 총합 / 일평균 / 피크일
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonthMetricCard("총 스크롤", "${totalScroll}회", modifier = Modifier.weight(1f))
                MonthMetricCard("일평균", "${avgPerDay}회", modifier = Modifier.weight(1f))
                MonthMetricCard(
                    "가장 많이 본 날",
                    peakLabel ?: "-",
                    subValue = if (peakCount > 0) "${peakCount}회" else null,
                    modifier = Modifier.weight(1f)
                )
            }

            // 목표 달성 일수 + 중단/무시 — 한 줄로
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonthMetricCard(
                    "목표 달성",
                    if (dailyLimit > 0) "${goalAchievedDays}일" else "-",
                    subValue = if (dailyLimit > 0) "활동 ${activeDays}일 중" else "한도 미설정",
                    modifier = Modifier.weight(1f)
                )
                MonthMetricCard(
                    "그만보기",
                    summary?.stopCount?.let { "${it}회" } ?: "-",
                    modifier = Modifier.weight(1f)
                )
                MonthMetricCard(
                    "무시",
                    summary?.ignoreCount?.let { "${it}회" } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }

            Text("앱별 스크롤 비율", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
            Spacer(Modifier.height(6.dp))
            AppShareDonut(donutCounts)

            Text("일별 스크롤", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
            Spacer(Modifier.height(6.dp))
            MonthDailyBarChart(
                year = year,
                month = month,
                daysInMonth = daysInMonth,
                dayCounts = dayCounts,
                peakDate = peakDate
            )

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF888888))
                    Spacer(Modifier.width(8.dp))
                    Text("불러오는 중...", fontSize = 12.sp, color = Color(0xFF888888))
                }
            }
        }
    }
}

// 월별 분석 화면의 작은 지표 카드
@Composable
internal fun MonthMetricCard(label: String, value: String, subValue: String? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 11.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            if (subValue != null) {
                Text(subValue, fontSize = 11.sp, color = Color(0xFF555555), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// 월별 일별 막대 그래프 — 1~말일을 가로로 균등 분할. 피크일은 색을 진하게 표시.
@Composable
internal fun MonthDailyBarChart(
    year: Int,
    month: Int,
    daysInMonth: Int,
    dayCounts: Map<String, Int>,
    peakDate: String?
) {
    val fmt = remember(year, month) { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val tmp = remember(year, month) {
        Calendar.getInstance().apply {
            clear(); set(year, month, 1, 0, 0, 0)
        }
    }
    val counts = remember(year, month, daysInMonth, dayCounts) {
        (1..daysInMonth).map { day ->
            tmp.set(Calendar.DAY_OF_MONTH, day)
            val key = fmt.format(tmp.time)
            key to (dayCounts[key] ?: 0)
        }
    }
    val maxCount = (counts.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val chartHeight = 140.dp
    // 막대 클릭 시 선택된 날짜(1..daysInMonth) — null 이면 미선택. 막대 또는 라벨 다시 클릭으로 토글.
    var selectedDay by remember(year, month) { mutableStateOf<Int?>(null) }
    val labelFmt = remember { SimpleDateFormat("M월 d일(E)", Locale.KOREAN) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFAFAFA)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 선택된 날짜 표시 — 없으면 안내문
            val sel = selectedDay
            if (sel != null) {
                tmp.set(Calendar.DAY_OF_MONTH, sel)
                val label = labelFmt.format(tmp.time)
                val c = counts.getOrNull(sel - 1)?.second ?: 0
                Text(
                    text = "$label · ${c}회",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                Text(
                    text = "막대를 누르면 그 날 스크롤 횟수가 보여요",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            // 막대 영역
            Row(
                modifier = Modifier.fillMaxWidth().height(chartHeight),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                counts.forEachIndexed { idx, (date, c) ->
                    val frac = c.toFloat() / maxCount
                    val isPeak = peakDate != null && date == peakDate && c > 0
                    val dayNum = idx + 1
                    val isSelected = selectedDay == dayNum
                    val color = when {
                        isSelected -> Color(0xFF1A1A1A)
                        c == 0 -> Color(0xFFE0E0E0)
                        isPeak -> Color(0xFFC62828)
                        else -> Color(0xFF8AB4F8)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(frac.coerceAtLeast(0.02f))
                            .background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .clickable { selectedDay = if (selectedDay == dayNum) null else dayNum }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // x 축 라벨: 1, 10, 20, 말일 (각 라벨 클릭으로도 그 날 선택)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(1, 10, 20, daysInMonth).distinct().forEach { d ->
                    Text(
                        "${d}일",
                        fontSize = 10.sp,
                        color = Color(0xFF888888),
                        modifier = Modifier.clickable { selectedDay = if (selectedDay == d) null else d }
                    )
                }
            }
        }
    }
}

// ── 월간: 1년치 12달 그리드. 각 달 총 스크롤을 색 농도(히트맵)로 표시, 누르면 그 달 일간 상세로 드릴다운. ──
// 각 달은 서버 /monthly 로 총 스크롤을 가져옴 (12회 병렬 호출).
@Composable
internal fun StatsMonthly() {
    val context = LocalContext.current
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }
    val now = remember { Calendar.getInstance() }
    val curYear = now.get(Calendar.YEAR)
    val curMonth = now.get(Calendar.MONTH) // 0..11

    var yearOffset by remember { mutableStateOf(0) } // 0 = 올해
    val displayYear = curYear + yearOffset

    // 인라인 펼침 — 선택된 달의 monthOffset(전체 기준). null 이면 닫힘. 같은 셀 다시 누르면 닫힘.
    var expandedOffset by remember(yearOffset) { mutableStateOf<Int?>(null) }

    var monthTotals by remember(yearOffset) {
        mutableStateOf<Map<Int, Int>>(
            userId?.let { StatsCache.get<Map<Int, Int>>("monthly:$it:$displayYear") } ?: emptyMap()
        )
    }
    var loading by remember(yearOffset) { mutableStateOf(monthTotals.isEmpty()) }
    LaunchedEffect(yearOffset, userId) {
        // 캐시 적중하면 네트워크 생략
        if (userId != null) {
            val cached = StatsCache.get<Map<Int, Int>>("monthly:$userId:$displayYear")
            if (cached != null) {
                monthTotals = cached
                loading = false
                return@LaunchedEffect
            }
        }
        loading = true
        val map = mutableMapOf<Int, Int>()
        if (userId != null) {
            val results = coroutineScope {
                (0 until 12).map { m ->
                    async {
                        val isFuture = displayYear > curYear || (displayYear == curYear && m > curMonth)
                        if (isFuture) m to null
                        else m to fetchMonthlyStats(userId, "%04d-%02d".format(displayYear, m + 1))
                    }
                }.awaitAll()
            }
            results.forEach { (m, s) -> if (s != null) map[m] = s.totalScroll }
            StatsCache.put("monthly:$userId:$displayYear", map.toMap())
        }
        monthTotals = map
        loading = false
    }
    val maxTotal = (monthTotals.values.maxOrNull() ?: 0).coerceAtLeast(1)
    val monthNames = listOf("1월","2월","3월","4월","5월","6월","7월","8월","9월","10월","11월","12월")

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 연 네비 ◀ 2026년 ▶
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { yearOffset-- }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 해")
            }
            Text(
                "${displayYear}년", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A), modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(onClick = { if (yearOffset < 0) yearOffset++ }, enabled = yearOffset < 0) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 해")
            }
        }
        if (loading) {
            StatsLoadingSpinner()
        }

        // 3열 × 4행 그리드
        for (rowIdx in 0 until 4) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val m = rowIdx * 3 + col
                    val isFuture = displayYear > curYear || (displayYear == curYear && m > curMonth)
                    val total = monthTotals[m] ?: 0
                    val intensity = total.toFloat() / maxTotal
                    val monthOffset = (displayYear - curYear) * 12 + (m - curMonth)
                    val isSelected = expandedOffset == monthOffset
                    val bg = when {
                        isFuture -> Color(0xFFFAFAFA)
                        isSelected -> Color(0xFF1A1A1A)
                        else -> heatColor(intensity)
                    }
                    var cellMod = Modifier
                        .weight(1f)
                        .aspectRatio(1.1f)
                        .padding(4.dp)
                        .background(bg, RoundedCornerShape(10.dp))
                    if (!isFuture) {
                        cellMod = cellMod.clickable {
                            // 같은 셀 다시 누르면 닫힘 — 토글
                            expandedOffset = if (expandedOffset == monthOffset) null else monthOffset
                        }
                    }
                    Box(modifier = cellMod, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                monthNames[m], fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isFuture -> Color(0xFFCCCCCC)
                                    isSelected -> Color.White
                                    intensity > 0.55f -> Color.White
                                    else -> Color(0xFF1A1A1A)
                                }
                            )
                            if (!isFuture) {
                                Text(
                                    "${total}회", fontSize = 12.sp,
                                    color = when {
                                        isSelected -> Color.White
                                        intensity > 0.55f -> Color.White
                                        else -> Color(0xFF555555)
                                    },
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "달을 누르면 그 달의 상세 통계가 아래에 펼쳐집니다.",
            fontSize = 11.sp, color = Color(0xFF888888),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        // ── 인라인 펼침 영역 — 선택한 달의 상세 통계 ──
        expandedOffset?.let { offset ->
            MonthInlineDetail(monthOffset = offset)
        }
    }
}
