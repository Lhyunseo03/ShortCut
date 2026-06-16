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

// POST /limits/:userId — 서버에 새 limit 동기화
// Firebase ID 토큰을 Authorization: Bearer 헤더로 전송 (서버 미들웨어가 검증)
// 서버는 pending 개념이 없으므로 "현재 실제로 적용 중인 값"만 보낸다.
// [변경됨] 예전 주석: "변경 시점 = 적용으로 본다" → 더 이상 변경 시점에 호출하지 않는다.
//   이제 이 함수는 (1) 신규 한도가 실제로 적용되는 순간(promoteAndSyncLimit) 에만 호출된다.
internal suspend fun pushLimitToServer(userId: String, hourlyLimit: Int, dailyLimit: Int): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (token == null) {
                Log.e("LimitSync", "토큰 없음 — 전송 중단")
                return@withContext false
            }
            val json = """{"hourlyLimit":$hourlyLimit,"dailyLimit":$dailyLimit}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/limits/$userId")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            // Railway 콜드 스타트가 최대 30초 → 타임아웃 30초로 늘림(기본 10초면 첫 요청 실패)
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            Log.d("LimitSync", "POST /limits 응답 — ${response.code} (success=$success)")
            response.close()
            success
        } catch (e: Exception) {
            Log.e("LimitSync", "전송 실패 — ${e.message}")
            false
        }
    }
}

// ── [신규] pending limit 승격 + 서버 동기화 ────────────────────────────────
// 기존 promoteExpiredPending() 호출을 이 함수로 감싼 것.
//
// [왜 만들었나]
//   한도를 바꾸면 로컬(Room)은 "다음 주 월요일부터" 적용(pending)인데,
//   예전 코드는 편집하는 순간 새 값을 서버로 즉시 push 했다.
//   그러면 서버 limits/{userId} 가 아직 적용도 안 된 미래 값을 갖게 되고,
//   GET /stats/daily 가 그 값을 읽어 "오늘(이번 주)" 통계를 새 한도로 계산해
//   화면에 잘못된 한도가 표시되는 버그가 있었다.
//
// [어떻게 고치나]
//   새 값은 "실제로 적용되는 순간"(= pending 이 승격되는 시점)에만 서버로 보낸다.
//   이 함수는 승격 직전에 승격 대상이 있는지 확인하고, 승격이 일어났다면
//   그때 비로소 pushLimitToServer() 로 새 값을 서버에 반영한다.
//   승격할 게 없으면 기존과 똑같이 로컬 정렬만 하고 서버는 옛 값을 유지한다.
internal suspend fun promoteAndSyncLimit(db: AppDatabase, userId: String) {
    val now = System.currentTimeMillis()
    val limit = db.userLimitDao().getLimit(userId) ?: return

    // 승격 대상인지 판단 — pendingEffectiveAt 가 설정돼 있고 이미 그 시각을 지났는가
    val effectiveAt = limit.pendingEffectiveAt
    val willPromote = effectiveAt != null && effectiveAt <= now

    // 승격 후 실제로 적용될 값(= 서버에 보낼 값)을 승격 전에 미리 계산
    val promotedHourly = limit.pendingHourlyLimit ?: limit.hourlyLimit
    val promotedDaily  = limit.pendingDailyLimit  ?: limit.dailyLimit

    // 로컬 승격 — 기존 동작 그대로 (pending → 현재 값)
    db.userLimitDao().promoteExpiredPending(now)

    // 승격이 실제로 일어난 경우에만 서버 동기화 — 이 순간이 "새 한도가 적용된 시점"
    if (willPromote) {
        pushLimitToServer(userId, promotedHourly, promotedDaily)
    }
}

// ── 서버 통계/limit API 공통 ───────────────────────────────────────────
internal const val SERVER_BASE_URL = "https://short-cut-server-production.up.railway.app"

// 일간 통계 — hourlyCounts 는 0..23 시간대별 카운트 배열 (서버 hourlyGraph 를 24 길이로 펴서 채움)
// hourlyLimit/byPlatform/violations 은 서버 응답에 없으면 null (점진 확장).
internal data class DailyStatsRemote(
    val totalScroll: Int,
    val dailyLimit: Int,
    val stopCount: Int,
    val ignoreCount: Int,
    val hourlyCounts: IntArray,
    val hourlyLimit: Int? = null,                       // 그 날의 시간별 목표
    val byPlatform: Map<String, Int>? = null,           // 플랫폼별 집계 (youtube/instagram/tiktok)
    val violations: List<ViolationRecord>? = null       // 한도 초과 기록(시각 포함)
)

internal data class MonthStatsRemote(
    val totalScroll: Int,
    val avgScrollPerDay: Int,
    val peakDate: String?,
    val peakCount: Int?,
    val stopCount: Int? = null,                         // 그 달 누적 '그만보기' 횟수
    val ignoreCount: Int? = null,                       // 그 달 누적 '무시' 횟수
    val byPlatform: Map<String, Int>? = null            // 플랫폼별 집계
)

// 한도 초과 기록(시각/타입/응답) — 일간 통계의 violations 항목.
internal data class ViolationRecord(
    val timestamp: Long,
    val limitType: String,   // "hourly" / "daily"
    val action: String,      // "stop" / "ignore"
    val hour: Int            // 0..23 — 그날 몇 시 위반인지
)

// Firebase ID 토큰을 Bearer 헤더로 붙여 GET 요청 → JSON 파싱. 실패 시 null.
internal suspend fun authedGetJson(url: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
    try {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: return@withContext null
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        // Railway 콜드 스타트가 최대 30초 → 타임아웃 30초로 늘림(기본 10초면 통계 GET 다 실패해 0회로 표시됨)
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string()
        val ok = resp.isSuccessful
        resp.close()
        if (ok && body != null) org.json.JSONObject(body) else {
            Log.w("StatsApi", "GET $url → ${resp.code}")
            null
        }
    } catch (e: Exception) {
        Log.e("StatsApi", "GET $url 실패 — ${e.message}")
        null
    }
}

// GET /limits/:userId — 다른 기기에서 설정한 한도값 동기화에 사용
internal suspend fun fetchLimitsFromServer(userId: String): Pair<Int, Int>? {
    val json = authedGetJson("$SERVER_BASE_URL/limits/$userId") ?: return null
    val h = json.optInt("hourlyLimit", -1)
    val d = json.optInt("dailyLimit", -1)
    return if (h > 0 && d > 0) h to d else null
}

internal suspend fun fetchDailyStats(userId: String, date: String): DailyStatsRemote? {
    val json = authedGetJson("$SERVER_BASE_URL/stats/$userId/daily?date=$date") ?: return null
    val hourly = IntArray(24)
    json.optJSONArray("hourlyGraph")?.let { arr ->
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val hr = obj.optInt("hour", -1)
            if (hr in 0..23) hourly[hr] = obj.optInt("scrollCount", 0)
        }
    }
    // 플랫폼별 집계 — 서버가 byPlatform: {youtube, instagram, tiktok} 으로 내려주면 사용
    val byPlatform = json.optJSONObject("byPlatform")?.let { o ->
        listOf("youtube", "instagram", "tiktok")
            .associateWith { o.optInt(it, 0) }
            .filterValues { it > 0 }
            .takeIf { it.isNotEmpty() }
    }
    // 위반 기록 — 서버가 내려주면 사용 (없어도 hourlyLimit + hourlyCounts 로 시간별 초과 시간 계산 가능)
    val violations = json.optJSONArray("violations")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ViolationRecord(
                timestamp = o.optLong("timestamp"),
                limitType = o.optString("limitType", ""),
                action = o.optString("action", ""),
                hour = o.optInt("hour", -1).takeIf { it in 0..23 } ?: 0
            )
        }
    }
    return DailyStatsRemote(
        totalScroll = json.optInt("totalScroll", 0),
        dailyLimit = json.optInt("dailyLimit", 0),
        stopCount = json.optInt("stopCount", 0),
        ignoreCount = json.optInt("ignoreCount", 0),
        hourlyCounts = hourly,
        hourlyLimit = json.optInt("hourlyLimit", -1).takeIf { it > 0 },
        byPlatform = byPlatform,
        violations = violations
    )
}

internal suspend fun fetchMonthlyStats(userId: String, month: String): MonthStatsRemote? {
    val json = authedGetJson("$SERVER_BASE_URL/stats/$userId/monthly?date=$month") ?: return null
    val peak = json.optJSONObject("peakDay")
    val byPlatform = json.optJSONObject("byPlatform")?.let { o ->
        listOf("youtube", "instagram", "tiktok")
            .associateWith { o.optInt(it, 0) }
            .filterValues { it > 0 }
            .takeIf { it.isNotEmpty() }
    }
    return MonthStatsRemote(
        totalScroll = json.optInt("totalScroll", 0),
        avgScrollPerDay = json.optInt("avgScrollPerDay", 0),
        peakDate = peak?.optString("date")?.takeIf { it.isNotEmpty() },
        peakCount = peak?.optInt("scrollCount"),
        stopCount = json.optInt("stopCount", -1).takeIf { it >= 0 },
        ignoreCount = json.optInt("ignoreCount", -1).takeIf { it >= 0 },
        byPlatform = byPlatform
    )
}

// POST /analyze — 통계 프롬프트를 서버로 보내 AI 분석 결과(텍스트)만 돌려받음.
// 프롬프트는 서버→AI 로만 전달되고 사용자에겐 결과만 노출됨. AI API 키는 서버가 보관.
// 서버 응답 형식: { "analysis": "..." }
internal suspend fun fetchAiAnalysis(userId: String, prompt: String): String? = withContext(Dispatchers.IO) {
    try {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: return@withContext null
        // JSONObject 로 직렬화해 프롬프트의 줄바꿈/따옴표가 안전하게 이스케이프되도록 함
        val payload = org.json.JSONObject()
            .put("userId", userId)
            .put("prompt", prompt)
            .toString()
        val req = Request.Builder()
            .url("$SERVER_BASE_URL/analyze")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        // Gemini 응답이 기본 10초를 넘길 수 있어 타임아웃을 넉넉히 — 기본값이면 앱이 먼저 끊어 "다시 시도" 에러
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string()
        val ok = resp.isSuccessful
        val code = resp.code
        resp.close()
        if (ok && body != null) {
            org.json.JSONObject(body).optString("analysis").takeIf { it.isNotBlank() }
        } else {
            Log.w("StatsApi", "POST /analyze → $code")
            null
        }
    } catch (e: Exception) {
        Log.e("StatsApi", "POST /analyze 실패 — ${e.message}")
        null
    }
}

// DELETE /users/:userId — 본인 계정 탈퇴
// 서버가 Firestore 데이터 전체 + Firebase Auth 계정을 한 번에 삭제
internal suspend fun deleteAccountOnServer(userId: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (token == null) {
                Log.e("AccountDelete", "토큰 없음 — 전송 중단")
                return@withContext false
            }
            val request = Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/users/$userId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            // Railway 콜드 스타트 대비 — 타임아웃 30초
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            Log.d("AccountDelete", "DELETE /users 응답 — ${response.code} (success=$success)")
            response.close()
            success
        } catch (e: Exception) {
            Log.e("AccountDelete", "전송 실패 — ${e.message}")
            false
        }
    }
}
