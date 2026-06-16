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

// AI 분석 탭 본문 — 통계로 프롬프트를 만들어 서버(/analyze)로 보내고, 돌아온 분석 결과만 표시.
// 프롬프트 원문은 화면에 띄우지도 클립보드에 복사하지도 않음 → 사용자에게 노출되지 않음.
@Composable
internal fun AiAnalysisContent(
    onBack: () -> Unit,
    onApplyLimit: (Int, Int) -> Unit = { _, _ -> }   // (daily, hourly) — "적용하기" 시 한도 화면으로 전달
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val prefs = remember { context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE) }
    val userId = remember { prefs.getString("userId", null) }
    // 닉네임 — 저장된 값 우선, 없으면 Firebase displayName/이메일 prefix 폴백
    val nickname = remember {
        prefs.getString("nickname", null)?.takeIf { it.isNotBlank() }
            ?: FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: FirebaseAuth.getInstance().currentUser?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "회원"
    }

    // 같은 사용자의 캐시가 있으면 즉시 표시 — 두 번째 진입부터는 LLM 재호출 없음
    val cached = StatsCache.aiAnalysis?.takeIf { it.first == userId }?.second
    var analysis by remember { mutableStateOf<String?>(cached) }
    // AI 추천 한도 (daily, hourly) — 분석과 함께 파싱/계산해서 캐시
    val cachedRec = StatsCache.aiRecommendation?.takeIf { it.first == userId }?.second
    var recommendation by remember { mutableStateOf<Pair<Int, Int>?>(cachedRec) }
    var loading by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }  // "다시 시도" 트리거

    LaunchedEffect(userId, reloadKey) {
        // 캐시가 있고 강제 새로고침(reloadKey>0)이 아니면 네트워크 호출 생략
        if (reloadKey == 0 && analysis != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        analysis = null
        if (userId == null) {
            error = "로그인이 필요합니다. 다시 로그인 후 이용해 주세요."
            loading = false
            return@LaunchedEffect
        }
        try {
            // [변경됨] promoteExpiredPending → promoteAndSyncLimit (승격 시 서버 동기화 포함)
            promoteAndSyncLimit(db, userId)
            val limit = db.userLimitDao().getLimit(userId)
            val dailyLimit = limit?.dailyLimit ?: 0
            val hourlyLimit = limit?.hourlyLimit ?: 0

            // 최근 14일(오늘 포함) 날짜 — 최근→과거
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance()
            val days = (0 until 14).map { i ->
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(Calendar.DAY_OF_YEAR, -i)
                dayFmt.format(cal.time)
            }

            val serverByDay = fetchDailyStatsForDays(userId, days)
            // 서버가 비어도(오프라인 등) 최근 7일치 로컬 Room 으로 일별 합계 폴백
            val localTotals = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
            val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
            val month = fetchMonthlyStats(userId, monthKey)

            // 프롬프트는 만들어 서버로만 전송 — 사용자에겐 결과만 노출
            val prompt = buildStatsPrompt(nickname, dailyLimit, hourlyLimit, days, serverByDay, localTotals, month)
            val result = fetchAiAnalysis(userId, prompt)
            if (result != null) {
                analysis = result
                StatsCache.aiAnalysis = userId to result

                // AI 응답 끝의 "추천한도: daily=.., hourly=.." 파싱 → 없으면 통계 기반 폴백 계산
                fun totalOf(d: String) = serverByDay[d]?.totalScroll ?: localTotals[d] ?: 0
                val avg7 = days.take(7).sumOf { totalOf(it) } / 7
                val fallbackDaily = if (avg7 > 0) avg7 else (dailyLimit.takeIf { it > 0 } ?: 100)
                val peakHourly = days.mapNotNull { serverByDay[it]?.hourlyCounts?.maxOrNull() }.maxOrNull() ?: 0
                val fallbackHourly = if (peakHourly > 0) peakHourly else (hourlyLimit.takeIf { it > 0 } ?: 30)

                val (pd, ph) = parseRecommendedLimits(result)
                val recD = snap100(pd ?: fallbackDaily)   // daily 100단위
                val recH = snap10(ph ?: fallbackHourly)    // hourly 10단위
                recommendation = recD to recH
                StatsCache.aiRecommendation = userId to (recD to recH)
            } else {
                error = "AI 분석을 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
            }
        } catch (e: Exception) {
            error = "통계를 불러오지 못했습니다: ${e.message}"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 상단 바 — 뒤로가기 + 헤더
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                "${nickname}(님)의 통계분석",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
        }

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF1A1A1A)
                )
                Spacer(Modifier.width(10.dp))
                Text("AI가 분석 중이에요...", fontSize = 13.sp, color = Color(0xFF888888))
            }
            error != null -> Column {
                Text(error!!, fontSize = 13.sp, color = Color(0xFFC62828), lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { reloadKey++ },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A))
                ) {
                    Text("다시 시도", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            analysis != null -> {
                AiAnalysisBody(analysis = stripRecommendation(analysis!!))
                recommendation?.let { (d, h) ->
                    AiRecommendationCard(
                        daily = d,
                        hourly = h,
                        onApply = { onApplyLimit(d, h) }
                    )
                }
            }
        }
    }
}

// AI 추천 한도 카드 — 추천값 표시 + "적용하기"(누르면 설정 한도 화면으로 이동해 자동 입력)
@Composable
private fun AiRecommendationCard(daily: Int, hourly: Int, onApply: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFEAF4FF)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("AI 추천 한도", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0))
            Spacer(Modifier.height(6.dp))
            Text(
                "Daily ${daily}회 · Hourly ${hourly}회",
                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Text("적용하기", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "적용하기를 누르면 한도 설정 화면으로 이동해요. (변경은 다음 주 월요일부터 적용)",
                fontSize = 11.sp, color = Color(0xFF6B6B6B), modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

// AI 응답에서 "추천한도: daily=N, hourly=M" 의 N, M 추출 (형식이 조금 달라도 daily=/hourly= 패턴이면 인식)
private fun parseRecommendedLimits(text: String): Pair<Int?, Int?> {
    val d = Regex("(?i)daily\\s*=\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val h = Regex("(?i)hourly\\s*=\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return d to h
}

// 화면 표시용 — 추천한도 머신리더블 줄은 빼서 깔끔하게 보여줌(추천값은 카드로 따로 표시)
private fun stripRecommendation(text: String): String =
    text.lines()
        .filterNot { it.contains("추천한도") || Regex("(?i)daily\\s*=\\s*\\d+").containsMatchIn(it) }
        .joinToString("\n")
        .trim()

// daily 100단위 반올림(최소 100), hourly 10단위 반올림(최소 10)
private fun snap100(v: Int): Int = (((v + 50) / 100) * 100).coerceAtLeast(100)
private fun snap10(v: Int): Int = (((v + 5) / 10) * 10).coerceAtLeast(10)

// AI 분석 결과 본문 — 서버에서 받은 분석 텍스트만 표시(프롬프트 원문은 노출하지 않음).
@Composable
internal fun AiAnalysisBody(analysis: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F7)
    ) {
        SelectionContainer {
            Text(
                analysis,
                modifier = Modifier.padding(16.dp),
                fontSize = 14.sp,
                color = Color(0xFF1A1A1A),
                lineHeight = 21.sp
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

// 여러 날짜의 일간 통계를 동시에(최대 8개) 가져옴 — AI 프롬프트용. 실패한 날짜는 결과에서 빠짐.
internal suspend fun fetchDailyStatsForDays(
    userId: String,
    days: List<String>
): Map<String, DailyStatsRemote> = coroutineScope {
    val sem = Semaphore(8)
    days.map { d -> async { d to sem.withPermit { fetchDailyStats(userId, d) } } }
        .awaitAll()
        .mapNotNull { (d, s) -> s?.let { d to it } }
        .toMap()
}

// 통계를 사람이 읽기 쉬운 한국어 프롬프트로 변환. 패턴 분석 + 줄이기 조언을 함께 요청.
// 응답 분량은 의도적으로 짧게 — 사용자 피드백: "분석 너무 한바가지". 200~300자 이내 요약.
internal fun buildStatsPrompt(
    nickname: String,
    dailyLimit: Int,
    hourlyLimit: Int,
    days: List<String>,                         // yyyy-MM-dd, 최근→과거 14일
    serverByDay: Map<String, DailyStatsRemote>, // 서버 일간 통계 (있으면 우선)
    localTotals: Map<String, Int>,              // 로컬 Room 일별 합계 (서버 없을 때 폴백)
    month: MonthStatsRemote?
): String {
    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val label = SimpleDateFormat("M.d(E)", Locale.KOREAN)
    fun lbl(d: String): String = try { label.format(parse.parse(d)!!) } catch (e: Exception) { d }
    fun totalOf(d: String): Int = serverByDay[d]?.totalScroll ?: localTotals[d] ?: 0

    val recent7 = days.take(7)
    val sum7 = recent7.sumOf { totalOf(it) }
    val sum14 = days.sumOf { totalOf(it) }
    val avg7 = sum7 / 7
    val avg14 = sum14 / 14
    val stopSum = days.sumOf { serverByDay[it]?.stopCount ?: 0 }
    val ignoreSum = days.sumOf { serverByDay[it]?.ignoreCount ?: 0 }
    val exceedDays = days.count { dailyLimit > 0 && totalOf(it) > dailyLimit }

    val peakDay = days.maxByOrNull { totalOf(it) }
    val peakCount = peakDay?.let { totalOf(it) } ?: 0
    val hasPeak = peakDay != null && peakCount > 0
    val hourlyLine = peakDay?.let { serverByDay[it]?.hourlyCounts }
        ?.withIndex()
        ?.filter { it.value > 0 }
        ?.joinToString(", ") { "${it.index}시 ${it.value}회" }
        ?.takeIf { it.isNotEmpty() }
        ?: "기록 없음"

    // 일별 카운트를 한 줄에 콤마로 — 14줄을 1줄로 압축
    val dailyLine = days.joinToString(", ") {
        val warn = if (dailyLimit > 0 && totalOf(it) > dailyLimit) "⚠" else ""
        "${lbl(it)}:${totalOf(it)}$warn"
    }

    return buildString {
        appendLine("당신은 디지털 웰빙 코치입니다. 아래는 '${nickname}'님의 최근 14일 쇼츠(YouTube/Instagram/TikTok) 사용 통계입니다.")
        appendLine()
        appendLine("- 목표 한도: 하루 ${dailyLimit}회 / 1시간 ${hourlyLimit}회")
        appendLine("- 최근 7일 ${sum7}회(일평균 ${avg7}), 14일 ${sum14}회(일평균 ${avg14}), 한도 초과 ${exceedDays}일")
        if (hasPeak) appendLine("- 가장 많이 본 날: ${lbl(peakDay!!)} (${peakCount}회)")
        appendLine("- '그만보기' ${stopSum}회 / '무시하고 계속' ${ignoreSum}회")
        if (month != null) appendLine("- 이번 달 누적 ${month.totalScroll}회(일평균 ${month.avgScrollPerDay})")
        appendLine("- 일별: $dailyLine")
        if (hasPeak) appendLine("- 피크일 시간대: $hourlyLine")
        appendLine()
        appendLine("[요청] ${nickname}님께 친근한 말투로 한국어로 답해 주세요. 전체 250자 내외로 짧게 요약:")
        appendLine("1) 핵심 패턴 1~2문장")
        appendLine("2) 줄이기 팁 2가지(불릿)")
        appendLine("3) 다음 주 추천 한도 한 줄(사용 패턴을 분석해 점진적으로 줄이는 방향)")
        appendLine()
        appendLine("그리고 답변 맨 마지막 줄에 반드시 아래 형식 그대로 추천 한도를 출력하세요(daily는 100단위, hourly는 10단위 정수):")
        append("추천한도: daily=<정수>, hourly=<정수>")
    }
}
