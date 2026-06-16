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

internal enum class StatsSubTab(val label: String) {
    DAILY("일간"), WEEKLY("주간"), MONTHLY("월간")
}

// 통계 화면 인메모리 캐시 — 탭 전환 시 stale 즉시 표시용. 앱 인스턴스 수명 동안만 유지.
// 일반 통계는 60초 TTL(짧게 둬서 최신성 확보), AI 분석 결과는 비싸므로 영구 보관(사용자가 "다시 시도"로만 갱신).
internal object StatsCache {
    private const val TTL_MS = 60_000L

    // userId 별 AI 분석 — 다른 사용자로 바꾸면 무효
    var aiAnalysis: Pair<String, String>? = null
    // userId 별 AI 추천 한도 (daily, hourly) — 분석과 함께 캐시
    var aiRecommendation: Pair<String, Pair<Int, Int>>? = null

    private data class Entry(val value: Any?, val at: Long)
    private val map = mutableMapOf<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            map.remove(key); return null
        }
        return e.value as T?
    }

    fun put(key: String, value: Any?) {
        map[key] = Entry(value, System.currentTimeMillis())
    }
}

@Composable
internal fun StatsTabContent(onApplyAiLimit: (Int, Int) -> Unit = { _, _ -> }) {
    var subTab by remember { mutableStateOf(StatsSubTab.DAILY) }
    // 일간 탭의 월 오프셋 (0=이번 달, -1=저번 달 ...). 달력 좌우 화살표로 조정.
    var dailyMonthOffset by remember { mutableStateOf(0) }
    // AI 분석 화면 진입 여부 — 서브탭 옆 작은 "AI분석 →" 버튼으로 토글
    var showAiAnalysis by remember { mutableStateOf(false) }

    BackHandler(enabled = showAiAnalysis) { showAiAnalysis = false }
    if (showAiAnalysis) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            AiAnalysisContent(
                onBack = { showAiAnalysis = false },
                onApplyLimit = onApplyAiLimit
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        // 서브탭 토글 row — 일간/주간/월간 + 오른쪽 끝에 작은 "AI분석 →" 텍스트 버튼
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatsSubTab.values().forEach { tab ->
                val selected = subTab == tab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { subTab = tab },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) Color(0xFF1A1A1A) else Color(0xFFF1F1F1)
                ) {
                    Text(
                        text = tab.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        color = if (selected) Color.White else Color(0xFF1A1A1A),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
            // 작은 텍스트 버튼 — AI 분석 화면 진입
            Text(
                text = "AI분석 →",
                modifier = Modifier
                    .clickable { showAiAnalysis = true }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1A1A1A)
            )
        }

        when (subTab) {
            StatsSubTab.DAILY -> StatsDaily(
                monthOffset = dailyMonthOffset,
                onMonthOffsetChange = { dailyMonthOffset = it }
            )
            StatsSubTab.WEEKLY -> StatsWeekly()
            StatsSubTab.MONTHLY -> StatsMonthly()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// AI 통계 분석 — 통계로 프롬프트를 만들어 서버(/analyze)로 보내고, AI 분석 결과만 인앱에 표시.
// 프롬프트 원문은 서버→AI 로만 전달되어 사용자에게 노출되지 않음(API 키는 서버 보관).
// ─────────────────────────────────────────────────────────────────────────

// 월간/주간 서버 요약 카드 — 총 스크롤·일평균·피크일을 한 줄로 표시
@Composable
internal fun StatsSummaryCard(
    title: String,
    totalScroll: Int?,
    avgPerDay: Int?,
    peakDate: String?,
    peakCount: Int?,
    loading: Boolean,
    errorMsg: String?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF555555))
            Spacer(Modifier.height(6.dp))
            when {
                loading -> Text("불러오는 중...", fontSize = 12.sp, color = Color(0xFF888888))
                errorMsg != null -> Text(errorMsg, fontSize = 12.sp, color = Color(0xFFC62828))
                else -> {
                    val peakStr = if (peakDate != null) "$peakDate (${peakCount ?: 0}회)" else "—"
                    Text(
                        "총 ${totalScroll ?: 0}회 · 일평균 ${avgPerDay ?: 0}회",
                        fontSize = 14.sp,
                        color = Color(0xFF1A1A1A),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "피크일 $peakStr",
                        fontSize = 12.sp,
                        color = Color(0xFF555555),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// 스크롤량 비율(0~1) → 히트맵 색. 0이면 옅은 회색, 많을수록 진한 빨강.
internal fun heatColor(intensity: Float): Color =
    if (intensity <= 0f) Color(0xFFF5F5F5)
    else lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * intensity.coerceIn(0f, 1f))

// 목표 달성 색 — 한도 이내(성공) 단색 초록, 초과(실패) 빨강(오버할수록 진하게).
// dailyLimit 가 0(미설정)이면 기존 히트맵으로 폴백. 두 번째 Boolean 은 흰 글씨가 어울리는 어두운 배경인지.
// [변경됨] 달성한 날 사이의 그라데이션 제거 — "스크롤이 많을수록 진해지는" 표시는 한도 초과 시에만 의미가 있음.
internal fun goalColor(count: Int, dailyLimit: Int, maxCount: Int, maxOverage: Int): Pair<Color, Boolean> {
    if (dailyLimit <= 0) {
        if (count == 0) return Color(0xFFF5F5F5) to false
        val intensity = (count.toFloat() / maxCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        return lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * intensity) to (intensity > 0.55f)
    }
    if (count == 0) return Color(0xFFEFF6F0) to false  // 활동 없음 = 자연 달성(아주 옅은 초록)
    if (count <= dailyLimit) return Color(0xFF66BB6A) to false  // 달성 — 카운트 크기와 무관하게 단색
    val frac = ((count - dailyLimit).toFloat() / maxOverage.coerceAtLeast(1)).coerceIn(0f, 1f)
    return lerp(Color(0xFFEF9A9A), Color(0xFFB71C1C), 0.15f + 0.85f * frac) to (frac > 0.4f)
}

// ── 앱별 스크롤 비율 도넛 그래프 ─────────────────────────────
// counts: Map<key, count> — key 는 패키지명 또는 플랫폼 키("youtube"/"instagram"/"tiktok").
// 데이터 없으면 안내문만 표시. 색: 유튜브=빨강, 인스타=파랑, 틱톡=초록, 기타=회색.
@Composable
internal fun AppShareDonut(counts: Map<String, Int>) {
    // 플랫폼 키(서버 byPlatform) 와 로컬 패키지명 둘 다 받을 수 있게 매핑
    val ytKeys = setOf("youtube", "com.google.android.youtube")
    val igKeys = setOf("instagram", "com.instagram.android")
    val ttKeys = setOf("tiktok", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

    val yt = counts.entries.filter { it.key in ytKeys }.sumOf { it.value }
    val ig = counts.entries.filter { it.key in igKeys }.sumOf { it.value }
    val tt = counts.entries.filter { it.key in ttKeys }.sumOf { it.value }
    val other = counts.entries
        .filter { it.key !in ytKeys && it.key !in igKeys && it.key !in ttKeys }
        .sumOf { it.value }
    val total = yt + ig + tt + other

    if (total == 0) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFF7F7F7)
        ) {
            Text(
                "앱별 데이터가 아직 없어요",
                modifier = Modifier.padding(16.dp),
                fontSize = 12.sp, color = Color(0xFF888888)
            )
        }
        return
    }

    // 표시 순서: 유튜브 → 인스타 → 틱톡 → 기타. 0인 항목은 건너뜀.
    // 색: YT=빨강 / IG=파랑 / TT=초록 (사용자 지정)
    data class Slice(val label: String, val count: Int, val color: Color)
    val slices = listOfNotNull(
        Slice("유튜브", yt, Color(0xFFE53935)).takeIf { yt > 0 },
        Slice("인스타", ig, Color(0xFF1E88E5)).takeIf { ig > 0 },
        Slice("틱톡", tt, Color(0xFF43A047)).takeIf { tt > 0 },
        Slice("기타", other, Color(0xFF9E9E9E)).takeIf { other > 0 }
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val stroke = 28.dp.toPx()
            val pad = stroke / 2f
            val rect = androidx.compose.ui.geometry.Rect(pad, pad, size.width - pad, size.height - pad)
            var startAngle = -90f
            slices.forEach { s ->
                val sweep = 360f * s.count / total
                drawArc(
                    color = s.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            slices.forEach { s ->
                val pct = s.count * 100 / total
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${s.label}  ${s.count}회 (${pct}%)",
                        fontSize = 13.sp, color = Color(0xFF1A1A1A)
                    )
                }
            }
        }
    }
}

// /logs/range 로 기간 내 모든 스크롤 로그를 받아 일별(local) 합산. 주간 통계 목록·상세 공통 소스.
// 로그 한 건당 scrollCount(묶음 수) 를 그 로그 timestamp 가 속한 날짜에 더함.
// 여러 날짜("yyyy-MM-dd")의 일별 총 스크롤을 /daily 로 받아 맵으로 반환. 동시 호출은 8개로 제한.
// 주간 통계의 그리드 합계와 상세 막대가 같은 소스(일별 /daily)에서 나오게 해 항상 일치시킴.
internal suspend fun fetchDailyTotalsForDays(userId: String, days: List<String>): Map<String, Int> = coroutineScope {
    val sem = Semaphore(8)
    days.map { d ->
        async { d to sem.withPermit { fetchDailyStats(userId, d)?.totalScroll ?: 0 } }
    }.awaitAll().toMap()
}
