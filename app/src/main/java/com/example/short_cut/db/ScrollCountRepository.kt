package com.example.short_cut.db

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// 스크롤 카운트(일간 · 최근 1시간)의 Room 읽기/쓰기를 한 곳으로 모은 창구.
//
// 접근성 서비스 · 홈 화면 · 그룹 화면 · 설정(탈퇴)이 각자 DAO 를 직접 부르던 것을 여기로 통일한다.
// 모든 호출은 스레드 1개짜리 디스패처에서 순서대로 실행되므로,
// "스크롤 insert" 와 "카운트 조회", "전체 삭제" 가 동시에 겹쳐 서로 어긋난 값을 보는 일이 없다.
// (3주차에 서버 /sync 값을 반영하는 쓰기가 추가돼도 같은 줄에 서게 하려는 목적)
//
// 통계 화면의 집계 조회(countByDay 등)는 카운트를 바꾸지 않는 읽기 전용 리포트라 DAO 를 그대로 쓴다.
class ScrollCountRepository private constructor(private val dao: ScrollHistoryDao) {

    companion object {
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS

        // 앱 전체에서 디스패처도 하나 — 인스턴스가 여러 개 생겨도 직렬화가 깨지지 않게 companion 에 둔다.
        private val serial = Executors.newSingleThreadExecutor { r ->
            Thread(r, "scroll-count-repo").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        @Volatile private var INSTANCE: ScrollCountRepository? = null

        fun get(context: Context): ScrollCountRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScrollCountRepository(
                    AppDatabase.getDatabase(context.applicationContext).scrollHistoryDao()
                ).also { INSTANCE = it }
            }
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────

    // 스크롤 1회 기록
    suspend fun recordScroll(appPkg: String, timestamp: Long) = withContext(serial) {
        dao.insert(ScrollHistory(appPkg = appPkg, timestamp = timestamp))
    }

    // timestamp < beforeMs 인 기록 삭제 (1주일 지난 기록 정리 등)
    suspend fun deleteOlderThan(beforeMs: Long) = withContext(serial) {
        dao.deleteOlderThan(beforeMs)
    }

    // 전체 삭제 — 계정 탈퇴, 다른 계정으로 바뀐 것을 감지했을 때
    suspend fun deleteAll() = withContext(serial) {
        dao.deleteAll()
    }

    // 서버에서 받아 온 기록으로 다시 채우기 (재설치 후 1회) — HistoryRestore 가 호출
    suspend fun restore(rows: List<ScrollHistory>) = withContext(serial) {
        dao.insertAll(rows)
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    // 폰 안에 스크롤 기록이 하나도 없는지
    suspend fun isEmpty(): Boolean = withContext(serial) {
        dao.countAll() == 0
    }

    // [startOfDayMs, startOfDayMs + 24h) 스크롤 횟수 — 일간 한도 판단용
    suspend fun dailyCount(startOfDayMs: Long): Int = withContext(serial) {
        dao.countToday(startOfDayMs, startOfDayMs + DAY_MS)
    }

    // [now - 1h, now] 스크롤 횟수(슬라이딩 윈도우) — 시간당 한도 판단용
    suspend fun hourlyCount(now: Long): Int = withContext(serial) {
        dao.countLastHour(now - HOUR_MS, now)
    }
}
