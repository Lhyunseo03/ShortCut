package com.example.short_cut

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.short_cut.ui.theme.ShortCutTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

// 앱 화면 상태 정의
// LOGIN: 로그인 화면, ACCESSIBILITY_GUIDE: 접근성 권한 안내 화면
enum class Screen {
    LOGIN, ACCESSIBILITY_GUIDE
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShortCutTheme {
                // 현재 어떤 화면을 보여줄지 상태로 관리
                var currentScreen by remember { mutableStateOf(Screen.LOGIN) }

                // 이미 로그인된 유저면 로그인 화면 건너뛰고 바로 접근성 화면으로
                // 앱 재시작 시 매번 로그인 안 해도 되게 하는 처리
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser != null) {
                    currentScreen = Screen.ACCESSIBILITY_GUIDE
                }

                // 현재 화면에 맞는 컴포저블 표시
                when (currentScreen) {
                    Screen.LOGIN -> LoginScreen(
                        onLoginSuccess = { currentScreen = Screen.ACCESSIBILITY_GUIDE }
                    )
                    Screen.ACCESSIBILITY_GUIDE -> AccessibilityGuideScreen()
                }
            }
        }
    }
}

// 로그인 화면
// 구글 계정으로 로그인하면 Firebase UID를 userId로 저장하고 다음 화면으로 이동
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit = {}) {
    val context = LocalContext.current

    // Firebase Auth 인스턴스 — 로그인/로그아웃/토큰 관리
    val auth = FirebaseAuth.getInstance()

    // 구글 로그인 옵션 설정
    // requestIdToken: 서버에서 토큰 검증할 때 필요한 ID 토큰 요청
    // web client ID — google-services.json의 client_type: 3 값
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken("425580874526-7hlejv7t4rbk3q0rn56egpd7ii6jvl41.apps.googleusercontent.com")
        .requestEmail()
        .build()

    // 구글 로그인 클라이언트 — 구글 계정 선택 팝업 UI 제공
    val googleSignInClient = GoogleSignIn.getClient(context, gso)

    // 구글 로그인 결과를 받는 런처
    // 구글 계정 선택 화면에서 돌아왔을 때 실행됨
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            // 구글 계정 선택 성공 → ID 토큰 추출
            val account = task.getResult(ApiException::class.java)

            // 구글 ID 토큰을 Firebase 인증 자격증명으로 변환
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)

            // Firebase Auth로 최종 로그인 처리
            auth.signInWithCredential(credential)
                .addOnSuccessListener { authResult ->
                    // Firebase UID — 구글 계정에 묶인 고유 ID (위조 불가능)
                    val userId = authResult.user?.uid ?: return@addOnSuccessListener

                    // userId를 SharedPreferences에 저장
                    // ShortCutAccessibilityService에서 이 값을 읽어 서버 요청에 사용
                    val prefs = context.getSharedPreferences("short_cut_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString("userId", userId).apply()

                    Log.d("Auth", "구글 로그인 성공 — userId: $userId")
                    onLoginSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Firebase 로그인 실패 — ${e.message}")
                }
        } catch (e: ApiException) {
            // 구글 계정 선택 실패 또는 취소
            Log.e("Auth", "구글 로그인 실패 — ${e.message}")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Short-Cut",
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 구글 로그인 버튼
            // 클릭 시 구글 계정 선택 팝업 실행
            Button(
                onClick = {
                    launcher.launch(googleSignInClient.signInIntent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4285F4) // 구글 블루
                )
            ) {
                Text(
                    text = "Google로 로그인",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// 접근성 안내 화면
// 로그인 후 최초 1회 접근성 권한 설정 안내
@Composable
fun AccessibilityGuideScreen() {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "접근성 권한 설정하기",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )

            Text(
                text = "앱이 제대로 동작하려면\n접근성 권한이 필요해요.",
                fontSize = 15.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 접근성 설정 화면으로 이동하는 버튼
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            ) {
                Text(
                    text = "접근성 설정 열기",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun LoginPreview() {
    ShortCutTheme {
        LoginScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AccessibilityGuidePreview() {
    ShortCutTheme {
        AccessibilityGuideScreen()
    }
}