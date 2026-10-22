package com.example.short_cut.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// @Database — 이 클래스가 Room DB 전체를 관리하는 클래스라는 표시
// entities — 이 DB에 포함된 테이블 목록
// version — DB 구조가 바뀔 때마다 숫자를 올려야 함
//   v1 → v2: UserLimit 에 pending* 필드 3개 추가 (다음날부터 적용되는 limit 변경 예약)
//   v2 → v3: group_limit 테이블 추가 (내가 속한 그룹들의 한도 캐시)
@Database(entities = [ScrollHistory::class, UserLimit::class, GroupLimit::class], version = 3)
abstract class AppDatabase : RoomDatabase() {

    // scroll_history 테이블에 접근하는 DAO 반환
    abstract fun scrollHistoryDao(): ScrollHistoryDao

    // user_limit 테이블에 접근하는 DAO 반환
    abstract fun userLimitDao(): UserLimitDao

    // group_limit 테이블에 접근하는 DAO 반환
    abstract fun groupLimitDao(): GroupLimitDao

    companion object {
        // INSTANCE — 앱 전체에서 DB를 하나만 쓰도록 싱글톤으로 관리
        // @Volatile — 여러 스레드에서 동시에 접근해도 항상 최신 값을 읽도록 보장
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1 → v2 마이그레이션 — UserLimit 테이블에 pending* 컬럼 3개 추가
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_limit ADD COLUMN pendingHourlyLimit INTEGER")
                db.execSQL("ALTER TABLE user_limit ADD COLUMN pendingDailyLimit INTEGER")
                db.execSQL("ALTER TABLE user_limit ADD COLUMN pendingEffectiveAt INTEGER")
            }
        }

        // v2 → v3 마이그레이션 — group_limit 테이블 생성 (GroupLimit 엔티티와 컬럼/NOT NULL 이 정확히 일치해야 함)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_limit` (" +
                        "`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`dailyLimit` INTEGER NOT NULL, `hourlyLimit` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`))"
                )
            }
        }

        // ── 계정별 DB 파일 ──
        // 로그아웃 → 다른 계정 → 다시 로그인 하면 이전 계정의 스크롤 기록이 그대로 있어야 한다.
        // 한 파일에 섞어 두고 지우는 대신, 계정(uid)마다 파일을 따로 쓴다. 전환하면 그 계정 파일을 연다.
        // (로그인 안 된 상태는 "anon" 파일 — 업로드도 안 되는 기록이라 어디에도 섞이지 않는다)
        private const val LEGACY_NAME = "short_cut_database"
        private fun dbNameFor(uid: String) = if (uid.isBlank()) "short_cut_database_anon" else "short_cut_database_$uid"
        @Volatile private var instanceUid: String? = null

        fun currentUserId(context: Context): String =
            context.applicationContext.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
                .getString("userId", null) ?: ""

        // 지금 로그인한 계정의 DB. 계정이 바뀌면 새 파일을 연다 (이전 인스턴스는 닫지 않고 버린다 —
        // 화면 전환 중에 아직 그걸 쓰는 코루틴이 "닫힌 DB" 예외로 죽지 않게).
        fun getDatabase(context: Context): AppDatabase {
            val uid = currentUserId(context)
            INSTANCE?.let { if (instanceUid == uid) return it }
            return synchronized(this) {
                INSTANCE?.let { if (instanceUid == uid) return it }
                migrateLegacyIfNeeded(context.applicationContext, uid)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbNameFor(uid)
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instanceUid = uid
                instance
            }
        }

        // 계정 탈퇴 — 그 계정의 로컬 DB 파일을 지운다
        fun deleteUserDatabase(context: Context, uid: String) {
            synchronized(this) {
                if (instanceUid == uid) { try { INSTANCE?.close() } catch (_: Exception) {}; INSTANCE = null; instanceUid = null }
            }
            context.applicationContext.deleteDatabase(dbNameFor(uid))
        }

        // 예전 단일 파일(short_cut_database)이 있고 이 계정 파일이 아직 없으면 이름만 바꿔 승계.
        // 예전 방식은 로그아웃 때 비웠으므로, 남아 있는 파일은 지금 로그인한 계정의 것이다.
        private fun migrateLegacyIfNeeded(app: Context, uid: String) {
            if (uid.isBlank()) return
            val target = app.getDatabasePath(dbNameFor(uid))
            if (target.exists()) return
            val legacy = app.getDatabasePath(LEGACY_NAME)
            if (!legacy.exists()) return
            for (suffix in listOf("", "-wal", "-shm")) {
                val from = java.io.File(legacy.path + suffix)
                if (from.exists()) from.renameTo(java.io.File(target.path + suffix))
            }
        }
    }
}
