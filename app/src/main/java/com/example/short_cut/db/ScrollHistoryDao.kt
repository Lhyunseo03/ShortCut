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

    // 통계 탭용 — 날짜별 스크롤 횟수 집계 (최근일자 먼저)
    // timestamp(ms) → 초 단위로 나눠 SQLite date() 에 넘기고 로컬 타임존 기준 날짜로 그룹핑
    @Query("""
        SELECT date(timestamp / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM scroll_history
        GROUP BY day
        ORDER BY day DESC
    """)
    suspend fun countByDay(): List<DailyCount>
}

// 일자별 스크롤 횟수 — countByDay() 반환 타입
// day 형식: "YYYY-MM-DD" (SQLite date() 출력)
data class DailyCount(
    val day: String,
    val count: Int
)