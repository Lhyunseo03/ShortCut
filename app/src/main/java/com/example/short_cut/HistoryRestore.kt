package com.example.short_cut

import android.content.Context
import android.util.Log
import com.example.short_cut.db.ScrollCountRepository
import com.example.short_cut.db.ScrollHistory
import java.text.SimpleDateFormat
import java.util.Locale

// 재설치(또는 새 기기 첫 설치) 후 폰 안 스크롤 기록이 비어 있을 때, 서버의 최근 7일 기록으로 다시 채운다.
//
// 왜: 홈 탭과 통계는 폰 안 기록(Room)을 우선으로 쓴다. 재설치로 Room 이 비면 홈 탭은 0 부터 다시 세고,
//     통계는 새로 스크롤하는 순간 서버 값을 가리고 새 기록만 보여 줬다.
//     서버 값을 먼저 폰 안에 채워 두면 거기서부터 이어서 쌓이므로 두 화면 모두 끊기지 않는다.
//
// 한계: 서버는 "시간대별 횟수"와 "하루 플랫폼별 횟수"만 준다. 그래서 복원된 기록은
//     - 시각이 그 시간대 안에 고르게 펴진 값이고(분 단위는 실제와 다름)
//     - 어느 앱인지는 그날의 플랫폼 비율대로 나눠 붙인 값이다.
//     합계 · 시간대별 그래프 · 앱별 비율은 서버와 일치한다.
internal object HistoryRestore {
    private const val TAG = "HistoryRestore"
    private const val PREFS = "short_cut_prefs"
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAYS = 7   // Room 보관 기간과 동일

    private val PLATFORM_PKG = mapOf(
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically"
    )

    // 복원했으면 true. 이 설치에서 이 계정으로 한 번만 시도한다(성공 · 해당 없음일 때 완료 표시, 네트워크 실패면 다음에 재시도).
    suspend fun restoreIfNeeded(context: Context, userId: String): Boolean {
        if (userId.isBlank() || userId == "unknown") return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doneKey = "historyRestored_$userId"
        if (prefs.getBoolean(doneKey, false)) return false

        val repo = ScrollCountRepository.get(context)
        // 폰 안에 기록이 이미 있으면(업데이트 설치 등) 건드리지 않는다 — 서버 값과 겹쳐 두 배가 되면 안 되므로.
        if (!repo.isEmpty()) {
            prefs.edit().putBoolean(doneKey, true).apply()
            return false
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayStarts = (0 until DAYS).map { startOfDayMs(-it) }
        val dayKeys = dayStarts.map { fmt.format(java.util.Date(it)) }
        val byDay = fetchDailyStatsForDays(userId, dayKeys)
        if (byDay.isEmpty()) {
            // 서버가 아무 날짜도 못 줬다 — 네트워크 실패일 수 있으니 완료 표시하지 않고 다음 시작 때 재시도
            Log.w(TAG, "서버 기록을 받지 못함 — 다음에 재시도")
            return false
        }

        val now = System.currentTimeMillis()
        val rows = ArrayList<ScrollHistory>()
        dayStarts.forEachIndexed { i, dayStart ->
            val stats = byDay[dayKeys[i]] ?: return@forEachIndexed
            rows += buildRows(dayStart, stats, now)
        }

        // 받아 오는 사이에 사용자가 스크롤을 시작했으면 그만둔다 (겹침 방지)
        if (rows.isNotEmpty() && repo.isEmpty()) repo.restore(rows)
        prefs.edit().putBoolean(doneKey, true).apply()
        Log.d(TAG, "서버 기록 복원 — ${rows.size}회 (${byDay.size}일치)")
        return rows.isNotEmpty()
    }

    private fun buildRows(dayStart: Long, stats: DailyStatsRemote, now: Long): List<ScrollHistory> {
        // 그날 플랫폼별 남은 횟수 — 기록 하나를 만들 때마다 가장 많이 남은 플랫폼에 붙인다 (비율대로 고르게 섞임)
        val remaining = (stats.byPlatform ?: emptyMap()).toMutableMap()
        val out = ArrayList<ScrollHistory>()
        for (hour in 0..23) {
            val n = stats.hourlyCounts.getOrElse(hour) { 0 }
            if (n <= 0) continue
            val hourStart = dayStart + hour * HOUR_MS
            if (hourStart > now) continue
            // 최근 1시간은 복원하지 않는다 — 재설치로 deviceId 가 새로 생겨 서버는 그 전 스크롤을 전부 "다른 기기" 로 보고
            // /sync 의 otherDevicesLastHour 에 담아 준다. 여기서도 넣으면 시간당 카운트가 두 배로 잡혀 팝업이 일찍 뜬다.
            if (hourStart + HOUR_MS > now - HOUR_MS) {
                val cutoff = now - HOUR_MS
                if (hourStart >= cutoff) continue
                // 시간대가 경계에 걸치면 cutoff 이전 비율만큼만 복원
                val keep = ((cutoff - hourStart).toDouble() / HOUR_MS * n).toInt()
                if (keep <= 0) continue
                for (k in 0 until keep) {
                    val ts = hourStart + ((cutoff - hourStart) * (2L * k + 1L)) / (2L * keep)
                    val platform = remaining.filterValues { it > 0 }.maxByOrNull { it.value }?.key
                    if (platform != null) remaining[platform] = remaining.getValue(platform) - 1
                    out += ScrollHistory(appPkg = PLATFORM_PKG[platform] ?: "unknown", timestamp = ts)
                }
                continue
            }
            // 지금 진행 중인 시간대는 "현재 시각"까지만 — 미래 시각의 기록이 생기면 카운트 조회에서 빠진다
            val span = minOf(HOUR_MS, now - hourStart).coerceAtLeast(1L)
            for (k in 0 until n) {
                val ts = hourStart + (span * (2L * k + 1L)) / (2L * n)   // 구간 안에 고르게
                val platform = remaining.filterValues { it > 0 }.maxByOrNull { it.value }?.key
                if (platform != null) remaining[platform] = remaining.getValue(platform) - 1
                out += ScrollHistory(appPkg = PLATFORM_PKG[platform] ?: "unknown", timestamp = ts)
            }
        }
        return out
    }
}
