package com.example.short_cut.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 앱 전체가 흰 배경 · 검정 글씨 · 연파랑 뱃지 · 회색 보조글씨로 색을 직접 지정하고 있다.
// 그래서 테마는 항상 라이트로 고정하고, 폰 배경화면 색(Material You)도 따라가지 않는다.
//   - 다크 모드 폰에서 NavigationBar 아이콘·글자가 흰색이 되어 흰 배경에 묻히던 문제 (10/30 이모 폰)
//   - 폰마다 선택 탭 알약 색이 달라지던 문제
// Material 컴포넌트가 기본값으로 가져다 쓰는 색만 앱 팔레트에 맞춰 둔다.
private val ShortCutColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),             // 기본 버튼 · 진행 표시
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE3F2FD),  // 선택된 탭 알약 ("이 기기" 뱃지와 같은 연파랑)
    onSecondaryContainer = Color(0xFF1565C0),
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,                   // 다이얼로그 · NavigationBar · 입력칸 배경
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF7F7F7),      // 카드 배경과 같은 연회색
    onSurfaceVariant = Color(0xFF888888),    // 선택 안 된 탭 · 보조 글씨
    outline = Color(0xFFCCCCCC),             // 입력칸 테두리
    error = Color(0xFFC62828)
)

@Composable
fun ShortCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShortCutColorScheme,
        typography = Typography,
        content = content
    )
}
