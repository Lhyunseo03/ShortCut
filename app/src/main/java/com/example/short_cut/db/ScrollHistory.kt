    package com.example.short_cut.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// @Entity — 이 클래스가 Room DB의 테이블 하나라는 표시
// tableName — Firestore의 userLogs 컬렉션과 동일한 역할, 단 로컬 저장용
@Entity(tableName = "scroll_history")
data class ScrollHistory(

    // @PrimaryKey — 각 행을 고유하게 식별하는 기본 키
    // autoGenerate = true — 저장할 때 자동으로 1, 2, 3... 번호를 매겨줌
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 스크롤이 발생한 앱 패키지명 (예: com.google.android.youtube)
    val appPkg: String,

    // 스크롤 발생 시각 (Unix ms) — 슬라이딩 윈도우 계산에 사용
    val timestamp: Long
)