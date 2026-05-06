package com.example.short_cut.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// @Entity — 이 클래스가 Room DB의 테이블 하나라는 표시
// 사용자의 hourly/daily limit 설정값을 로컬에 저장하는 테이블
@Entity(tableName = "user_limit")
data class UserLimit(

    // @PrimaryKey — userId를 기본 키로 사용
    // autoGenerate 없이 직접 지정 — 유저 한 명당 limit 설정이 하나만 존재
    @PrimaryKey
    val userId: String,

    // 1시간 스크롤 한도 — 기본값 50
    val hourlyLimit: Int = 50,

    // 하루 스크롤 한도 — 기본값 100
    val dailyLimit: Int = 100,

    // 마지막으로 설정을 업데이트한 시각 (Unix ms)
    val updatedAt: Long = System.currentTimeMillis()
)