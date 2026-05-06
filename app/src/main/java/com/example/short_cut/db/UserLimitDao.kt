package com.example.short_cut.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// @Dao — 이 인터페이스가 DB에 접근하는 함수들의 모음이라는 표시
// user_limit 테이블에 limit 설정값을 저장하고 조회하는 창구 역할
@Dao
interface UserLimitDao {

    // limit 설정값 저장
    // OnConflictStrategy.REPLACE — 같은 userId가 이미 있으면 덮어씀 (업데이트 효과)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(userLimit: UserLimit)

    // userId로 limit 설정 조회
    // 반환값이 null일 수 있음 — 저장된 설정이 없으면 null 반환
    @Query("SELECT * FROM user_limit WHERE userId = :userId LIMIT 1")
    suspend fun getLimit(userId: String): UserLimit?
}