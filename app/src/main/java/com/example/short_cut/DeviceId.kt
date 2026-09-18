package com.example.short_cut

import android.content.Context
import java.util.UUID

// 다중 기기 동기화용 기기 식별자 (API 스펙 §0).
// 최초 호출 시 UUID 하나를 만들어 SharedPreferences 에 저장하고, 이후에는 같은 값을 돌려준다.
// 로그인/로그아웃과 무관하게 유지되며, 앱 데이터 삭제·재설치 시에만 새로 발급된다(= 새 기기 취급).
object DeviceId {
    private const val PREFS = "short_cut_prefs"
    private const val KEY = "deviceId"

    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        // 앱 UI · 접근성 서비스 · FCM 서비스가 동시에 처음 호출해도 UUID 가 하나만 만들어지게 동기화
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
                // commit(): 저장이 끝나기 전에 프로세스가 죽어 다음 실행에서 다른 UUID 가 생기는 일을 막음
                prefs.edit().putString(KEY, it).commit()
            }
            cached = id
            return id
        }
    }
}
