package com.example.short_cut.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// @Dao — 이 인터페이스가 DB에 접근하는 함수들의 모음이라는 표시
// DAO (Data Access Object) — 테이블에 데이터를 넣고 꺼내는 창구 역할
@Dao
interface ScrollHistoryDao {

    // 스크롤 이벤트 발생 시 새 행 추가
    @Insert
    suspend fun insert(scroll: ScrollHistory)

    // 슬라이딩 윈도우 — 현재 시각 기준 최근 1시간 이내 스크롤 횟수 조회
    // hourly limit 초과 여부 판단에 사용
    @Query("SELECT COUNT(*) FROM scroll_history WHERE timestamp >= :oneHourAgo")
    suspend fun countLastHour(oneHourAgo: Long): Int

    // 오늘 자정 이후 스크롤 횟수 조회 — daily limit 초과 여부 판단에 사용
    @Query("SELECT COUNT(*) FROM scroll_history WHERE timestamp >= :startOfDay")
    suspend fun countToday(startOfDay: Long): Int

    // 1주일 이상 된 데이터 삭제 — 앱 시작 시 오래된 로그 정리용
    @Query("DELETE FROM scroll_history WHERE timestamp < :oneWeekAgo")
    suspend fun deleteOlderThan(oneWeekAgo: Long)

    // 사용자 탈퇴/로그아웃 시 로컬 스크롤 기록 전부 삭제
    @Query("DELETE FROM scroll_history")
    suspend fun deleteAll()

    // 통계 탭용 — 날짜별 스크롤 횟수 집계 (최근일자 먼저)
    // timestamp(ms) → 초 단위로 나눠 SQLite date() 에 넘기고 로컬 타임존 기준 날짜로 그룹핑
    @Query("""
        SELECT date(timestamp / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM scroll_history
        GROUP BY day
        ORDER BY day DESC
    """)
    suspend fun countByDay(): List<DailyCount>

    // 일간 통계 탭용 — 특정 하루의 시간대(0~23)별 스크롤 횟수 집계
    // [startOfDay, endOfDay) 윈도우 안의 행만 보고 시간(0~23) 으로 GROUP BY
    @Query("""
        SELECT CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS count
        FROM scroll_history
        WHERE timestamp >= :startOfDay AND timestamp < :endOfDay
        GROUP BY hour
        ORDER BY hour ASC
    """)
    suspend fun countByHourForDay(startOfDay: Long, endOfDay: Long): List<HourlyCount>

    // 도넛 그래프용 — 특정 기간의 앱(패키지)별 스크롤 횟수.
    // 일간이면 그 날, 월간이면 그 달을 [startMs, endMs) 로 넘김.
    @Query("""
        SELECT appPkg, COUNT(*) AS count
        FROM scroll_history
        WHERE timestamp >= :startMs AND timestamp < :endMs
        GROUP BY appPkg
    """)
    suspend fun countByAppForRange(startMs: Long, endMs: Long): List<AppCount>

    // 월간 분석 화면용 — 특정 기간의 일별 스크롤 횟수.
    // countByDay() 는 전체를 반환하지만 이건 범위로 제한
    @Query("""
        SELECT date(timestamp / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM scroll_history
        WHERE timestamp >= :startMs AND timestamp < :endMs
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun countByDayForRange(startMs: Long, endMs: Long): List<DailyCount>

    // 특정 기간 내 N번째 스크롤의 timestamp(ms). offset=N-1 이 N번째.
    // 한도 초과 시각 추적용 — 예: dailyLimit=100 이면 offset=100 으로 호출 → 101번째 스크롤 시각 = 한도 처음 넘긴 순간.
    @Query("""
        SELECT timestamp FROM scroll_history
        WHERE timestamp >= :startMs AND timestamp < :endMs
        ORDER BY timestamp ASC
        LIMIT 1 OFFSET :offset
    """)
    suspend fun nthScrollTimestamp(startMs: Long, endMs: Long, offset: Int): Long?

    // 특정 기간 안의 모든 스크롤 timestamp 시간순 — hourly 슬라이딩 윈도우 위반 시각 계산용.
    @Query("""
        SELECT timestamp FROM scroll_history
        WHERE timestamp >= :startMs AND timestamp < :endMs
        ORDER BY timestamp ASC
    """)
    suspend fun timestampsInRange(startMs: Long, endMs: Long): List<Long>
}

// 일자별 스크롤 횟수 — countByDay() 반환 타입
// day 형식: "YYYY-MM-DD" (SQLite date() 출력)
data class DailyCount(
    val day: String,
    val count: Int
)

// 시간대별 스크롤 횟수 — countByHourForDay() 반환 타입
// hour: 0~23 (로컬 타임존 기준)
data class HourlyCount(
    val hour: Int,
    val count: Int
)

// 앱(패키지)별 스크롤 횟수 — countByAppForRange() 반환 타입. 도넛 그래프용.
data class AppCount(
    val appPkg: String,
    val count: Int
)
