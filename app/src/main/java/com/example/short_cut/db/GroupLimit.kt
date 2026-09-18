package com.example.short_cut.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

// 내가 속한 그룹들의 한도 캐시 — GET /groups 응답을 받을 때마다 통째로 교체한다.
// 접근성 서비스가 쇼츠 시청 중 "그룹 한도를 넘었는지" 판단할 때 서버 호출 없이 이 테이블만 읽는다.
@Entity(tableName = "group_limit")
data class GroupLimit(
    @PrimaryKey
    val groupId: String,
    val name: String,
    val dailyLimit: Int,
    val hourlyLimit: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface GroupLimitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(limits: List<GroupLimit>)

    @Query("DELETE FROM group_limit")
    suspend fun deleteAll()

    @Query("SELECT * FROM group_limit")
    suspend fun getAll(): List<GroupLimit>

    // 서버 목록으로 통째 교체 — 탈퇴했거나 사라진 그룹의 한도가 남아 있지 않게 한다.
    @Transaction
    suspend fun replaceAll(limits: List<GroupLimit>) {
        deleteAll()
        insertAll(limits)
    }
}
