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

// 막대그래프 가로 기준선 간격을 100/200/500/1000... 중 보기 좋은 값으로 고름(구간 약 4개).
internal fun niceGridStep(maxValue: Int): Int {
    val rough = (maxValue.toDouble() / 4).coerceAtLeast(1.0)
    val mags = listOf(100, 200, 500)
    var mult = 1
    while (mult <= 1_000_000) {
        for (m in mags) {
            val s = m * mult
            if (s >= rough) return s
        }
        mult *= 10
    }
    return 1_000_000
}

// ── 주간 리포트 화면: 일요일 저녁 8시 리포트 알림을 누르면 열리는 전체 화면 ──────────
// 지난주 vs 이번주 일별(월~일) 스크롤을 묶음 막대로 비교. 지난주는 회색, 이번주는 하늘색.
// 가로 기준선(100/200…)으로 대략 회수를 눈대중할 수 있게 한다.
// 데이터는 로컬 Room(최근 데이터는 로컬에 다 있음) → 빠진 과거 날짜만 서버 /daily 로 보강.
@Composable
fun WeeklyReportScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }
    val nowMs = remember { System.currentTimeMillis() }
    val thisMonday = remember { mondayOf(nowMs) }
    val dayFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val todayStr = remember { dayFmt.format(java.util.Date(nowMs)) }

    // 한 주 월요일에서 월~일 7개 날짜 문자열
    fun weekDays(monday: Long): List<String> {
        val cal = Calendar.getInstance()
        return (0 until 7).map { i ->
            cal.timeInMillis = monday
            cal.add(Calendar.DAY_OF_YEAR, i)
            dayFmt.format(cal.time)
        }
    }
    val lastDays = remember(thisMonday) { weekDays(thisMonday - 7L * 24 * 60 * 60 * 1000) }
    val thisDays = remember(thisMonday) { weekDays(thisMonday) }

    var lastWeek by remember { mutableStateOf(List(7) { 0 }) }
    var thisWeek by remember { mutableStateOf(List(7) { 0 }) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(userId) {
        if (userId == null) { loading = false; return@LaunchedEffect }
        val local = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
        lastWeek = lastDays.map { local[it] ?: 0 }
        thisWeek = thisDays.map { local[it] ?: 0 }
        loading = false

        // 로컬에 없는 과거 날짜(오늘 이전)만 서버에서 보강
        val missing = (lastDays + thisDays).filter { it <= todayStr && !local.containsKey(it) }
        if (missing.isNotEmpty()) {
            val server = fetchDailyTotalsForDays(userId, missing)
            lastWeek = lastDays.map { local[it] ?: server[it] ?: 0 }
            thisWeek = thisDays.map { local[it] ?: server[it] ?: 0 }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // 상단 뒤로가기 + 타이틀
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                text = "주간 리포트",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                "${longRangeOf(thisMonday - 7L * 24 * 60 * 60 * 1000)} vs ${longRangeOf(thisMonday)}",
                fontSize = 13.sp, color = Color(0xFF888888),
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
            if (loading) {
                StatsLoadingSpinner(modifier = Modifier.height(240.dp))
            } else {
                WeeklyReportChart(lastWeek = lastWeek, thisWeek = thisWeek)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// 주(週) 월요일 ms → "yyyy.MM.dd ~ MM.dd" 범위 문자열
internal fun longRangeOf(monday: Long): String {
    val startFmt = SimpleDateFormat("yyyy.MM.dd", Locale.US)
    val endFmt = SimpleDateFormat("MM.dd", Locale.US)
    val c = Calendar.getInstance().apply { timeInMillis = monday }
    val s = startFmt.format(c.time)
    c.add(Calendar.DAY_OF_YEAR, 6)
    return "$s ~ ${endFmt.format(c.time)}"
}

// 지난주(회색)·이번주(하늘색) 묶음 막대그래프 + 가로 기준선 + 총합 비교 문구.
// counts 는 [월,화,수,목,금,토,일] 순서.
@Composable
internal fun WeeklyReportChart(lastWeek: List<Int>, thisWeek: List<Int>) {
    val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    val lastColor = Color(0xFFBDBDBD)  // 지난주 — 회색
    val thisColor = Color(0xFF4FC3F7)  // 이번주 — 하늘색

    val maxV = ((lastWeek + thisWeek).maxOrNull() ?: 0).coerceAtLeast(1)
    val step = niceGridStep(maxV)
    val lineCount = (Math.ceil(maxV.toDouble() / step).toInt()).coerceAtLeast(1)
    val scale = step * lineCount  // y축 최댓값 — 기준선이 딱 떨어지게
    val gutter = 36.dp            // 왼쪽 기준선 숫자 라벨 영역

    Column(modifier = Modifier.fillMaxWidth()) {
        // 범례
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(12.dp).background(lastColor, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(4.dp))
            Text("지난주", fontSize = 12.sp, color = Color(0xFF555555))
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(12.dp).background(thisColor, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(4.dp))
            Text("이번주", fontSize = 12.sp, color = Color(0xFF555555))
        }

        // 플롯 영역 — 가로 기준선(라벨 포함) 위에 묶음 막대
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(200.dp)
        ) {
            val plotH = maxHeight
            // 가로 기준선 + 숫자 라벨 (0 ~ scale, step 간격)
            for (i in 0..lineCount) {
                val v = i * step
                val y = plotH * (1f - v.toFloat() / scale)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = gutter)
                        .offset(y = y)
                        .height(1.dp)
                        .background(Color(0xFFEEEEEE))
                )
                Text(
                    text = "$v",
                    fontSize = 9.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(gutter)
                        .offset(y = (y - 6.dp).coerceAtLeast(0.dp))
                        .padding(end = 4.dp)
                )
            }

            // 요일별 묶음 막대 (월~일)
            Row(
                modifier = Modifier.fillMaxSize().padding(start = gutter),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in 0 until 7) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        ReportBar(lastWeek.getOrElse(i) { 0 }, scale, lastColor)
                        ReportBar(thisWeek.getOrElse(i) { 0 }, scale, thisColor)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 요일 라벨 — 막대와 같은 gutter/weight 로 정렬
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = gutter),
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

        Spacer(Modifier.height(14.dp))

        // 총합 비교 문구
        val lastSum = lastWeek.sum()
        val thisSum = thisWeek.sum()
        val diff = thisSum - lastSum
        val (msg, msgColor) = when {
            diff > 0 -> "지난주보다 숏폼을 ${diff}회 더 봤어요!" to Color(0xFFC62828)
            diff < 0 -> "지난주보다 숏폼을 ${-diff}회 덜 봤어요!" to Color(0xFF2E7D32)
            else -> "지난주와 숏폼 시청 횟수가 같아요!" to Color(0xFF1A1A1A)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFF7F7F7)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "지난주 ${lastSum}회 · 이번주 ${thisSum}회",
                    fontSize = 12.sp, color = Color(0xFF555555)
                )
                Spacer(Modifier.height(4.dp))
                Text(msg, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = msgColor)
            }
        }
    }
}

// 묶음 막대 하나 — count/scale 비율만큼 채움. count=0 이면 바닥에 얇은 회색 placeholder.
@Composable
internal fun RowScope.ReportBar(count: Int, scale: Int, color: Color) {
    val fraction = (count.toFloat() / scale).coerceIn(0f, 1f)
    Box(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (count == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(2.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            )
        }
    }
}
