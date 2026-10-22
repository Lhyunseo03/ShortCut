package com.example.short_cut

import android.content.Context
import android.os.SystemClock
import android.util.Log

// ── 신뢰할 수 있는 현재 시각 ─────────────────────────────────────────────
// 폰 설정에서 날짜·시간을 바꾸면 System.currentTimeMillis() 가 같이 바뀌어서
//   - 시계를 앞으로 돌리면 최근 1시간 창에서 기록이 빠져 시간당 한도를 피하고, 5분 차단도 바로 풀리고
//   - 날짜를 바꾸면 자정 롤오버로 일간 카운트가 0 이 되고, 서버에도 가짜 날짜로 기록이 올라갔다.
// 그래서 "서버 시각 + 그 뒤로 폰이 켜진 채 흐른 시간(elapsedRealtime)" 으로 현재 시각을 계산한다.
// elapsedRealtime 은 설정에서 시계를 바꿔도 변하지 않는 단조 시계라, 서버와 한 번만 맞추면 그 뒤로는 영향이 없다.
//   - 맞추는 시점: 기기 등록 응답 · /sync 응답의 serverTime (앱을 켤 때마다, 쇼츠를 보는 동안 1분마다)
//   - 재부팅하면 elapsedRealtime 이 0 부터 다시 시작 → 저장해 둔 값은 버리고 다시 맞출 때까지 폰 시계를 쓴다.
//   - 아직 한 번도 못 맞췄으면(오프라인 첫 실행) 폰 시계를 쓴다.
object TrustedClock {
    private const val PREFS = "short_cut_prefs"
    private const val PK_SERVER_AT = "trustedServerAt"
    private const val PK_ELAPSED_AT = "trustedElapsedAt"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000   // 일주일 넘게 안 맞췄으면 버림

    @Volatile private var serverAt = 0L
    @Volatile private var elapsedAt = 0L
    @Volatile private var loaded = false

    // 프로세스 시작 후 첫 사용 전에 한 번 — 저장해 둔 기준점을 복원 (재부팅이면 무시)
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = prefs.getLong(PK_SERVER_AT, 0L)
        val e = prefs.getLong(PK_ELAPSED_AT, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (s > 0 && e > 0 && nowElapsed >= e && nowElapsed - e < MAX_AGE_MS) {
            serverAt = s; elapsedAt = e
            Log.d("TrustedClock", "기준점 복원 — 서버와의 차이 ${skewMs() / 1000}초")
        } else if (s > 0) {
            Log.d("TrustedClock", "기준점 폐기 (재부팅 또는 오래됨) — 다시 맞출 때까지 폰 시계 사용")
        }
    }

    // 서버 응답의 serverTime(Unix ms) 으로 기준점 갱신
    fun sync(context: Context, serverTimeMs: Long) {
        if (serverTimeMs <= 0L) return
        serverAt = serverTimeMs
        elapsedAt = SystemClock.elapsedRealtime()
        loaded = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(PK_SERVER_AT, serverAt).putLong(PK_ELAPSED_AT, elapsedAt).apply()
        val skew = skewMs()
        if (kotlin.math.abs(skew) > 60_000L) Log.w("TrustedClock", "폰 시계가 서버와 ${skew / 1000}초 다름 — 서버 기준으로 계산")
    }

    // 지금 시각(Unix ms). 서버와 맞춘 적이 있으면 그 기준, 없으면 폰 시계.
    fun now(): Long =
        if (serverAt > 0L) serverAt + (SystemClock.elapsedRealtime() - elapsedAt) else System.currentTimeMillis()

    fun isTrusted(): Boolean = serverAt > 0L

    // 신뢰 시각 − 폰 시계. 양수면 폰 시계가 느린 것.
    fun skewMs(): Long = now() - System.currentTimeMillis()
}
