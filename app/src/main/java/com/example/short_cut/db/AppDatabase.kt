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
@Database(entities = [ScrollHistory::class, UserLimit::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

    // scroll_history 테이블에 접근하는 DAO 반환
    abstract fun scrollHistoryDao(): ScrollHistoryDao

    // user_limit 테이블에 접근하는 DAO 반환
    abstract fun userLimitDao(): UserLimitDao

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

        // DB 인스턴스를 가져오는 함수
        // 이미 만들어진 인스턴스가 있으면 그걸 재사용, 없으면 새로 생성
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "short_cut_database" // DB 파일 이름
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
