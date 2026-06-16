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

// 선택일의 daily/hourly 한도 도달·추가 팝업 시각 — Service 의 milestone 트리거 로직(DAILY_STEP=100, HOURLY_STEP=10)을 모방.
// scroll_history 의 timestamps 로부터 직접 계산해 분 단위까지 정확.
internal data class MilestoneTimes(
    val dailyExceed: Long?,            // dailyLimit+1번째 스크롤 시각 (=한도 처음 초과 순간). null=미초과
    val dailyExtraPopups: List<Long>,  // dailyLimit+100, +200, ... 번째 스크롤 시각들
    val hourlyExceed: Long?,           // hourly limit 처음 도달 시각 (슬라이딩 윈도우)
    val hourlyExtraPopups: List<Long>  // hourly milestone 추가 도달 시각들
)

internal suspend fun computeMilestones(
    db: AppDatabase,
    startMs: Long,
    endMs: Long,
    dailyLimit: Int,
    hourlyLimit: Int
): MilestoneTimes {
    val ts = db.scrollHistoryDao().timestampsInRange(startMs, endMs)
    // Daily — 그날 N번째 스크롤
    val dailyExceed = if (dailyLimit > 0 && ts.size > dailyLimit) ts[dailyLimit] else null
    val dailyExtras = mutableListOf<Long>()
    if (dailyLimit > 0) {
        var trigger = dailyLimit + 100  // DAILY_STEP
        while (ts.size > trigger) {
            dailyExtras.add(ts[trigger])
            trigger += 100
        }
    }
    // Hourly — Service 로직 모방. 슬라이딩 1시간 윈도우.
    var hourlyFirst: Long? = null
    val hourlyExtras = mutableListOf<Long>()
    if (hourlyLimit > 0) {
        val window = 60 * 60 * 1000L
        val step = 10  // HOURLY_STEP
        var milestone = -1
        var left = 0
        for (right in ts.indices) {
            val now = ts[right]
            while (left <= right && now - ts[left] > window) left++
            val hourlyCount = right - left + 1
            if (hourlyCount < hourlyLimit && milestone >= 0) milestone = -1
            if (hourlyCount >= hourlyLimit) {
                val nextTrigger = if (milestone < 0) {
                    hourlyLimit + ((hourlyCount - hourlyLimit) / step) * step
                } else {
                    milestone + step
                }
                if (hourlyCount >= nextTrigger) {
                    milestone = nextTrigger
                    if (hourlyFirst == null) hourlyFirst = now
                    else hourlyExtras.add(now)
                }
            }
        }
    }
    return MilestoneTimes(dailyExceed, dailyExtras, hourlyFirst, hourlyExtras)
}

// HH:mm 포맷 — 박스에서 공통 사용
internal val POPUP_TIME_FMT = SimpleDateFormat("HH:mm", Locale.US)

internal fun fmtHHmm(ms: Long): String = POPUP_TIME_FMT.format(java.util.Date(ms))

// ── 일일 목표 진행률 박스 — 진행률 바 + 한도 초과 시각 + 추가 팝업 시각 ──
@Composable
internal fun DailyGoalProgressBox(
    total: Int,
    dailyLimit: Int,
    exceeded: Boolean,
    source: String,
    dailyExceedTime: Long?,
    dailyExtraPopupTimes: List<Long>
) {
    val frac = if (dailyLimit > 0) (total.toFloat() / dailyLimit).coerceIn(0f, 1.5f) else 0f
    val barColor = if (exceeded) Color(0xFFC62828) else Color(0xFF2E7D32)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "일일 목표 진행률" + if (source == "local") " (로컬)" else "",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A1A)
                )
                if (dailyLimit > 0) {
                    Text(
                        "${total} / ${dailyLimit}회",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = barColor
                    )
                } else {
                    Text("${total}회 / 한도 미설정", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
            Spacer(Modifier.height(8.dp))
            // 진행률 바
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(Color(0xFFE8E8E8), RoundedCornerShape(5.dp))
            ) {
                if (dailyLimit > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac.coerceAtMost(1f))
                            .fillMaxHeight()
                            .background(barColor, RoundedCornerShape(5.dp))
                    )
                }
            }
            if (dailyLimit > 0) {
                Spacer(Modifier.height(8.dp))
                if (dailyExceedTime != null) {
                    Text(
                        "한도 초과: ${fmtHHmm(dailyExceedTime)}",
                        fontSize = 12.sp,
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (dailyExtraPopupTimes.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "추가 팝업: " + dailyExtraPopupTimes.joinToString(", ") { fmtHHmm(it) },
                            fontSize = 12.sp,
                            color = Color(0xFF555555),
                            lineHeight = 17.sp
                        )
                    }
                } else {
                    Text(
                        "목표 ${dailyLimit}회 이내 ✓",
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// hourly 한도 초과 팝업 시각 박스 — hourly limit 처음 도달 시각 + 추가 팝업(milestone) 시각만 표시.
@Composable
internal fun HourlyGoalBox(
    hourlyLimit: Int,
    hourlyExceedTime: Long?,
    hourlyExtraPopupTimes: List<Long>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                // 한도 처음 도달 시 1번 + 그 이후 10회씩 더 볼 때마다(=한도+10, +20, ...). "0부터 10마다" 가 아님.
                "hourly 한도 초과 팝업" +
                    if (hourlyLimit > 0) " (한도 ${hourlyLimit}회, 이후 +10회마다)" else "",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1A1A1A)
            )
            Spacer(Modifier.height(6.dp))
            if (hourlyLimit <= 0) {
                Text("시간별 한도가 설정되지 않았어요", fontSize = 12.sp, color = Color(0xFF888888))
                return@Column
            }
            if (hourlyExceedTime == null) {
                Text("오늘 한도 도달 없음 ✓", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                return@Column
            }
            Text(
                "처음 도달: ${fmtHHmm(hourlyExceedTime)}",
                fontSize = 12.sp,
                color = Color(0xFFC62828),
                fontWeight = FontWeight.SemiBold
            )
            if (hourlyExtraPopupTimes.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "추가 팝업: " + hourlyExtraPopupTimes.joinToString(", ") { fmtHHmm(it) },
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                    lineHeight = 17.sp
                )
            }
        }
    }
}

// ── 일간: 월 달력 + 선택일 24시간 누적 그래프 ───────────────────────────
// 상단 달력(히트맵)에서 날짜를 누르면 그 날의 시간대별 누적 스크롤 그래프를 아래에 표시.
// 그래프엔 그 날의 daily limit 을 주황 점선으로 그려 한도 초과 여부를 한눈에 보여줌.
// monthOffset 은 상위(StatsTabContent)에서 들고 있어 월간 탭에서 드릴다운 가능.
@Composable
internal fun StatsDaily(monthOffset: Int, onMonthOffsetChange: (Int) -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }

    // 달력 히트맵 일자별 카운트(yyyy-MM-dd → 스크롤 수) + 로컬 폴백용 현재 daily/hourly limit.
    // 상세 그래프와 같은 서버 /daily 를 소스로 써서 같은 날 숫자가 어긋나지 않게 함(서버에 없는 날만 로컬 Room 폴백).
    var dayCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var currentDailyLimit by remember { mutableStateOf(0) }
    var currentHourlyLimit by remember { mutableStateOf(0) }
    LaunchedEffect(userId) {
        val limit = userId?.let { db.userLimitDao().getLimit(it) }
        currentDailyLimit = limit?.dailyLimit ?: 0
        currentHourlyLimit = limit?.hourlyLimit ?: 0
    }

    // 달력이 보여주는 달 (0=이번 달, -1=저번 달 ...) — 상위 상태
    val monthCal = remember(monthOffset) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val year = monthCal.get(Calendar.YEAR)
    val month = monthCal.get(Calendar.MONTH) // 0~11
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = (monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // MON=0..SUN=6

    val monthKey = remember(monthOffset) { SimpleDateFormat("yyyy-MM", Locale.US).format(monthCal.time) }

    // 표시 중인 달의 일자별 스크롤 수를 서버 /daily 로 가져옴(상세 그래프와 동일 소스).
    // 서버 응답이 있는 날은 서버값, 없는 날(오프라인 등)만 로컬 Room countByDay 로 폴백.
    LaunchedEffect(monthOffset, userId) {
        if (userId != null) {
            val cached = StatsCache.get<Map<String, Int>>("monthDays:$userId:$monthKey")
            if (cached != null) {
                dayCounts = cached
                return@LaunchedEffect
            }
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tmp = Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis }
        val monthDays = (1..daysInMonth).map { day ->
            tmp.set(Calendar.DAY_OF_MONTH, day); fmt.format(tmp.time)
        }
        // 로컬 우선 — 7일치 보관 한도 안의 날짜는 로컬이 항상 최신(홈탭과 일치).
        // 로컬에 행 자체가 없는(8일 이전 등) 날짜만 서버에서 가져와 폴백 → 서버 호출 최소화.
        val local = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
        val missing = monthDays.filter { !local.containsKey(it) }
        val server = if (missing.isNotEmpty() && userId != null) {
            fetchDailyStatsForDays(userId, missing)
        } else emptyMap()
        val merged = monthDays.associateWith { d -> local[d] ?: server[d]?.totalScroll ?: 0 }
        dayCounts = merged
        userId?.let { StatsCache.put("monthDays:$it:$monthKey", merged) }
    }

    val countByDate = remember(monthOffset, dayCounts) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tmp = Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis }
        (1..daysInMonth).associateWith { day ->
            tmp.set(Calendar.DAY_OF_MONTH, day)
            val key = fmt.format(tmp.time)
            dayCounts[key] ?: 0
        }
    }
    val maxCount = (countByDate.values.maxOrNull() ?: 0).coerceAtLeast(1)

    // 선택된 날짜 — 절대 자정 millis. 기본값 = 오늘.
    val todayMidnight = remember { startOfDayMs(0) }
    var selectedDayMs by remember { mutableStateOf(todayMidnight) }
    val selectedDateStr = remember(selectedDayMs) {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(selectedDayMs))
    }
    val selectedLabel = remember(selectedDayMs) {
        SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREAN).format(java.util.Date(selectedDayMs))
    }

    // 표시 중인 달이 바뀌면(달력 이동/월간 드릴다운) 선택일을 그 달 안으로 맞춤
    LaunchedEffect(monthOffset) {
        val selCal = Calendar.getInstance().apply { timeInMillis = selectedDayMs }
        if (selCal.get(Calendar.YEAR) != year || selCal.get(Calendar.MONTH) != month) {
            selectedDayMs = if (monthOffset == 0) startOfDayMs(0) else monthCal.timeInMillis
        }
    }

    // 선택일 상세 — 로컬 우선(홈탭과 일치, 실시간). 로컬에 행이 없으면(8일 이전) 서버 폴백.
    // serverStats 는 별도로 계속 가져옴(stopCount/ignoreCount/hourlyLimit 같은 메트릭은 서버에만 있음).
    var counts by remember { mutableStateOf(IntArray(24)) }
    var serverStats by remember { mutableStateOf<DailyStatsRemote?>(null) }
    var source by remember { mutableStateOf("loading") }  // "server" / "local" / "loading"
    var dayAppCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // milestone 시각 (분까지 정확) — Service 트리거 로직 모방으로 로컬 timestamps 에서 계산
    var dailyExceedTime by remember { mutableStateOf<Long?>(null) }
    var dailyExtraPopupTimes by remember { mutableStateOf<List<Long>>(emptyList()) }
    var hourlyExceedTime by remember { mutableStateOf<Long?>(null) }
    var hourlyExtraPopupTimes by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(selectedDayMs, userId, currentDailyLimit, currentHourlyLimit) {
        source = "loading"
        val endOfDay = Calendar.getInstance().apply {
            timeInMillis = selectedDayMs
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        // 로컬 시간대별 카운트 먼저
        val hourly = db.scrollHistoryDao().countByHourForDay(selectedDayMs, endOfDay)
        val localCounts = IntArray(24).also { arr ->
            hourly.forEach { if (it.hour in 0..23) arr[it.hour] = it.count }
        }
        val hasLocal = localCounts.sum() > 0
        // 서버 메트릭(stopCount/ignoreCount/hourlyLimit)은 항상 같이 가져옴 — 표시용 그래프는 로컬 우선
        val remote = userId?.let { fetchDailyStats(it, selectedDateStr) }
        serverStats = remote
        if (hasLocal) {
            counts = localCounts
            source = "local"
        } else if (remote != null) {
            counts = remote.hourlyCounts
            source = "server"
        } else {
            counts = localCounts  // 0 배열
            source = "local"
        }
        // 도넛은 항상 로컬 DB 기반
        dayAppCounts = db.scrollHistoryDao()
            .countByAppForRange(selectedDayMs, endOfDay)
            .associate { it.appPkg to it.count }
        // milestone 시각 — 로컬 timestamps 로 정확히 계산 (분 단위까지)
        // [중요] "그날 실제로 팝업을 트리거한 한도값" 을 써야 한다:
        //   - 오늘: 로컬 current(=오늘 실제로 적용 중인 값). 서버 limits/{userId} 는
        //     limit-sync 가 promote 시점(다음 주 월요일)에만 push 되므로 stale 일 수 있음.
        //     예: 서버=10, 로컬=50 인데 오늘 31회 → 서버값 쓰면 10,20,30 시점 "가짜 팝업 3건" 생김.
        //   - 과거: 서버 스냅샷 우선(finalizeStats 가 그날 적용 값을 박제). 없으면 로컬로 폴백.
        val selectedIsToday = selectedDayMs == todayMidnight
        val dLim = if (selectedIsToday) currentDailyLimit
                   else (remote?.dailyLimit?.takeIf { it > 0 } ?: currentDailyLimit)
        val hLim = if (selectedIsToday) currentHourlyLimit
                   else (remote?.hourlyLimit ?: currentHourlyLimit)
        if (dLim > 0 || hLim > 0) {
            val m = computeMilestones(db, selectedDayMs, endOfDay, dLim, hLim)
            dailyExceedTime = m.dailyExceed
            dailyExtraPopupTimes = m.dailyExtraPopups
            hourlyExceedTime = m.hourlyExceed
            hourlyExtraPopupTimes = m.hourlyExtraPopups
        } else {
            dailyExceedTime = null
            dailyExtraPopupTimes = emptyList()
            hourlyExceedTime = null
            hourlyExtraPopupTimes = emptyList()
        }
    }

    // 누적값 — cumulative[i] = counts[0..i-1] 합. 길이 25
    val cumulative = remember(counts) {
        IntArray(25).also { arr ->
            var sum = 0
            for (i in 0 until 24) {
                arr[i] = sum
                sum += counts[i]
            }
            arr[24] = sum
        }
    }
    val total = cumulative[24]
    val peakHour = counts.indices.maxByOrNull { counts[it] } ?: 0
    val peakCount = counts.getOrNull(peakHour) ?: 0

    // 그 날의 daily limit — 과거: 서버값(finalizeStats 스냅샷) 우선, 오늘: 로컬 current.
    // [중요] 오늘에 서버값을 쓰면 안 됨 — limit-sync 가 다음 주 월요일 promote 때까지 안 일어나서
    // 서버 limits/{userId} 가 stale 일 수 있음(예: 한도 변경 직후엔 옛 값).
    val selectedIsToday = selectedDayMs == todayMidnight
    val dailyLimit = if (selectedIsToday) currentDailyLimit
                     else (serverStats?.dailyLimit?.takeIf { it > 0 } ?: currentDailyLimit)
    val exceeded = dailyLimit > 0 && total > dailyLimit

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 월 네비 ← 2026.05 →
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { onMonthOffsetChange(monthOffset - 1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 달")
            }
            Text(
                text = "${year}.%02d".format(month + 1),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(
                onClick = { if (monthOffset < 0) onMonthOffsetChange(monthOffset + 1) },
                enabled = monthOffset < 0
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 달")
            }
        }

        // 요일 헤더
        val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEach { d ->
                Text(
                    text = d,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF888888)
                )
            }
        }

        // 달력 셀 — 누르면 선택일 변경 (미래 날짜는 비활성, 선택일은 테두리 강조)
        val totalCells = firstDayOfWeek + daysInMonth
        val totalRows = (totalCells + 6) / 7
        for (rowIdx in 0 until totalRows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIdx = rowIdx * 7 + col
                    val dayNum = cellIdx - firstDayOfWeek + 1
                    if (dayNum in 1..daysInMonth) {
                        val cellMs = Calendar.getInstance().apply {
                            timeInMillis = monthCal.timeInMillis
                            set(Calendar.DAY_OF_MONTH, dayNum)
                        }.timeInMillis
                        val isFuture = cellMs > todayMidnight
                        val isSelected = cellMs == selectedDayMs
                        val count = countByDate[dayNum] ?: 0
                        // 그 달 최대 오버량 — 빨강 진하기 기준
                        val maxOverage = (countByDate.values.maxOrNull() ?: 0) - currentDailyLimit
                        val (bgRaw, isDarkBg) =
                            if (isFuture) Color(0xFFFAFAFA) to false
                            else goalColor(count, currentDailyLimit, maxCount, maxOverage)
                        var cellMod = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .background(bgRaw, RoundedCornerShape(6.dp))
                        if (isSelected) {
                            cellMod = cellMod.border(2.dp, Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                        }
                        if (!isFuture) {
                            cellMod = cellMod.clickable { selectedDayMs = cellMs }
                        }
                        Box(modifier = cellMod, contentAlignment = Alignment.TopCenter) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 3.dp)
                            ) {
                                Text(
                                    text = dayNum.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        isFuture -> Color(0xFFCCCCCC)
                                        isDarkBg -> Color.White
                                        else -> Color(0xFF1A1A1A)
                                    },
                                    lineHeight = 14.sp
                                )
                                // 0회도 표시 — 미래일자 제외
                                if (!isFuture) {
                                    Text(
                                        text = count.toString(),
                                        fontSize = 10.sp,
                                        lineHeight = 11.sp,
                                        color = if (isDarkBg) Color.White else Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp))
                    }
                }
            }
        }

        // 히트맵 범례
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("적음", fontSize = 11.sp, color = Color(0xFF888888))
            Spacer(Modifier.width(6.dp))
            listOf(0.1f, 0.35f, 0.6f, 0.85f, 1.0f).forEach { f ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 4.dp)
                        .background(
                            lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * f),
                            RoundedCornerShape(4.dp)
                        )
                )
            }
            Spacer(Modifier.width(2.dp))
            Text("많음", fontSize = 11.sp, color = Color(0xFF888888))
        }

        Spacer(Modifier.height(20.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
        Spacer(Modifier.height(16.dp))

        // ── 선택일 상세 영역 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = selectedLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
            // 로딩 중이면 제목 옆에 작은 스피너 — 상세가 화면 아래라도 로딩 여부가 보이게
            if (source == "loading") {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF4FC3F7)
                )
            }
        }

        // 로딩 중(서버 fetch 대기)이면 상세 대신 스피너 — 일간도 월간처럼 로딩 표시
        if (source == "loading") {
            StatsLoadingSpinner(modifier = Modifier.fillMaxWidth().height(240.dp))
        } else {

        // 일일 목표 진행률 박스 — 한도 초과 시각 + 추가 팝업 시각만(분까지)
        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            DailyGoalProgressBox(
                total = total,
                dailyLimit = dailyLimit,
                exceeded = exceeded,
                source = source,
                dailyExceedTime = dailyExceedTime,
                dailyExtraPopupTimes = dailyExtraPopupTimes
            )
        }
        Spacer(Modifier.height(12.dp))

        // 시간별 한도 팝업 박스 — 도달 시각 + 추가 팝업(milestone) 시각만
        // dailyLimit 와 동일한 이유로 오늘은 로컬 current, 과거는 서버 스냅샷.
        val hourlyLimitForDay = if (selectedIsToday) currentHourlyLimit
                                else (serverStats?.hourlyLimit ?: currentHourlyLimit)
        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            HourlyGoalBox(
                hourlyLimit = hourlyLimitForDay,
                hourlyExceedTime = hourlyExceedTime,
                hourlyExtraPopupTimes = hourlyExtraPopupTimes
            )
        }
        Spacer(Modifier.height(12.dp))

        // 앱별 비율 도넛 — 서버 byPlatform 있으면 우선, 없으면 로컬 Room
        Text(
            "앱별 스크롤 비율",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )
        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            // 로컬 우선 — 7일치 한도 안의 날짜는 로컬이 정확(달력/진행률과 일치).
            // 로컬에 데이터 없는 8일 이전 날짜만 서버 byPlatform 폴백.
            AppShareDonut(
                if (dayAppCounts.values.sum() > 0) dayAppCounts
                else serverStats?.byPlatform ?: emptyMap()
            )
        }

        // Canvas 차트 영역 — 누적 라인 + 그날 한도 점선
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(start = 8.dp, end = 16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val leftPad = 40f
                val bottomPad = 24f
                val topPad = 8f
                val plotW = w - leftPad - 8f
                val plotH = h - topPad - bottomPad
                // 한도 점선이 항상 보이도록 총합과 한도 중 큰 값을 y축 최대치로
                val maxY = maxOf(total, dailyLimit).coerceAtLeast(1)

                // y축 가이드 라인 (4분할)
                val gridColor = Color(0xFFEEEEEE)
                for (i in 0..4) {
                    val y = topPad + plotH * (1f - i / 4f)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(leftPad + plotW, y),
                        strokeWidth = 1f
                    )
                }

                // 피크 시간 빨강 강조 배경
                if (peakCount > 0) {
                    val x1 = leftPad + plotW * (peakHour / 24f)
                    val x2 = leftPad + plotW * ((peakHour + 1) / 24f)
                    drawRect(
                        color = Color(0xFFC62828).copy(alpha = 0.18f),
                        topLeft = Offset(x1, topPad),
                        size = androidx.compose.ui.geometry.Size(x2 - x1, plotH)
                    )
                }

                // 그날 daily limit — 주황 점선 가로선
                if (dailyLimit > 0) {
                    val limitY = topPad + plotH * (1f - dailyLimit.toFloat() / maxY)
                    drawLine(
                        color = Color(0xFFFF8F00),
                        start = Offset(leftPad, limitY),
                        end = Offset(leftPad + plotW, limitY),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }

                // 누적 라인
                val path = Path()
                for (i in 0..24) {
                    val x = leftPad + plotW * (i / 24f)
                    val y = topPad + plotH * (1f - cumulative[i].toFloat() / maxY)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF1A1A1A),
                    style = Stroke(width = 3f)
                )

                // 피크 구간 라인은 빨강으로 덧칠
                if (peakCount > 0) {
                    val redPath = Path()
                    val x1 = leftPad + plotW * (peakHour / 24f)
                    val y1 = topPad + plotH * (1f - cumulative[peakHour].toFloat() / maxY)
                    val x2 = leftPad + plotW * ((peakHour + 1) / 24f)
                    val y2 = topPad + plotH * (1f - cumulative[peakHour + 1].toFloat() / maxY)
                    redPath.moveTo(x1, y1)
                    redPath.lineTo(x2, y2)
                    drawPath(
                        path = redPath,
                        color = Color(0xFFC62828),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }

        // x축 라벨
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(0, 6, 12, 18, 24).forEach { h ->
                Text(text = "${h}시", fontSize = 11.sp, color = Color(0xFF888888))
            }
        }

        // 한도 점선 범례
        if (dailyLimit > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(width = 18.dp, height = 3.dp).background(Color(0xFFFF8F00)))
                Spacer(Modifier.width(6.dp))
                Text("daily limit (${dailyLimit}회)", fontSize = 11.sp, color = Color(0xFF888888))
            }
        }
        } // end else — 로딩 아닐 때만 상세 표시

        Spacer(Modifier.height(24.dp))
    }
}
