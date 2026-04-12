package com.example.short_cut

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI
//Socket.IO Android 클라이언트는 ACK 콜백을 JavaScript처럼 람다로 못 써요. Ack 인터페이스를 명시적으로 써야 해요.
//이게 없으면 Kotlin이 람다 타입을 추론 못 해서 에러가 나요.
import io.socket.client.Ack

/**
 * Socket.IO 연결을 앱 전체에서 하나만 유지하는 싱글톤
 * 사용: SocketManager.connect() → SocketManager.emit(...)
 */
object SocketManager {

    private const val TAG = "SocketManager"

    // ── 서버 주소 ─────────────────────────────────────────
    // 실기기 테스트: PC의 실제 IP로 바꾸세요 (ex. "http://192.168.0.5:3000")
    // 에뮬레이터:    "http://10.0.2.2:3000"
    //private const val SERVER_URL = "http://10.0.2.2:3000"
    //잠깐 밑에걸로 바꿔놈
    private const val SERVER_URL = "http://localhost:3000"
    //실제 기기 연결하면 바꿀거에요

    private var socket: Socket? = null
    val isConnected: Boolean get() = socket?.connected() == true

    // ── 연결 ──────────────────────────────────────────────
    fun connect() {
        if (isConnected) return

        try {
            val options = IO.Options().apply {
                reconnection       = true
                reconnectionAttempts = 5
                reconnectionDelay  = 2000L   // 2초 후 재연결 시도
                timeout            = 10000L  // 10초 연결 타임아웃
            }

            socket = IO.socket(URI.create(SERVER_URL), options).apply {
                on(Socket.EVENT_CONNECT) {
                    Log.i(TAG, "✅ 서버 연결됨 — id: ${id()}")
                }
                on(Socket.EVENT_DISCONNECT) { args ->
                    val reason = args.firstOrNull()?.toString() ?: "unknown"
                    Log.w(TAG, "🔌 연결 해제 — reason: $reason")
                }
                /*
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e(TAG, "❌ 연결 오류: ${args.firstOrNull()}")
                }*/
                //연결 오류 왜나는지 확인하는 코드
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val error = args.firstOrNull()
                    if (error is Exception) {
                        Log.e(TAG, "❌ 연결 오류: ${error.message}")
                        Log.e(TAG, "❌ 원인1: ${error.cause?.message}")
                        Log.e(TAG, "❌ 원인2: ${error.cause?.cause?.message}")
                    } else {
                        Log.e(TAG, "❌ 연결 오류: $error")
                    }
                }
                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "소켓 초기화 실패: ${e.message}")
        }
    }

    // ── scroll_event 전송 ─────────────────────────────────
    fun emitScrollEvent(
        userId:      String,
        appPkg:      String,
        scrollCount: Int,
        onAck:       ((status: String, message: String) -> Unit)? = null
    ) {
        if (!isConnected) {
            Log.w(TAG, "소켓 미연결 — scroll_event 전송 건너뜀")
            return
        }

        val payload = JSONObject().apply {
            put("userId",      userId)
            put("appPkg",      appPkg)
            put("timestamp",   System.currentTimeMillis())
            put("scrollCount", scrollCount)
        }

        socket?.emit("scroll_event", payload, Ack { args ->
            val ack = args.firstOrNull() as? JSONObject
            val status = ack?.optString("status", "unknown") ?: "no_ack"
            val reason = ack?.optString("reason", "") ?: ""
            Log.d(TAG, "scroll_event ACK — status=$status ${if (reason.isNotEmpty()) "reason=$reason" else ""}")
            onAck?.invoke(status, reason)
        })


    }

    // ── 연결 해제 ─────────────────────────────────────────
    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        Log.i(TAG, "소켓 연결 해제")
    }
}
