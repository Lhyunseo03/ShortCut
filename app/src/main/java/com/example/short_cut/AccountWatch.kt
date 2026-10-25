package com.example.short_cut

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// ── 다른 기기에서 탈퇴한 계정 감지 ─────────────────────────────────────────
// 서버 DELETE /users 가 Firebase 계정을 지우면, 같은 계정으로 로그인돼 있던 **다른 기기**는 그 사실을 모른 채 남는다.
//   - 토큰이 만료(1시간)되면 모든 요청이 조용히 실패하고
//   - 그 사이 같은 구글 계정으로 다시 가입하면 새 uid 가 생겨, 기기마다 다른 계정(옛 uid / 새 uid)이 된다.
//     (실기기 재현: A 로그인 상태에서 B 가 탈퇴 → 재가입 → A 는 옛 계정, B 는 새 계정)
// 그래서
//   1) 앱이 앞으로 올 때마다 Firebase 에 "이 계정 아직 있나"(reload) 물어보고
//   2) 토큰을 받다가 "없는 계정" 오류가 나면
//   3) 서버가 ACCOUNT_DELETED 푸시를 보내면 (현서 요청 사항)
//   → 이 기기를 바로 로컬 로그아웃한다. 그 계정의 로컬 DB 파일도 지운다 (계정이 없어졌으니 되살릴 데가 없다).
object AccountWatch {
    private const val TAG = "AccountWatch"

    // 강제 로그아웃이 일어날 때마다 1 씩 — AppRoot 가 보고 화면을 다시 고른다
    val version = mutableIntStateOf(0)

    // 앱 어디서든 쓸 수 있는 application context (MainActivity · 접근성 서비스가 시작할 때 넣는다)
    @Volatile var appContext: Context? = null
    private val running = AtomicBoolean(false)

    fun init(context: Context) { appContext = context.applicationContext }

    // 계정이 아직 있는지 Firebase 에 확인. 없어졌으면 강제 로그아웃하고 false.
    // 네트워크 오류 등 "모름" 은 true (멀쩡한 계정을 함부로 내보내지 않는다).
    suspend fun checkAlive(context: Context): Boolean {
        val user = FirebaseAuth.getInstance().currentUser ?: return true
        return try {
            user.reload().await()
            true
        } catch (e: FirebaseAuthInvalidUserException) {
            Log.w(TAG, "계정이 Firebase 에 없음 (${e.errorCode}) → 강제 로그아웃")
            forceLogout(context)
            false
        } catch (e: Exception) {
            Log.d(TAG, "계정 확인 실패(무시) — ${e.message}")
            true
        }
    }

    // authedRequest 가 토큰을 받다가 "없는 계정" 예외를 만났을 때
    fun onInvalidUser(e: Exception) {
        if (e !is FirebaseAuthInvalidUserException) return
        val ctx = appContext ?: return
        appScope.launch { forceLogout(ctx) }
    }

    // 이 기기만 로그아웃 — 서버 /logout 은 부르지 않는다 (토큰이 이미 안 통한다)
    suspend fun forceLogout(context: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            val app = context.applicationContext
            val uid = app.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
                ?: FirebaseAuth.getInstance().currentUser?.uid
            clearAccountLocalState(app)
            if (uid != null) com.example.short_cut.db.AppDatabase.deleteUserDatabase(app, uid)
            withContext(Dispatchers.Main) {
                try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) {}
                Toast.makeText(app, "이 계정은 다른 기기에서 탈퇴됐어요. 다시 로그인해 주세요", Toast.LENGTH_LONG).show()
                version.intValue++
            }
            Log.w(TAG, "강제 로그아웃 완료 — uid=$uid")
        } finally {
            running.set(false)
        }
    }
}
