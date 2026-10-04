package com.example.short_cut

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.short_cut.services.ShortCutMessagingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── 서버 호출 공통 (다중 기기 · 그룹 API) ─────────────────────────────────
// authedGetJson 은 실패 시 null 만 돌려줘서 409/429 같은 "이유 있는 거절"을 화면에 못 보여 준다.
// 여기서는 HTTP 코드와 서버의 { "error": "..." } 문구를 함께 돌려준다.
internal data class ApiResult(val code: Int, val json: JSONObject?) {
    val ok: Boolean get() = code in 200..299
    // 서버가 준 거절 사유. 없으면 null — 호출부가 기본 문구를 쓴다.
    val error: String? get() = json?.optString("error")?.takeIf { it.isNotBlank() }
}

// Railway 콜드 스타트(최대 30초) 대비. 요청마다 새로 만들지 않고 하나를 공유한다.
private val apiClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

// method: GET / POST / DELETE. path 는 "/groups" 처럼 슬래시로 시작.
// code = 0 → 요청 자체가 실패(네트워크 오류 · 미로그인).
internal suspend fun authedRequest(method: String, path: String, body: JSONObject? = null): ApiResult =
    withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
                ?: return@withContext ApiResult(0, null)
            val reqBody = (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
            val builder = Request.Builder()
                .url("$SERVER_BASE_URL$path")
                .addHeader("Authorization", "Bearer $token")
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(reqBody)
                "DELETE" -> if (body == null) builder.delete() else builder.delete(reqBody)
                else -> error("지원하지 않는 method: $method")
            }
            apiClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string()
                val json = try { if (text.isNullOrBlank()) null else JSONObject(text) } catch (_: Exception) { null }
                if (!resp.isSuccessful) Log.w("Api", "$method $path → ${resp.code} ${json?.optString("error") ?: ""}")
                ApiResult(resp.code, json)
            }
        } catch (e: Exception) {
            Log.e("Api", "$method $path 실패 — ${e.message}")
            ApiResult(0, null)
        }
    }

// 설정 > 닉네임 에 저장된 값. 없으면 구글 계정 이름 → 이메일 앞부분. (그룹 순위표 · AI 분석에 쓰는 이름)
internal fun localNickname(context: Context): String {
    val prefs = context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
    val user = FirebaseAuth.getInstance().currentUser
    return prefs.getString("nickname", null)?.takeIf { it.isNotBlank() }
        ?: user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.email?.substringBefore('@')
        ?: ""
}

// 오늘 카운트 — 이 기기 로컬(Room) 과 접근성 서비스가 /sync 로 받아 둔 계정 전체 값 중 큰 쪽.
// 홈 탭 · 그룹 목록 · 그룹 상세가 같은 숫자를 보여 주도록 한 곳에서 계산한다.
internal suspend fun accountTodayCount(context: Context): Int {
    val app = context.applicationContext
    val dayStart = startOfDayMs()
    val prefs = app.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
    val synced = if (prefs.getLong(com.example.short_cut.services.ShortCutAccessibilityService.PK_SYNCED_DAILY_DAY, -1L) == dayStart)
        prefs.getInt(com.example.short_cut.services.ShortCutAccessibilityService.PK_SYNCED_DAILY_COUNT, 0) else 0
    return maxOf(com.example.short_cut.db.ScrollCountRepository.get(app).dailyCount(dayStart), synced)
}

// ── 모드(일반/하드) 동기화 ───────────────────────────────────────────────
// 계정 단위 설정 — 한 기기에서 바꾸면 다른 기기도 따라간다.
//   보내기: POST /mode { deviceId, mode: "normal" | "hard" }   → 서버가 users/{uid}.appMode 저장 + 다른 기기에 COUNT_UPDATED
//   받기  : GET /sync 응답의 appMode
// (서버에 아직 없으면 POST 는 404, /sync 에는 appMode 가 없어 아무 일도 일어나지 않는다 — 기기별 설정으로 동작)
internal const val PK_APP_MODE = "appMode"
private const val PK_APP_MODE_CHANGED_AT = "appModeChangedAt"

// 이 기기에서 모드를 바꿨을 때: 로컬 저장 + 서버 업로드
internal suspend fun setAppMode(context: Context, mode: String) {
    val app = context.applicationContext
    app.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).edit()
        .putString(PK_APP_MODE, mode)
        .putLong(PK_APP_MODE_CHANGED_AT, System.currentTimeMillis())
        .apply()
    val res = authedRequest("POST", "/mode", JSONObject().put("deviceId", DeviceId.get(app)).put("mode", mode))
    Log.d("DeviceApi", "POST /mode $mode → ${res.code}")
}

// /sync 응답의 appMode 를 로컬에 반영. 바뀌었으면 true.
// 방금 이 기기에서 바꾼 직후(15초)에는 무시 — 업로드가 서버에 닿기 전의 옛 값으로 되돌아가지 않게.
internal fun applyServerAppMode(context: Context, syncJson: JSONObject): Boolean {
    val mode = syncJson.optString("appMode")
    if (mode != "normal" && mode != "hard") return false
    val prefs = context.applicationContext.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
    if (System.currentTimeMillis() - prefs.getLong(PK_APP_MODE_CHANGED_AT, 0L) < 15_000L) return false
    if (prefs.getString(PK_APP_MODE, "normal") == mode) return false
    prefs.edit().putString(PK_APP_MODE, mode).apply()
    Log.d("DeviceApi", "다른 기기에서 모드 변경 → $mode 적용")
    return true
}

// ── 기기 등록 (API 스펙 §1) ──────────────────────────────────────────────
internal const val PK_DEVICE_COUNT = "deviceCount"   // 이 계정의 로그인 상태 기기 수 — 2 이상이면 1분 배치(D2)

// POST /devices/register — 로그인 성공 시 · 앱 시작 시 · FCM 토큰 갱신 시 호출. 같은 deviceId 면 서버가 갱신한다.
// 응답의 deviceCount 를 prefs 에 저장 → 접근성 서비스가 배치 주기를 정할 때 읽는다.
// 미로그인이거나 실패하면 아무것도 바꾸지 않는다(다음 앱 시작 때 다시 시도됨).
internal suspend fun registerDevice(context: Context): Boolean {
    val app = context.applicationContext
    if (FirebaseAuth.getInstance().currentUser == null) return false

    // 저장된 토큰이 없으면(첫 실행 직후 등) FCM 에 직접 요청. 실패해도 등록은 진행 — fcmToken 은 선택 필드.
    val fcmToken = ShortCutMessagingService.savedToken(app) ?: try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: Exception) {
        Log.w("DeviceApi", "FCM 토큰 조회 실패 — ${e.message}")
        null
    }
    val permissionsOk = isAccessibilityServiceEnabled(app) && hasUsageStatsPermission(app) && hasOverlayPermission(app)

    val body = JSONObject()
        .put("deviceId", DeviceId.get(app))
        .put("deviceName", Build.MODEL ?: "Android")
        .put("permissionsOk", permissionsOk)
    if (fcmToken != null) body.put("fcmToken", fcmToken)
    // 닉네임도 함께 — 서버가 users/{uid}.nickname 으로 저장해 그룹 순위표에 쓰도록 (앱 시작 · 닉네임 저장 시 갱신)
    localNickname(app).takeIf { it.isNotBlank() }?.let { body.put("nickname", it) }

    val res = authedRequest("POST", "/devices/register", body)
    if (!res.ok) return false
    val count = res.json?.optInt("deviceCount", 1) ?: 1
    app.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
        .edit().putInt(PK_DEVICE_COUNT, count).apply()
    Log.d("DeviceApi", "기기 등록 완료 — deviceCount=$count, fcmToken=${fcmToken != null}")
    return true
}
