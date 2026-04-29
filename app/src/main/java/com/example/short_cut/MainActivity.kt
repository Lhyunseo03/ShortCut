package com.example.short_cut

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

// 화면 상태 정의
enum class Screen {
    LOGIN, ACCESSIBILITY_GUIDE
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShortCutTheme {
                // 현재 어떤 화면인지 상태로 관리
                var currentScreen by remember { mutableStateOf(Screen.LOGIN) }

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

// 로그인 화면 - onLoginSuccess 콜백 추가
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit = {}) {
    var nickname by remember { mutableStateOf("") }
    val context = LocalContext.current  // SharedPreferences 접근을 위해 context 가져오기

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

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("닉네임을 입력하세요") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (nickname.isNotBlank()) {
                        // 닉네임을 SharedPreferences에 userId로 저장 → SocketManager에서 읽어서 서버로 전송
                        val prefs = context.getSharedPreferences("short_cut_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putString("userId", nickname.trim()).apply()
                        onLoginSuccess()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFEE500)
                )
            ) {
                Text(
                    text = "등록하기",
                    color = Color(0xFF191919),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

//  접근성 안내 화면
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