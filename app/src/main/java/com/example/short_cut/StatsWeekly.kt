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

// ── 주간: 연도 선택 + 1~4분기 탭. 선택한 분기의 주(週)만 4열 그리드로 색 농도(히트맵) 표시.
//   배치는 좌상단=과거 → 우하단=최신(오름차순). 한 주를 누르면 그 주 상세(요약 + 월~일 막대그래프). ──
// 데이터는 그 해 전체를 한 번 받아(yearOffset 키) 분기 전환은 즉시. 그리드 합계·상세 막대 모두 같은
// /daily 일별 데이터에서 나와 항상 일치. 주가 속한 분기 = 그 주 목요일의 월/3 (연도 판정과 동일 기준).
@Composable
internal fun StatsWeekly() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }
    // 현재 사용자 daily limit — 막대 그래프 색(달성/초과) 기준
    var currentDailyLimit by remember { mutableStateOf(0) }
    LaunchedEffect(userId) {
        currentDailyLimit = userId?.let { db.userLimitDao().getLimit(it)?.dailyLimit } ?: 0
    }
    val nowMs = remember { System.currentTimeMillis() }
    val curYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val curQuarter = remember { Calendar.getInstance().get(Calendar.MONTH) / 3 } // 0=1분기 .. 3=4분기
    val thisWeekMonday = remember { mondayOf(nowMs) }
    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(nowMs)) }

    var yearOffset by remember { mutableStateOf(0) }
    val displayYear = curYear + yearOffset
    val isCurrentYear = yearOffset == 0

    // 선택 분기 — 올해는 현재 분기로 시작
    var quarter by remember { mutableStateOf(curQuarter) }

    val dayFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    // 그 해 전체 주(월요일) — 오름차순(과거가 앞)
    val allWeeks = remember(yearOffset) { weeksOfYear(displayYear, thisWeekMonday, isCurrentYear) }

    // 주가 속한 분기 = 그 주 목요일의 월 / 3
    fun weekQuarter(ws: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ws; add(Calendar.DAY_OF_YEAR, 3) }
        return c.get(Calendar.MONTH) / 3
    }
    // 선택 분기의 주들 — 오름차순이라 좌상단=과거, 우하단=최신
    val weeks = remember(allWeeks, quarter) { allWeeks.filter { weekQuarter(it) == quarter } }

    // 표시 주들의 모든 날짜(오늘 이전)를 /daily 로 받아 일별 합산 — 그 해 전체를 한 번에(분기 전환은 즉시)
    var dayTotals by remember(yearOffset) {
        mutableStateOf<Map<String, Int>>(
            userId?.let { StatsCache.get<Map<String, Int>>("weekly:$it:$displayYear") } ?: emptyMap()
        )
    }
    var loading by remember(yearOffset) { mutableStateOf(dayTotals.isEmpty()) }
    // 서버 보강(미싱 날짜 /daily) 진행 중 표시 — 로컬은 즉시 떠도 서버 fetch 동안 스피너 유지
    var refreshing by remember(yearOffset) { mutableStateOf(false) }
    LaunchedEffect(yearOffset, userId) {
        if (userId != null) {
            val cached = StatsCache.get<Map<String, Int>>("weekly:$userId:$displayYear")
            if (cached != null) {
                dayTotals = cached
                loading = false
                return@LaunchedEffect
            }
        }
        if (userId != null) {
            val cal = Calendar.getInstance()
            val days = mutableListOf<String>()
            for (ws in allWeeks) {
                for (i in 0 until 7) {
                    cal.timeInMillis = ws
                    cal.add(Calendar.DAY_OF_YEAR, i)
                    val ds = dayFmt.format(cal.time)
                    if (ds <= todayStr) days.add(ds)  // yyyy-MM-dd 문자열 비교 = 날짜 비교
                }
            }
            // 1단계: 로컬로 즉시 화면 표시 (이번 주는 로컬에 다 있음 → 0회로 잠깐 안 보임)
            val local = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
            dayTotals = days.associateWith { local[it] ?: 0 }
            loading = false

            // 2단계: 미싱 날짜만 서버에서 백그라운드로 보강해 dayTotals 갱신
            val missing = days.filter { !local.containsKey(it) }
            if (missing.isNotEmpty()) {
                refreshing = true
                val server = fetchDailyTotalsForDays(userId, missing)
                val result = days.associateWith { d -> local[d] ?: server[d] ?: 0 }
                dayTotals = result
                StatsCache.put("weekly:$userId:$displayYear", result)
                refreshing = false
            } else {
                StatsCache.put("weekly:$userId:$displayYear", dayTotals)
            }
        } else {
            loading = false
        }
    }

    fun weekDayCounts(ws: Long): List<Int> {
        val cal = Calendar.getInstance()
        return (0 until 7).map { i ->
            cal.timeInMillis = ws
            cal.add(Calendar.DAY_OF_YEAR, i)
            dayTotals[dayFmt.format(cal.time)] ?: 0
        }
    }
    fun weekTotal(ws: Long): Int = weekDayCounts(ws).sum()
    // 히트맵 농도는 그 해 전체 최대값 기준 — 분기끼리 색을 비교할 수 있게
    val maxTotal = remember(dayTotals, allWeeks) {
        (allWeeks.maxOfOrNull { weekTotal(it) } ?: 0).coerceAtLeast(1)
    }

    // 선택 주 — 분기 안의 최신 주를 기본 선택. 분기/연도 바뀌면 재설정.
    var selectedWeek by remember(yearOffset, quarter) {
        mutableStateOf(weeks.lastOrNull() ?: thisWeekMonday)
    }

    val shortFmt = remember { SimpleDateFormat("M.d", Locale.US) }
    val longStartFmt = remember { SimpleDateFormat("yyyy.MM.dd", Locale.US) }
    val longEndFmt = remember { SimpleDateFormat("MM.dd", Locale.US) }
    fun shortRange(ws: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ws }
        val s = shortFmt.format(c.time)
        c.add(Calendar.DAY_OF_YEAR, 6)
        return "$s~${shortFmt.format(c.time)}"
    }
    fun longRange(ws: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ws }
        val s = longStartFmt.format(c.time)
        c.add(Calendar.DAY_OF_YEAR, 6)
        return "$s ~ ${longEndFmt.format(c.time)}"
    }

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

        // 분기 선택 탭 (1~4분기) — 한 번에 한 분기만 표시
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("1분기", "2분기", "3분기", "4분기").forEachIndexed { i, label ->
                val selected = quarter == i
                Surface(
                    modifier = Modifier.weight(1f).clickable { quarter = i },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) Color(0xFF1A1A1A) else Color(0xFFF1F1F1)
                ) {
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        color = if (selected) Color.White else Color(0xFF1A1A1A),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (loading || refreshing) {
            StatsLoadingSpinner()
        }

        if (weeks.isEmpty()) {
            Text(
                "이 분기에는 아직 기록이 없습니다.",
                fontSize = 13.sp, color = Color(0xFF888888),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            // 주 그리드 — 4열, 좌상단(과거) → 우하단(최신)
            val cols = 4
            val rowsN = (weeks.size + cols - 1) / cols
            for (r in 0 until rowsN) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        if (idx < weeks.size) {
                            val ws = weeks[idx]
                            val total = weekTotal(ws)
                            val intensity = total.toFloat() / maxTotal
                            val selected = ws == selectedWeek
                            var cellMod = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(3.dp)
                                .background(heatColor(intensity), RoundedCornerShape(8.dp))
                                .clickable { selectedWeek = ws }
                            if (selected) cellMod = cellMod.border(2.dp, Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                            Box(modifier = cellMod, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        shortRange(ws), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (intensity > 0.55f) Color.White else Color(0xFF1A1A1A)
                                    )
                                    Text(
                                        "${total}회", fontSize = 10.sp,
                                        color = if (intensity > 0.55f) Color.White else Color(0xFF555555)
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(3.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
            Spacer(Modifier.height(16.dp))

            // 선택 주 상세 (그리드와 같은 dayTotals 에서 계산 → 항상 일치)
            val dc = weekDayCounts(selectedWeek)
            val total = dc.sum()
            val peakIdx = dc.indices.maxByOrNull { dc[it] } ?: 0
            val peakDateStr = if (dc[peakIdx] > 0) {
                Calendar.getInstance().apply { timeInMillis = selectedWeek; add(Calendar.DAY_OF_YEAR, peakIdx) }
                    .let { dayFmt.format(it.time) }
            } else null

            // 선택 주의 앱별 카운트 — 로컬 Room (7일 범위)
            var weekAppCounts by remember(selectedWeek) { mutableStateOf<Map<String, Int>>(emptyMap()) }
            LaunchedEffect(selectedWeek) {
                val weekEnd = selectedWeek + 7L * 24L * 60L * 60L * 1000L
                weekAppCounts = db.scrollHistoryDao()
                    .countByAppForRange(selectedWeek, weekEnd)
                    .associate { it.appPkg to it.count }
            }

            Text(
                longRange(selectedWeek), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            StatsSummaryCard(
                title = "주간 요약",
                totalScroll = total,
                avgPerDay = total / 7,
                peakDate = peakDateStr,
                peakCount = if (peakDateStr != null) dc[peakIdx] else null,
                loading = loading,
                errorMsg = null
            )
            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                if (loading || refreshing) {
                    StatsLoadingSpinner(modifier = Modifier.fillMaxSize())
                } else {
                    WeekBarChart(counts = dc, dailyLimit = currentDailyLimit)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "앱별 스크롤 비율",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            AppShareDonut(weekAppCounts)

            Spacer(Modifier.height(24.dp))
        }
    }
}

// 한 주의 막대 그래프 — counts 는 [월, 화, 수, 목, 금, 토, 일] 순서.
// dailyLimit 기준으로 막대 색: 이내(달성) 초록, 초과 빨강. 한도 0(미설정) 이면 기존 검정.
// 그 주의 데일리 리밋을 가로 점선으로 겹쳐 표시 — 막대와 같은 스케일을 공유해
// 어느 날이 한도를 넘었는지(빨강 + 점선 위로 솟음) 한 눈에 보이게 한다.
@Composable
internal fun WeekBarChart(counts: List<Int>, dailyLimit: Int) {
    val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    val maxCount = counts.max().coerceAtLeast(1)
    // 점선이 항상 화면 안에 보이도록 막대·점선이 같은 y 스케일을 공유 (한도가 최댓값보다 커도 잘리지 않음)
    val scale = (if (dailyLimit > 0) maxOf(maxCount, dailyLimit) else maxCount).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // 플롯 영역 — 막대 Row + 한도 점선 Canvas 오버레이 (둘 다 이 Box 높이를 기준으로)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                counts.forEachIndexed { idx, count ->
                    val barColor = when {
                        dailyLimit <= 0 -> Color(0xFF1A1A1A)
                        count == 0 -> Color(0xFF1A1A1A)  // 활동 없음 — 표시 안 되니까 그냥 기본값
                        count <= dailyLimit -> Color(0xFF2E7D32)
                        else -> Color(0xFFC62828)
                    }
                    WeekBar(
                        count = count,
                        scale = scale,
                        barColor = barColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // 데일리 리밋 — 주황 가로 점선 (플롯 높이 기준, 막대와 동일 스케일)
            if (dailyLimit > 0) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y = size.height * (1f - dailyLimit.toFloat() / scale)
                    drawLine(
                        color = Color(0xFFFF8F00),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 요일 라벨 — 막대와 같은 weight/간격으로 정렬
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            dayLabels.forEach { label ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A1A),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 요일 밑 — 그 날 스크롤 횟수 (한도 초과는 빨강, 이내는 검정, 0회는 회색 '-')
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            counts.forEach { c ->
                val countColor = when {
                    c == 0 -> Color(0xFFBBBBBB)
                    dailyLimit > 0 && c > dailyLimit -> Color(0xFFC62828)
                    else -> Color(0xFF1A1A1A)
                }
                Text(
                    text = if (c > 0) "${c}회" else "-",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = countColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 한도 점선 범례
        if (dailyLimit > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(width = 18.dp, height = 3.dp).background(Color(0xFFFF8F00)))
                Spacer(Modifier.width(6.dp))
                Text("daily limit (${dailyLimit}회)", fontSize = 11.sp, color = Color(0xFF888888))
            }
        }
    }
}

// 막대 하나 — count=0 이면 회색 placeholder, 그 외엔 barColor 막대.
// [중요] 막대 높이는 플롯 '전체 높이'(maxHeight) × fraction 으로 그린다.
//   예전엔 막대 위 카운트 텍스트가 Column 의 세로 공간을 차지해, 막대 좌표계가
//   Canvas 한도 점선의 좌표계(전체 높이)보다 짧아졌다. 그 결과 한도(100)를 넘긴 날(112)도
//   점선 아래에 머무는 버그가 있었다. 이제 막대는 전체 높이 기준이고 라벨은 막대 위에
//   '떠 있게'(레이아웃 높이 비점유) 배치해 점선과 정확히 같은 스케일을 공유한다.
@Composable
internal fun WeekBar(
    count: Int,
    scale: Int,
    barColor: Color = Color(0xFF1A1A1A),
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (count == 0) {
            // 미래/빈 요일 — 회색 얇은 placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(4.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(2.dp))
            )
        } else {
            val fraction = (count.toFloat() / scale).coerceIn(0f, 1f)
            val barH = maxHeight * fraction
            // 막대 — 전체 높이 기준 (한도 점선과 동일 스케일 → 초과하면 점선 위로 솟음)
            // 카운트 숫자는 막대 위가 아니라 요일 라벨 밑(아래 별도 Row)에 표시한다.
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(barH)
                    .background(barColor, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            )
        }
    }
}
