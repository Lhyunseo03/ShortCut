package com.example.short_cut.services

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

// 서버 → 앱 FCM data message 수신 (API 스펙 §0). notification 없는 무음 메시지이며 data["type"] 으로 분기한다.
//  - FLUSH         : 다른 기기가 /sync 를 불렀다 → 미전송 스크롤을 즉시 POST /userlogs
//  - COUNT_UPDATED : 서버 합계가 바뀌었다 → GET /sync 재호출해 카운트 재적용
class ShortCutMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "ShortCutFCM"
        private const val PREFS = "short_cut_prefs"
        const val PK_FCM_TOKEN = "fcmToken"   // 최신 FCM 토큰 — /devices/register 호출 시 사용

        fun savedToken(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PK_FCM_TOKEN, null)
    }

    override fun onNewToken(token: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PK_FCM_TOKEN, token).apply()
        Log.d(TAG, "FCM 토큰 갱신: $token")
        // 로그인 상태면 서버에 새 토큰 등록 (미로그인이면 registerDevice 가 그냥 반환 → 로그인 때 등록됨)
        val app = applicationContext
        com.example.short_cut.appScope.launch { com.example.short_cut.registerDevice(app) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
        Log.d(TAG, "FCM 수신 — type=$type, sentAt=${message.data["sentAt"]}")
        when (type) {
            "FLUSH" -> {
                // 접근성 서비스가 꺼져 있으면 메모리에 쌓인 배치도 없음 — 큐에 남은 실패분은 서비스 시작 시 재전송된다.
                val service = ShortCutAccessibilityService.instance
                if (service == null) Log.d(TAG, "FLUSH 무시 — 접근성 서비스 미실행")
                else service.flushFromRemote()
            }
            "COUNT_UPDATED" -> {
                val service = ShortCutAccessibilityService.instance
                if (service == null) Log.d(TAG, "COUNT_UPDATED 무시 — 접근성 서비스 미실행")
                else service.syncFromRemote()
            }
            else -> Log.w(TAG, "알 수 없는 FCM type: $type")
        }
    }
}
