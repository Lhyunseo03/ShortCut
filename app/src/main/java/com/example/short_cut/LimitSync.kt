package com.example.short_cut

import android.content.Context
import android.util.Log
import com.example.short_cut.db.UserLimit
import com.example.short_cut.db.UserLimitDao
import org.json.JSONObject

// ── 한도(현재값 + 다음 주 예약) 서버 동기화 ──────────────────────────────────
// 서버 limits/{uid} 문서가 기준이다 (API_SPEC_multidevice §6-1, 10/18 서버 배포).
//   { hourlyLimit, dailyLimit, pendingHourlyLimit, pendingDailyLimit, pendingEffectiveAt }
//   - 이 값이 GET /sync 의 limits, GET /limits/{uid}, POST /limits/{uid} 응답에 같은 모양으로 온다.
//   - GET /limits · /sync 는 예약 시각이 지났으면 서버가 트랜잭션으로 승격해서 준다.
//
// 규칙
//   1) 설정에서 한도를 바꾸면 로컬 Room 에 저장 + 바로 POST /limits (예약 필드 포함). 다른 기기는 /sync 로 받는다.
//   2) 서버 응답의 limits 는 로컬을 덮어쓴다. 단, 이 기기에서 바꾼 값이 아직 서버에 못 올라갔으면(dirty)
//      덮지 않고 그 값을 다시 올린다 — 닉네임 동기화와 같은 패턴.
//   3) 로컬 승격(월요일 0시 지남) 전에 서버에 먼저 물어본다. 오래 꺼져 있던 기기가 옛 예약으로 승격하면서
//      다른 기기가 새로 건 예약을 지우는 일을 막는다 (서버 스펙의 경고).
private const val TAG = "LimitSync"
private const val PREFS = "short_cut_prefs"
private const val PK_LIMIT_DIRTY = "limitDirty"

private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

// 설정에서 저장한 직후 — 서버에 올라갈 때까지 서버 값으로 덮이지 않게 표시
internal fun markLimitDirty(context: Context) {
    prefs(context).edit().putBoolean(PK_LIMIT_DIRTY, true).apply()
}

// 서버 limits JSON → Room. 바뀐 게 있으면 true (호출부가 화면·서비스 변수를 다시 읽는다).
internal suspend fun applyServerLimits(context: Context, dao: UserLimitDao, userId: String, limits: JSONObject?): Boolean {
    if (limits == null || userId.isBlank() || userId == "unknown") return false
    if (prefs(context).getBoolean(PK_LIMIT_DIRTY, false)) {
        // 이 기기에서 바꾼 값이 먼저다 — 서버 값으로 덮지 않고 다시 올린다
        pushLocalLimits(context, dao, userId)
        return false
    }
    val h = limits.optInt("hourlyLimit", -1)
    val d = limits.optInt("dailyLimit", -1)
    if (h <= 0 || d <= 0) return false
    val ph = limits.intOrNull("pendingHourlyLimit")?.takeIf { it > 0 }
    val pd = limits.intOrNull("pendingDailyLimit")?.takeIf { it > 0 }
    val pat = limits.longOrNull("pendingEffectiveAt")?.takeIf { it > 0 && (ph != null || pd != null) }
    val local = dao.getLimit(userId)
    if (local != null && local.hourlyLimit == h && local.dailyLimit == d &&
        local.pendingHourlyLimit == ph && local.pendingDailyLimit == pd && local.pendingEffectiveAt == pat
    ) return false
    dao.insert(
        UserLimit(
            userId = userId, hourlyLimit = h, dailyLimit = d,
            pendingHourlyLimit = ph, pendingDailyLimit = pd, pendingEffectiveAt = pat,
            updatedAt = TrustedClock.now()
        )
    )
    Log.d(TAG, "서버 한도 반영 — h$h/d$d" + (if (pat != null) ", 예약 h${ph ?: "-"}/d${pd ?: "-"} @$pat" else ""))
    return true
}

// 로컬 Room 의 현재값 + 예약을 서버에 올린다 (POST /limits/{uid}). 예약이 없으면 null 을 보내 서버 예약도 지운다.
// 성공하면 dirty 해제 + 서버가 돌려준 limits(승격됐을 수 있음)를 반영.
internal suspend fun pushLocalLimits(context: Context, dao: UserLimitDao, userId: String): Boolean {
    if (userId.isBlank() || userId == "unknown") return false
    val l = dao.getLimit(userId) ?: return false
    val body = JSONObject()
        .put("hourlyLimit", l.hourlyLimit)
        .put("dailyLimit", l.dailyLimit)
        .put("pendingHourlyLimit", l.pendingHourlyLimit ?: JSONObject.NULL)
        .put("pendingDailyLimit", l.pendingDailyLimit ?: JSONObject.NULL)
        .put("pendingEffectiveAt", l.pendingEffectiveAt ?: JSONObject.NULL)
        .put("deviceId", DeviceId.get(context))
    val res = authedRequest("POST", "/limits/$userId", body)
    Log.d(TAG, "POST /limits h${l.hourlyLimit}/d${l.dailyLimit} 예약 h${l.pendingHourlyLimit ?: "-"}/d${l.pendingDailyLimit ?: "-"} → ${res.code}")
    if (!res.ok) return false
    prefs(context).edit().putBoolean(PK_LIMIT_DIRTY, false).apply()
    applyServerLimits(context, dao, userId, res.json?.optJSONObject("limits"))
    return true
}

// GET /limits/{uid} — 없으면(신규 사용자 404 · 오프라인) null
internal suspend fun fetchServerLimits(userId: String): JSONObject? {
    val res = authedRequest("GET", "/limits/$userId")
    return if (res.ok) res.json else null
}

// 예약 승격. 호출: 한도를 읽기 직전 (홈 탭 · 설정 · AI 분석 · 서비스 시작 · 자정 롤오버).
//   예약 시각이 지났으면 서버에 먼저 물어 서버가 승격한 값을 받는다. 서버에 못 물어보면(오프라인) 예전처럼
//   로컬에서 승격하고 그 값을 올린다 (연결되면 dirty 로 재시도).
//   예약이 없거나 아직 멀었으면 아무것도 안 한다 — 서버 요청 없음.
internal suspend fun promoteAndSyncLimit(context: Context, dao: UserLimitDao, userId: String) {
    if (userId.isBlank() || userId == "unknown") return
    val now = TrustedClock.now()
    val limit = dao.getLimit(userId) ?: return
    val due = limit.pendingEffectiveAt?.let { it <= now } == true
    if (!due) return

    val server = fetchServerLimits(userId)
    if (server != null) applyServerLimits(context, dao, userId, server)

    // 서버가 처리했으면 예약이 비어 있다. 남아 있으면(서버 실패 · 서버에 문서 없음) 로컬 승격 후 올린다.
    val after = dao.getLimit(userId) ?: return
    if (after.pendingEffectiveAt != null && after.pendingEffectiveAt <= now) {
        dao.promoteExpiredPending(now)
        Log.d(TAG, "로컬 승격 — h${after.pendingHourlyLimit ?: after.hourlyLimit}/d${after.pendingDailyLimit ?: after.dailyLimit}")
        markLimitDirty(context)
        pushLocalLimits(context, dao, userId)
    }
}
