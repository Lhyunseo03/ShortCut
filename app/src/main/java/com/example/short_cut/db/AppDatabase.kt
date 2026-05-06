package com.example.short_cut.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// @Database — 이 클래스가 Room DB 전체를 관리하는 클래스라는 표시
// entities — 이 DB에 포함된 테이블 목록
// version — DB 구조가 바뀔 때마다 숫자를 올려야 함 (현재 최초 생성이라 1)
@Database(entities = [ScrollHistory::class, UserLimit::class], version = 1)
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

        // DB 인스턴스를 가져오는 함수
        // 이미 만들어진 인스턴스가 있으면 그걸 재사용, 없으면 새로 생성
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "short_cut_database" // DB 파일 이름
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}