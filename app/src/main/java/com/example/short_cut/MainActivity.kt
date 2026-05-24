package com.example.short_cut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.short_cut.db.AppDatabase
import com.example.short_cut.db.HourlyCount
import com.example.short_cut.db.UserLimit
import com.example.short_cut.services.ShortCutAccessibilityService
import com.example.short_cut.ui.theme.ShortCutTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// 앱 화면 상태
// LOGIN: 로그인 필요
// ACCESSIBILITY_GUIDE: 로그인됨 + 접근성 권한 꺼짐 → 권한 설정 안내
// HOME: 로그인됨 + 접근성 권한 켜짐 → 실질 홈 화면
enum class Screen {
    LOGIN, ACCESSIBILITY_GUIDE, HOME
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShortCutTheme {
                AppRoot()
            }
        }
    }
}

// 앱 진입점 — 로그인/권한 상태에 따라 화면 분기
// ON_RESUME 마다 재평가해서 사용자가 접근성 설정에서 권한 토글하고 돌아오면 자동 전환
// onAuthChanged 콜백을 통해 설정 탭의 로그아웃/탈퇴에서도 LOGIN 으로 돌아갈 수 있게 함
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }

    fun resolveScreen(): Screen = when {
        auth.currentUser == null -> Screen.LOGIN
        !isAccessibilityServiceEnabled(context) -> Screen.ACCESSIBILITY_GUIDE
        else -> Screen.HOME
    }

    var currentScreen by remember { mutableStateOf(resolveScreen()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentScreen = resolveScreen()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (currentScreen) {
        Screen.LOGIN -> LoginScreen(
            onLoginSuccess = { currentScreen = resolveScreen() }
        )
        Screen.ACCESSIBILITY_GUIDE -> AccessibilityGuideScreen()
        Screen.HOME -> HomeScreen(
            onAuthChanged = { currentScreen = resolveScreen() }
        )
    }
}

// ShortCutAccessibilityService 가 시스템 설정에서 활성화돼 있는지 확인
// Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 는 활성화된 서비스들의 ComponentName 을 ':' 으로 구분해 저장
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, ShortCutAccessibilityService::class.java)
    val enabledSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledSetting)
    while (splitter.hasNext()) {
        val parsed = ComponentName.unflattenFromString(splitter.next()) ?: continue
        if (parsed == expected) return true
    }
    return false
}

// 로그인 화면
// 구글 계정으로 로그인하면 Firebase UID를 userId로 저장하고 다음 화면으로 이동
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit = {}) {
    val context = LocalContext.current

    // Firebase Auth 인스턴스 — 로그인/로그아웃/토큰 관리
    val auth = FirebaseAuth.getInstance()

    val scope = rememberCoroutineScope()

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

                    // 서버에 저장된 hourly/daily limit 동기화 — 다른 기기에서 변경한 값이 있다면 가져옴
                    scope.launch {
                        val limits = fetchLimitsFromServer(userId)
                        if (limits != null) {
                            val (h, d) = limits
                            AppDatabase.getDatabase(context).userLimitDao()
                                .insert(UserLimit(userId = userId, hourlyLimit = h, dailyLimit = d))
                            Log.d("Auth", "서버 limit 동기화 — hourly=$h, daily=$d")
                        } else {
                            Log.w("Auth", "서버 limit 동기화 실패 — 로컬/기본값 사용")
                        }
                    }

                    Log.d("Auth", "구글 로그인 성공 — userId: $userId")
                    onLoginSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Firebase 로그인 실패 — ${e.message}")
                    Toast.makeText(context, "Firebase 로그인 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } catch (e: ApiException) {
            // 구글 계정 선택 실패 또는 취소
            // statusCode=10 → DEVELOPER_ERROR: SHA-1/웹클라이언트ID/패키지명 중 하나가 Firebase 설정과 불일치
            Log.e("Auth", "구글 로그인 실패 — code=${e.statusCode}, msg=${e.message}")
            Toast.makeText(context, "구글 로그인 실패 (code=${e.statusCode})", Toast.LENGTH_LONG).show()
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
            // signOut 으로 이전 선택을 먼저 해제 → 매번 "계정 선택" 화면이 떠서 원하는 계정을 고를 수 있음
            // (해제 안 하면 마지막 계정으로 자동 로그인되어 선택지가 안 보임)
            Button(
                onClick = {
                    googleSignInClient.signOut().addOnCompleteListener {
                        launcher.launch(googleSignInClient.signInIntent)
                    }
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

// 실질 홈 화면 — 로그인 + 접근성 권한 모두 켜진 상태에서 표시
// 하단 BottomNavigation 으로 홈/통계/그룹홈/설정 4개 탭 전환
enum class HomeTab(val label: String) {
    HOME("홈"),
    STATS("통계"),
    GROUP("그룹홈"),
    SETTINGS("설정")
}

@Composable
fun HomeScreen(onAuthChanged: () -> Unit = {}) {
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.HOME,
                    onClick = { selectedTab = HomeTab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "홈") },
                    label = { Text("홈") }
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.STATS,
                    onClick = { selectedTab = HomeTab.STATS },
                    icon = { Icon(Icons.Filled.BarChart, contentDescription = "통계") },
                    label = { Text("통계") }
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.GROUP,
                    onClick = { selectedTab = HomeTab.GROUP },
                    icon = { Icon(Icons.Filled.Groups, contentDescription = "그룹홈") },
                    label = { Text("그룹홈") }
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.SETTINGS,
                    onClick = { selectedTab = HomeTab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "설정") },
                    label = { Text("설정") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
        ) {
            when (selectedTab) {
                HomeTab.HOME -> HomeTabContent()
                HomeTab.STATS -> StatsTabContent()
                HomeTab.GROUP -> GroupTabContent()
                HomeTab.SETTINGS -> SettingsTabContent(onAuthChanged = onAuthChanged)
            }
        }
    }
}

// 홈 탭 — 오늘의 목표(현재 적용 중인 limit) + 오늘의 스크롤 카운트
// 카운트가 목표를 초과하면 빨간색으로 강조
@Composable
private fun HomeTabContent() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
            .getString("userId", null)
    }

    var todayCount by remember { mutableStateOf(0) }
    var lastHourCount by remember { mutableStateOf(0) }
    var dailyLimit by remember { mutableStateOf(100) }
    var hourlyLimit by remember { mutableStateOf(50) }

    // 30초 마다 자동 새로고침 — 슬라이딩 윈도우 카운트가 시간 흐름에 따라 자연 감소하는 것이
    // 화면에도 반영되도록. LaunchedEffect(Unit) 은 composable 이 composition 에 들어올 때만 실행되므로
    // while + delay 로 주기적 refresh.
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            todayCount = db.scrollHistoryDao().countToday(startOfDayMs())
            lastHourCount = db.scrollHistoryDao().countLastHour(now - 60 * 60 * 1000)
            if (userId != null) {
                // 만료된 pending 먼저 promote 해서 오늘 적용되는 값으로 정렬
                db.userLimitDao().promoteExpiredPending(now)
                db.userLimitDao().getLimit(userId)?.let {
                    dailyLimit = it.dailyLimit
                    hourlyLimit = it.hourlyLimit
                }
            }
            delay(30_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Short-Cut",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(8.dp))

        SectionTitle("오늘의 목표")
        InfoRow(label = "Daily 목표", value = "${dailyLimit}회")
        InfoRow(label = "Hourly 목표", value = "${hourlyLimit}회")

        Spacer(Modifier.height(4.dp))

        SectionTitle("오늘의 스크롤")
        CountRow(
            label = "오늘 Daily Scroll",
            value = todayCount,
            isExceeded = todayCount > dailyLimit
        )
        CountRow(
            label = "최근 1시간 Scroll",
            value = lastHourCount,
            isExceeded = lastHourCount > hourlyLimit
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "스크롤 한도 변경은 설정 탭에서 가능합니다.",
            fontSize = 13.sp,
            color = Color(0xFF888888)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF888888)
    )
}

@Composable
private fun LimitRow(
    label: String,
    value: Int,
    step: Int,
    minValue: Int,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1A1A1A)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = {
                val newVal = (value - step).coerceAtLeast(minValue)
                if (newVal != value) onChange(newVal)
            }) {
                Icon(Icons.Filled.Remove, contentDescription = "감소")
            }
            Text(
                text = value.toString(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 56.dp)
            )
            IconButton(onClick = { onChange(value + step) }) {
                Icon(Icons.Filled.Add, contentDescription = "증가")
            }
        }
    }
}

// isExceeded=true 이면 값이 빨간색으로 표시됨 (오늘 목표 초과 강조용)
@Composable
private fun CountRow(label: String, value: Int, isExceeded: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 16.sp, color = Color(0xFF1A1A1A))
        Text(
            text = "$value 회",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (isExceeded) Color(0xFFC62828) else Color(0xFF1A1A1A)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// 통계 탭 — 일간(달력+선택일 상세)/주간(주 목록+상세)/월간(1년치 12달) 서브탭
// ─────────────────────────────────────────────────────────────────────────

private enum class StatsSubTab(val label: String) {
    DAILY("일간"), WEEKLY("주간"), MONTHLY("월간")
}

@Composable
private fun StatsTabContent() {
    var subTab by remember { mutableStateOf(StatsSubTab.DAILY) }
    // 월간에서 달을 누르면 일간 탭으로 드릴다운 — 보여줄 달을 공유 상태로 들고 있음
    var dailyMonthOffset by remember { mutableStateOf(0) }
    // AI 분석 화면 표시 여부 — 통계 위 진입 카드를 누르면 true
    var showAiAnalysis by remember { mutableStateOf(false) }

    // AI 분석 화면에선 시스템 뒤로가기를 가로채 통계로 복귀
    BackHandler(enabled = showAiAnalysis) { showAiAnalysis = false }

    if (showAiAnalysis) {
        AiAnalysisScreen(onBack = { showAiAnalysis = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        // AI 분석 진입 카드 — 통계를 프롬프트로 만들어 AI 앱에서 분석받는 기능
        AiAnalysisEntryCard(onClick = { showAiAnalysis = true })

        // 서브탭 토글 row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsSubTab.values().forEach { tab ->
                val selected = subTab == tab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { subTab = tab },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) Color(0xFF1A1A1A) else Color(0xFFF1F1F1)
                ) {
                    Text(
                        text = tab.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        color = if (selected) Color.White else Color(0xFF1A1A1A),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        when (subTab) {
            StatsSubTab.DAILY -> StatsDaily(
                monthOffset = dailyMonthOffset,
                onMonthOffsetChange = { dailyMonthOffset = it }
            )
            StatsSubTab.WEEKLY -> StatsWeekly()
            StatsSubTab.MONTHLY -> StatsMonthly(
                onMonthClick = { offset ->
                    dailyMonthOffset = offset
                    subTab = StatsSubTab.DAILY
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// AI 통계 분석 — 통계로 프롬프트를 만들어 서버(/analyze)로 보내고, AI 분석 결과만 인앱에 표시.
// 프롬프트 원문은 서버→AI 로만 전달되어 사용자에게 노출되지 않음(API 키는 서버 보관).
// ─────────────────────────────────────────────────────────────────────────

// 통계 탭 상단의 진입 카드
@Composable
private fun AiAnalysisEntryCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1A1A1A)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🤖", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "AI로 내 통계 분석하기",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "사용 패턴 분석과 줄이기 조언을 앱에서 바로 받기",
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.White)
        }
    }
}

// AI 분석 화면 — 통계로 프롬프트를 만들어 서버(/analyze)로 보내고, 돌아온 분석 결과만 표시.
// 프롬프트 원문은 화면에 띄우지도 클립보드에 복사하지도 않음 → 사용자에게 노출되지 않음.
@Composable
private fun AiAnalysisScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }

    var analysis by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }  // "다시 시도" 트리거

    LaunchedEffect(userId, reloadKey) {
        loading = true
        error = null
        analysis = null
        if (userId == null) {
            error = "로그인이 필요합니다. 다시 로그인 후 이용해 주세요."
            loading = false
            return@LaunchedEffect
        }
        try {
            db.userLimitDao().promoteExpiredPending(System.currentTimeMillis())
            val limit = db.userLimitDao().getLimit(userId)
            val dailyLimit = limit?.dailyLimit ?: 0
            val hourlyLimit = limit?.hourlyLimit ?: 0

            // 최근 14일(오늘 포함) 날짜 — 최근→과거
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance()
            val days = (0 until 14).map { i ->
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(Calendar.DAY_OF_YEAR, -i)
                dayFmt.format(cal.time)
            }

            val serverByDay = fetchDailyStatsForDays(userId, days)
            // 서버가 비어도(오프라인 등) 최근 7일치 로컬 Room 으로 일별 합계 폴백
            val localTotals = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
            val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
            val month = fetchMonthlyStats(userId, monthKey)

            // 프롬프트는 만들어 서버로만 전송 — 사용자에겐 결과만 노출
            val prompt = buildStatsPrompt(dailyLimit, hourlyLimit, days, serverByDay, localTotals, month)
            val result = fetchAiAnalysis(userId, prompt)
            if (result != null) {
                analysis = result
            } else {
                error = "AI 분석을 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
            }
        } catch (e: Exception) {
            error = "통계를 불러오지 못했습니다: ${e.message}"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        // 상단 바
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text("AI 통계 분석", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "내 사용 통계를 AI가 분석했어요. 아래에서 사용 패턴과 줄이기 조언을 확인하세요.",
            fontSize = 13.sp,
            color = Color(0xFF666666),
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF1A1A1A)
                )
                Spacer(Modifier.width(10.dp))
                Text("AI가 분석 중이에요...", fontSize = 13.sp, color = Color(0xFF888888))
            }
            error != null -> Column {
                Text(error!!, fontSize = 13.sp, color = Color(0xFFC62828), lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { reloadKey++ },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A))
                ) {
                    Text("다시 시도", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            analysis != null -> AiAnalysisBody(analysis = analysis!!)
        }
    }
}

// AI 분석 결과 본문 — 서버에서 받은 분석 텍스트만 표시(프롬프트 원문은 노출하지 않음).
@Composable
private fun AiAnalysisBody(analysis: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F7)
    ) {
        SelectionContainer {
            Text(
                analysis,
                modifier = Modifier.padding(16.dp),
                fontSize = 14.sp,
                color = Color(0xFF1A1A1A),
                lineHeight = 21.sp
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

// 여러 날짜의 일간 통계를 동시에(최대 8개) 가져옴 — AI 프롬프트용. 실패한 날짜는 결과에서 빠짐.
private suspend fun fetchDailyStatsForDays(
    userId: String,
    days: List<String>
): Map<String, DailyStatsRemote> = coroutineScope {
    val sem = Semaphore(8)
    days.map { d -> async { d to sem.withPermit { fetchDailyStats(userId, d) } } }
        .awaitAll()
        .mapNotNull { (d, s) -> s?.let { d to it } }
        .toMap()
}

// 통계를 사람이 읽기 쉬운 한국어 프롬프트로 변환. 패턴 분석 + 줄이기 조언을 함께 요청.
private fun buildStatsPrompt(
    dailyLimit: Int,
    hourlyLimit: Int,
    days: List<String>,                         // yyyy-MM-dd, 최근→과거 14일
    serverByDay: Map<String, DailyStatsRemote>, // 서버 일간 통계 (있으면 우선)
    localTotals: Map<String, Int>,              // 로컬 Room 일별 합계 (서버 없을 때 폴백)
    month: MonthStatsRemote?
): String {
    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val label = SimpleDateFormat("M.d(E)", Locale.KOREAN)
    fun lbl(d: String): String = try { label.format(parse.parse(d)!!) } catch (e: Exception) { d }
    fun totalOf(d: String): Int = serverByDay[d]?.totalScroll ?: localTotals[d] ?: 0

    val recent7 = days.take(7)
    val sum7 = recent7.sumOf { totalOf(it) }
    val sum14 = days.sumOf { totalOf(it) }
    val avg7 = sum7 / 7
    val avg14 = sum14 / 14
    val stopSum = days.sumOf { serverByDay[it]?.stopCount ?: 0 }
    val ignoreSum = days.sumOf { serverByDay[it]?.ignoreCount ?: 0 }
    val exceedDays = days.count { dailyLimit > 0 && totalOf(it) > dailyLimit }

    val peakDay = days.maxByOrNull { totalOf(it) }
    val peakCount = peakDay?.let { totalOf(it) } ?: 0
    val hasPeak = peakDay != null && peakCount > 0
    val hourlyLine = peakDay?.let { serverByDay[it]?.hourlyCounts }
        ?.withIndex()
        ?.filter { it.value > 0 }
        ?.joinToString(", ") { "${it.index}시 ${it.value}회" }
        ?.takeIf { it.isNotEmpty() }
        ?: "기록 없음"

    return buildString {
        appendLine("당신은 디지털 웰빙 코치입니다. 아래는 'ShortCut' 앱이 기록한 제 YouTube Shorts 사용 통계입니다. 이 데이터를 바탕으로 분석해 주세요.")
        appendLine()
        appendLine("[제 목표 한도]")
        appendLine("- 하루 목표: ${dailyLimit}회")
        appendLine("- 1시간 목표: ${hourlyLimit}회")
        appendLine()
        appendLine("[최근 14일 일별 스크롤 수] (목표 초과한 날은 ⚠️)")
        for (d in days) {
            val t = totalOf(d)
            val warn = if (dailyLimit > 0 && t > dailyLimit) " ⚠️" else ""
            appendLine("- ${lbl(d)}: ${t}회$warn")
        }
        appendLine()
        appendLine("[기간 요약]")
        appendLine("- 최근 7일 총 ${sum7}회 (일평균 ${avg7}회)")
        appendLine("- 최근 14일 총 ${sum14}회 (일평균 ${avg14}회)")
        appendLine("- 14일 중 목표 초과한 날: ${exceedDays}일")
        if (hasPeak) appendLine("- 가장 많이 본 날: ${lbl(peakDay!!)} (${peakCount}회)")
        appendLine("- 한도 초과 시 '그만보기' 선택 ${stopSum}회 / 무시하고 계속 ${ignoreSum}회")
        if (month != null) appendLine("- 이번 달 총 ${month.totalScroll}회 (일평균 ${month.avgScrollPerDay}회)")
        appendLine()
        if (hasPeak) {
            appendLine("[가장 많이 본 날(${lbl(peakDay!!)})의 시간대별 분포]")
            appendLine(hourlyLine)
            appendLine()
        }
        appendLine("[분석 요청]")
        appendLine("1. 제 사용 패턴을 분석해 주세요 — 주로 어느 시간대/요일에 많이 보는지, 한도를 얼마나 자주 넘는지, 최근 추세(늘었는지 줄었는지).")
        appendLine("2. 쇼츠 사용을 줄이기 위한 구체적이고 실천 가능한 팁 3~5가지와, 다음 주에 도전할 만한 현실적인 목표 한도를 제안해 주세요.")
        append("친근하고 격려하는 말투로, 한국어로 답해 주세요.")
    }
}

// 월간/주간 서버 요약 카드 — 총 스크롤·일평균·피크일을 한 줄로 표시
@Composable
private fun StatsSummaryCard(
    title: String,
    totalScroll: Int?,
    avgPerDay: Int?,
    peakDate: String?,
    peakCount: Int?,
    loading: Boolean,
    errorMsg: String?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF555555))
            Spacer(Modifier.height(6.dp))
            when {
                loading -> Text("불러오는 중...", fontSize = 12.sp, color = Color(0xFF888888))
                errorMsg != null -> Text(errorMsg, fontSize = 12.sp, color = Color(0xFFC62828))
                else -> {
                    val peakStr = if (peakDate != null) "$peakDate (${peakCount ?: 0}회)" else "—"
                    Text(
                        "총 ${totalScroll ?: 0}회 · 일평균 ${avgPerDay ?: 0}회",
                        fontSize = 14.sp,
                        color = Color(0xFF1A1A1A),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "피크일 $peakStr",
                        fontSize = 12.sp,
                        color = Color(0xFF555555),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// 스크롤량 비율(0~1) → 히트맵 색. 0이면 옅은 회색, 많을수록 진한 빨강.
private fun heatColor(intensity: Float): Color =
    if (intensity <= 0f) Color(0xFFF5F5F5)
    else lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * intensity.coerceIn(0f, 1f))

// ── 주간: 연도 선택 + 1~4분기 탭. 선택한 분기의 주(週)만 4열 그리드로 색 농도(히트맵) 표시.
//   배치는 좌상단=과거 → 우하단=최신(오름차순). 한 주를 누르면 그 주 상세(요약 + 월~일 막대그래프). ──
// 데이터는 그 해 전체를 한 번 받아(yearOffset 키) 분기 전환은 즉시. 그리드 합계·상세 막대 모두 같은
// /daily 일별 데이터에서 나와 항상 일치. 주가 속한 분기 = 그 주 목요일의 월/3 (연도 판정과 동일 기준).
@Composable
private fun StatsWeekly() {
    val context = LocalContext.current
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }
    val nowMs = remember { System.currentTimeMillis() }
    val curYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val curQuarter = remember { Calendar.getInstance().get(Calendar.MONTH) / 3 } // 0=1분기 .. 3=4분기
    val thisWeekMonday = remember { mondayOf(nowMs) }
    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(nowMs)) }

    var yearOffset by remember { mutableStateOf(0) }
    val displayYear = curYear + yearOffset
    val isCurrentYear = yearOffset == 0

    // 선택 분기 — 올해는 현재 분기로 시작
    var quarter by remember { mutableStateOf(curQuarter) }

    val dayFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    // 그 해 전체 주(월요일) — 오름차순(과거가 앞)
    val allWeeks = remember(yearOffset) { weeksOfYear(displayYear, thisWeekMonday, isCurrentYear) }

    // 주가 속한 분기 = 그 주 목요일의 월 / 3
    fun weekQuarter(ws: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ws; add(Calendar.DAY_OF_YEAR, 3) }
        return c.get(Calendar.MONTH) / 3
    }
    // 선택 분기의 주들 — 오름차순이라 좌상단=과거, 우하단=최신
    val weeks = remember(allWeeks, quarter) { allWeeks.filter { weekQuarter(it) == quarter } }

    // 표시 주들의 모든 날짜(오늘 이전)를 /daily 로 받아 일별 합산 — 그 해 전체를 한 번에(분기 전환은 즉시)
    var dayTotals by remember(yearOffset) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var loading by remember(yearOffset) { mutableStateOf(true) }
    LaunchedEffect(yearOffset, userId) {
        loading = true
        if (userId != null) {
            val cal = Calendar.getInstance()
            val days = mutableListOf<String>()
            for (ws in allWeeks) {
                for (i in 0 until 7) {
                    cal.timeInMillis = ws
                    cal.add(Calendar.DAY_OF_YEAR, i)
                    val ds = dayFmt.format(cal.time)
                    if (ds <= todayStr) days.add(ds)  // yyyy-MM-dd 문자열 비교 = 날짜 비교
                }
            }
            dayTotals = fetchDailyTotalsForDays(userId, days)
        }
        loading = false
    }

    fun weekDayCounts(ws: Long): List<Int> {
        val cal = Calendar.getInstance()
        return (0 until 7).map { i ->
            cal.timeInMillis = ws
            cal.add(Calendar.DAY_OF_YEAR, i)
            dayTotals[dayFmt.format(cal.time)] ?: 0
        }
    }
    fun weekTotal(ws: Long): Int = weekDayCounts(ws).sum()
    // 히트맵 농도는 그 해 전체 최대값 기준 — 분기끼리 색을 비교할 수 있게
    val maxTotal = remember(dayTotals, allWeeks) {
        (allWeeks.maxOfOrNull { weekTotal(it) } ?: 0).coerceAtLeast(1)
    }

    // 선택 주 — 분기 안의 최신 주를 기본 선택. 분기/연도 바뀌면 재설정.
    var selectedWeek by remember(yearOffset, quarter) {
        mutableStateOf(weeks.lastOrNull() ?: thisWeekMonday)
    }

    val shortFmt = remember { SimpleDateFormat("M.d", Locale.US) }
    val longStartFmt = remember { SimpleDateFormat("yyyy.MM.dd", Locale.US) }
    val longEndFmt = remember { SimpleDateFormat("MM.dd", Locale.US) }
    fun shortRange(ws: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ws }
        val s = shortFmt.format(c.time)
        c.add(Calendar.DAY_OF_YEAR, 6)
        return "$s~${shortFmt.format(c.time)}"
    }
    fun longRange(ws: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ws }
        val s = longStartFmt.format(c.time)
        c.add(Calendar.DAY_OF_YEAR, 6)
        return "$s ~ ${longEndFmt.format(c.time)}"
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 연 네비 ◀ 2026년 ▶
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { yearOffset-- }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 해")
            }
            Text(
                "${displayYear}년", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A), modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(onClick = { if (yearOffset < 0) yearOffset++ }, enabled = yearOffset < 0) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 해")
            }
        }

        // 분기 선택 탭 (1~4분기) — 한 번에 한 분기만 표시
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("1분기", "2분기", "3분기", "4분기").forEachIndexed { i, label ->
                val selected = quarter == i
                Surface(
                    modifier = Modifier.weight(1f).clickable { quarter = i },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) Color(0xFF1A1A1A) else Color(0xFFF1F1F1)
                ) {
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        color = if (selected) Color.White else Color(0xFF1A1A1A),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (loading) {
            Text("불러오는 중...", fontSize = 12.sp, color = Color(0xFF888888),
                modifier = Modifier.padding(4.dp))
        }

        if (weeks.isEmpty()) {
            Text(
                "이 분기에는 아직 기록이 없습니다.",
                fontSize = 13.sp, color = Color(0xFF888888),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            // 주 그리드 — 4열, 좌상단(과거) → 우하단(최신)
            val cols = 4
            val rowsN = (weeks.size + cols - 1) / cols
            for (r in 0 until rowsN) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        if (idx < weeks.size) {
                            val ws = weeks[idx]
                            val total = weekTotal(ws)
                            val intensity = total.toFloat() / maxTotal
                            val selected = ws == selectedWeek
                            var cellMod = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(3.dp)
                                .background(heatColor(intensity), RoundedCornerShape(8.dp))
                                .clickable { selectedWeek = ws }
                            if (selected) cellMod = cellMod.border(2.dp, Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                            Box(modifier = cellMod, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        shortRange(ws), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (intensity > 0.55f) Color.White else Color(0xFF1A1A1A)
                                    )
                                    Text(
                                        "${total}회", fontSize = 10.sp,
                                        color = if (intensity > 0.55f) Color.White else Color(0xFF555555)
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(3.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
            Spacer(Modifier.height(16.dp))

            // 선택 주 상세 (그리드와 같은 dayTotals 에서 계산 → 항상 일치)
            val dc = weekDayCounts(selectedWeek)
            val total = dc.sum()
            val peakIdx = dc.indices.maxByOrNull { dc[it] } ?: 0
            val peakDateStr = if (dc[peakIdx] > 0) {
                Calendar.getInstance().apply { timeInMillis = selectedWeek; add(Calendar.DAY_OF_YEAR, peakIdx) }
                    .let { dayFmt.format(it.time) }
            } else null
            Text(
                longRange(selectedWeek), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            StatsSummaryCard(
                title = "주간 요약",
                totalScroll = total,
                avgPerDay = total / 7,
                peakDate = peakDateStr,
                peakCount = if (peakDateStr != null) dc[peakIdx] else null,
                loading = loading,
                errorMsg = null
            )
            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                if (loading) {
                    Text("불러오는 중...", fontSize = 12.sp, color = Color(0xFF888888),
                        modifier = Modifier.padding(8.dp))
                } else {
                    WeekBarChart(counts = dc)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 월간: 1년치 12달 그리드. 각 달 총 스크롤을 색 농도(히트맵)로 표시, 누르면 그 달 일간 상세로 드릴다운. ──
// 각 달은 서버 /monthly 로 총 스크롤을 가져옴 (12회 병렬 호출).
@Composable
private fun StatsMonthly(onMonthClick: (monthOffset: Int) -> Unit) {
    val context = LocalContext.current
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }
    val now = remember { Calendar.getInstance() }
    val curYear = now.get(Calendar.YEAR)
    val curMonth = now.get(Calendar.MONTH) // 0..11

    var yearOffset by remember { mutableStateOf(0) } // 0 = 올해
    val displayYear = curYear + yearOffset

    var monthTotals by remember(yearOffset) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var loading by remember(yearOffset) { mutableStateOf(true) }
    LaunchedEffect(yearOffset, userId) {
        loading = true
        val map = mutableMapOf<Int, Int>()
        if (userId != null) {
            val results = coroutineScope {
                (0 until 12).map { m ->
                    async {
                        val isFuture = displayYear > curYear || (displayYear == curYear && m > curMonth)
                        if (isFuture) m to null
                        else m to fetchMonthlyStats(userId, "%04d-%02d".format(displayYear, m + 1))
                    }
                }.awaitAll()
            }
            results.forEach { (m, s) -> if (s != null) map[m] = s.totalScroll }
        }
        monthTotals = map
        loading = false
    }
    val maxTotal = (monthTotals.values.maxOrNull() ?: 0).coerceAtLeast(1)
    val monthNames = listOf("1월","2월","3월","4월","5월","6월","7월","8월","9월","10월","11월","12월")

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 연 네비 ◀ 2026년 ▶
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { yearOffset-- }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 해")
            }
            Text(
                "${displayYear}년", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A), modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(onClick = { if (yearOffset < 0) yearOffset++ }, enabled = yearOffset < 0) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 해")
            }
        }
        if (loading) {
            Text("불러오는 중...", fontSize = 12.sp, color = Color(0xFF888888),
                modifier = Modifier.padding(4.dp))
        }

        // 3열 × 4행 그리드
        for (rowIdx in 0 until 4) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val m = rowIdx * 3 + col
                    val isFuture = displayYear > curYear || (displayYear == curYear && m > curMonth)
                    val total = monthTotals[m] ?: 0
                    val intensity = total.toFloat() / maxTotal
                    val bg = if (isFuture) Color(0xFFFAFAFA) else heatColor(intensity)
                    var cellMod = Modifier
                        .weight(1f)
                        .aspectRatio(1.1f)
                        .padding(4.dp)
                        .background(bg, RoundedCornerShape(10.dp))
                    if (!isFuture) {
                        val monthOffset = (displayYear - curYear) * 12 + (m - curMonth)
                        cellMod = cellMod.clickable { onMonthClick(monthOffset) }
                    }
                    Box(modifier = cellMod, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                monthNames[m], fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isFuture -> Color(0xFFCCCCCC)
                                    intensity > 0.55f -> Color.White
                                    else -> Color(0xFF1A1A1A)
                                }
                            )
                            if (!isFuture) {
                                Text(
                                    "${total}회", fontSize = 12.sp,
                                    color = if (intensity > 0.55f) Color.White else Color(0xFF555555),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "달을 누르면 그 달 일간 통계로 이동합니다.",
            fontSize = 11.sp, color = Color(0xFF888888),
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

// 특정 해 1월 1일 0시(local)의 Unix ms
private fun startOfYearMs(year: Int): Long =
    Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis

// 그 해에 속하는 주(월요일 0시) 목록 — 오름차순. 주는 목요일이 속한 해 기준(ISO 유사).
// 현재 해면 이번 주까지만 포함(미래 주 제외).
private fun weeksOfYear(year: Int, thisWeekMonday: Long, isCurrentYear: Boolean): List<Long> {
    var ws = mondayOf(startOfYearMs(year))
    val result = mutableListOf<Long>()
    val cal = Calendar.getInstance()
    while (true) {
        cal.timeInMillis = ws
        cal.add(Calendar.DAY_OF_YEAR, 3) // 그 주의 목요일
        val thuYear = cal.get(Calendar.YEAR)
        if (thuYear > year) break
        if (thuYear == year) {
            if (isCurrentYear && ws > thisWeekMonday) break
            result.add(ws)
        }
        ws += 7L * 24L * 60L * 60L * 1000L
    }
    return result
}

// /logs/range 로 기간 내 모든 스크롤 로그를 받아 일별(local) 합산. 주간 통계 목록·상세 공통 소스.
// 로그 한 건당 scrollCount(묶음 수) 를 그 로그 timestamp 가 속한 날짜에 더함.
// 여러 날짜("yyyy-MM-dd")의 일별 총 스크롤을 /daily 로 받아 맵으로 반환. 동시 호출은 8개로 제한.
// 주간 통계의 그리드 합계와 상세 막대가 같은 소스(일별 /daily)에서 나오게 해 항상 일치시킴.
private suspend fun fetchDailyTotalsForDays(userId: String, days: List<String>): Map<String, Int> = coroutineScope {
    val sem = Semaphore(8)
    days.map { d ->
        async { d to sem.withPermit { fetchDailyStats(userId, d)?.totalScroll ?: 0 } }
    }.awaitAll().toMap()
}

// ── 일간: 월 달력 + 선택일 24시간 누적 그래프 ───────────────────────────
// 상단 달력(히트맵)에서 날짜를 누르면 그 날의 시간대별 누적 스크롤 그래프를 아래에 표시.
// 그래프엔 그 날의 daily limit 을 주황 점선으로 그려 한도 초과 여부를 한눈에 보여줌.
// monthOffset 은 상위(StatsTabContent)에서 들고 있어 월간 탭에서 드릴다운 가능.
@Composable
private fun StatsDaily(monthOffset: Int, onMonthOffsetChange: (Int) -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE).getString("userId", null)
    }

    // 달력 히트맵 일자별 카운트(yyyy-MM-dd → 스크롤 수) + 로컬 폴백용 현재 daily limit.
    // 상세 그래프와 같은 서버 /daily 를 소스로 써서 같은 날 숫자가 어긋나지 않게 함(서버에 없는 날만 로컬 Room 폴백).
    var dayCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var currentDailyLimit by remember { mutableStateOf(0) }
    LaunchedEffect(userId) {
        currentDailyLimit = userId?.let { db.userLimitDao().getLimit(it)?.dailyLimit } ?: 0
    }

    // 달력이 보여주는 달 (0=이번 달, -1=저번 달 ...) — 상위 상태
    val monthCal = remember(monthOffset) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val year = monthCal.get(Calendar.YEAR)
    val month = monthCal.get(Calendar.MONTH) // 0~11
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = (monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // MON=0..SUN=6

    // 월간 서버 요약 카드
    val monthKey = remember(monthOffset) { SimpleDateFormat("yyyy-MM", Locale.US).format(monthCal.time) }
    var summary by remember(monthOffset) { mutableStateOf<MonthStatsRemote?>(null) }
    var summaryLoading by remember(monthOffset) { mutableStateOf(true) }
    var summaryError by remember(monthOffset) { mutableStateOf<String?>(null) }
    LaunchedEffect(monthOffset, userId) {
        summaryLoading = true
        summaryError = null
        val s = userId?.let { fetchMonthlyStats(it, monthKey) }
        if (s != null) summary = s else summaryError = "서버 요약을 불러오지 못했습니다"
        summaryLoading = false
    }

    // 표시 중인 달의 일자별 스크롤 수를 서버 /daily 로 가져옴(상세 그래프와 동일 소스).
    // 서버 응답이 있는 날은 서버값, 없는 날(오프라인 등)만 로컬 Room countByDay 로 폴백.
    LaunchedEffect(monthOffset, userId) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tmp = Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis }
        val monthDays = (1..daysInMonth).map { day ->
            tmp.set(Calendar.DAY_OF_MONTH, day); fmt.format(tmp.time)
        }
        val server = userId?.let { fetchDailyStatsForDays(it, monthDays) } ?: emptyMap()
        val local = db.scrollHistoryDao().countByDay().associate { it.day to it.count }
        dayCounts = monthDays.associateWith { d -> server[d]?.totalScroll ?: local[d] ?: 0 }
    }

    val countByDate = remember(monthOffset, dayCounts) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tmp = Calendar.getInstance().apply { timeInMillis = monthCal.timeInMillis }
        (1..daysInMonth).associateWith { day ->
            tmp.set(Calendar.DAY_OF_MONTH, day)
            val key = fmt.format(tmp.time)
            dayCounts[key] ?: 0
        }
    }
    val maxCount = (countByDate.values.maxOrNull() ?: 0).coerceAtLeast(1)

    // 선택된 날짜 — 절대 자정 millis. 기본값 = 오늘.
    val todayMidnight = remember { startOfDayMs(0) }
    var selectedDayMs by remember { mutableStateOf(todayMidnight) }
    val selectedDateStr = remember(selectedDayMs) {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(selectedDayMs))
    }
    val selectedLabel = remember(selectedDayMs) {
        SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREAN).format(java.util.Date(selectedDayMs))
    }

    // 표시 중인 달이 바뀌면(달력 이동/월간 드릴다운) 선택일을 그 달 안으로 맞춤
    LaunchedEffect(monthOffset) {
        val selCal = Calendar.getInstance().apply { timeInMillis = selectedDayMs }
        if (selCal.get(Calendar.YEAR) != year || selCal.get(Calendar.MONTH) != month) {
            selectedDayMs = if (monthOffset == 0) startOfDayMs(0) else monthCal.timeInMillis
        }
    }

    // 선택일 상세 — 서버 우선, 실패 시 로컬 Room 폴백
    var counts by remember { mutableStateOf(IntArray(24)) }
    var serverStats by remember { mutableStateOf<DailyStatsRemote?>(null) }
    var source by remember { mutableStateOf("loading") }  // "server" / "local" / "loading"
    LaunchedEffect(selectedDayMs, userId) {
        source = "loading"
        val remote = userId?.let { fetchDailyStats(it, selectedDateStr) }
        if (remote != null) {
            serverStats = remote
            counts = remote.hourlyCounts
            source = "server"
        } else {
            serverStats = null
            val endOfDay = Calendar.getInstance().apply {
                timeInMillis = selectedDayMs
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val hourly = db.scrollHistoryDao().countByHourForDay(selectedDayMs, endOfDay)
            counts = IntArray(24).also { arr ->
                hourly.forEach { if (it.hour in 0..23) arr[it.hour] = it.count }
            }
            source = "local"
        }
    }

    // 누적값 — cumulative[i] = counts[0..i-1] 합. 길이 25
    val cumulative = remember(counts) {
        IntArray(25).also { arr ->
            var sum = 0
            for (i in 0 until 24) {
                arr[i] = sum
                sum += counts[i]
            }
            arr[24] = sum
        }
    }
    val total = cumulative[24]
    val peakHour = counts.indices.maxByOrNull { counts[it] } ?: 0
    val peakCount = counts.getOrNull(peakHour) ?: 0

    // 그 날의 daily limit — 서버값(그 날 기준) 우선, 없으면 현재 limit 으로 대체
    val dailyLimit = (serverStats?.dailyLimit?.takeIf { it > 0 }) ?: currentDailyLimit
    val exceeded = dailyLimit > 0 && total > dailyLimit

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── 월간 영역: 서버 요약 + 달력 히트맵 ──
        StatsSummaryCard(
            title = "${year}.%02d 서버 요약".format(month + 1),
            totalScroll = summary?.totalScroll,
            avgPerDay = summary?.avgScrollPerDay,
            peakDate = summary?.peakDate,
            peakCount = summary?.peakCount,
            loading = summaryLoading,
            errorMsg = summaryError
        )
        // 월 네비 ← 2026.05 →
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { onMonthOffsetChange(monthOffset - 1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 달")
            }
            Text(
                text = "${year}.%02d".format(month + 1),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(
                onClick = { if (monthOffset < 0) onMonthOffsetChange(monthOffset + 1) },
                enabled = monthOffset < 0
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 달")
            }
        }

        // 요일 헤더
        val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEach { d ->
                Text(
                    text = d,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF888888)
                )
            }
        }

        // 달력 셀 — 누르면 선택일 변경 (미래 날짜는 비활성, 선택일은 테두리 강조)
        val totalCells = firstDayOfWeek + daysInMonth
        val totalRows = (totalCells + 6) / 7
        for (rowIdx in 0 until totalRows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIdx = rowIdx * 7 + col
                    val dayNum = cellIdx - firstDayOfWeek + 1
                    if (dayNum in 1..daysInMonth) {
                        val cellMs = Calendar.getInstance().apply {
                            timeInMillis = monthCal.timeInMillis
                            set(Calendar.DAY_OF_MONTH, dayNum)
                        }.timeInMillis
                        val isFuture = cellMs > todayMidnight
                        val isSelected = cellMs == selectedDayMs
                        val count = countByDate[dayNum] ?: 0
                        val intensity = if (maxCount > 0) (count.toFloat() / maxCount).coerceIn(0f, 1f) else 0f
                        val bg = if (count == 0) {
                            Color(0xFFF5F5F5)
                        } else {
                            lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * intensity)
                        }
                        var cellMod = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .background(bg, RoundedCornerShape(6.dp))
                        if (isSelected) {
                            cellMod = cellMod.border(2.dp, Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                        }
                        if (!isFuture) {
                            cellMod = cellMod.clickable { selectedDayMs = cellMs }
                        }
                        Box(modifier = cellMod, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = dayNum.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        isFuture -> Color(0xFFCCCCCC)
                                        intensity > 0.55f -> Color.White
                                        else -> Color(0xFF1A1A1A)
                                    }
                                )
                                if (count > 0) {
                                    Text(
                                        text = count.toString(),
                                        fontSize = 10.sp,
                                        color = if (intensity > 0.55f) Color.White else Color(0xFF555555)
                                    )
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp))
                    }
                }
            }
        }

        // 히트맵 범례
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("적음", fontSize = 11.sp, color = Color(0xFF888888))
            Spacer(Modifier.width(6.dp))
            listOf(0.1f, 0.35f, 0.6f, 0.85f, 1.0f).forEach { f ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 4.dp)
                        .background(
                            lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * f),
                            RoundedCornerShape(4.dp)
                        )
                )
            }
            Spacer(Modifier.width(2.dp))
            Text("많음", fontSize = 11.sp, color = Color(0xFF888888))
        }

        Spacer(Modifier.height(20.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
        Spacer(Modifier.height(16.dp))

        // ── 선택일 상세 영역 ──
        Text(
            text = selectedLabel,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Text(
            text = "총 ${total}회" + if (source == "local") " (로컬)" else "",
            fontSize = 14.sp,
            color = Color(0xFF555555),
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        serverStats?.let { s ->
            Text(
                text = "정지 ${s.stopCount}회 · 무시 ${s.ignoreCount}회",
                fontSize = 12.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
        }
        if (dailyLimit > 0) {
            Text(
                text = if (exceeded) "daily limit ${dailyLimit}회 — 초과 ⚠️" else "daily limit ${dailyLimit}회 — 이내 ✓",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (exceeded) Color(0xFFC62828) else Color(0xFF2E7D32),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
        }
        if (peakCount > 0) {
            Text(
                text = "피크 시간대: ${peakHour}시 ~ ${peakHour + 1}시 (${peakCount}회)",
                fontSize = 13.sp,
                color = Color(0xFFC62828),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
            )
        }

        // Canvas 차트 영역 — 누적 라인 + 그날 한도 점선
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(start = 8.dp, end = 16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val leftPad = 40f
                val bottomPad = 24f
                val topPad = 8f
                val plotW = w - leftPad - 8f
                val plotH = h - topPad - bottomPad
                // 한도 점선이 항상 보이도록 총합과 한도 중 큰 값을 y축 최대치로
                val maxY = maxOf(total, dailyLimit).coerceAtLeast(1)

                // y축 가이드 라인 (4분할)
                val gridColor = Color(0xFFEEEEEE)
                for (i in 0..4) {
                    val y = topPad + plotH * (1f - i / 4f)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(leftPad + plotW, y),
                        strokeWidth = 1f
                    )
                }

                // 피크 시간 빨강 강조 배경
                if (peakCount > 0) {
                    val x1 = leftPad + plotW * (peakHour / 24f)
                    val x2 = leftPad + plotW * ((peakHour + 1) / 24f)
                    drawRect(
                        color = Color(0xFFC62828).copy(alpha = 0.18f),
                        topLeft = Offset(x1, topPad),
                        size = androidx.compose.ui.geometry.Size(x2 - x1, plotH)
                    )
                }

                // 그날 daily limit — 주황 점선 가로선
                if (dailyLimit > 0) {
                    val limitY = topPad + plotH * (1f - dailyLimit.toFloat() / maxY)
                    drawLine(
                        color = Color(0xFFFF8F00),
                        start = Offset(leftPad, limitY),
                        end = Offset(leftPad + plotW, limitY),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }

                // 누적 라인
                val path = Path()
                for (i in 0..24) {
                    val x = leftPad + plotW * (i / 24f)
                    val y = topPad + plotH * (1f - cumulative[i].toFloat() / maxY)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF1A1A1A),
                    style = Stroke(width = 3f)
                )

                // 피크 구간 라인은 빨강으로 덧칠
                if (peakCount > 0) {
                    val redPath = Path()
                    val x1 = leftPad + plotW * (peakHour / 24f)
                    val y1 = topPad + plotH * (1f - cumulative[peakHour].toFloat() / maxY)
                    val x2 = leftPad + plotW * ((peakHour + 1) / 24f)
                    val y2 = topPad + plotH * (1f - cumulative[peakHour + 1].toFloat() / maxY)
                    redPath.moveTo(x1, y1)
                    redPath.lineTo(x2, y2)
                    drawPath(
                        path = redPath,
                        color = Color(0xFFC62828),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }

        // x축 라벨
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(0, 6, 12, 18, 24).forEach { h ->
                Text(text = "${h}시", fontSize = 11.sp, color = Color(0xFF888888))
            }
        }

        // 한도 점선 범례
        if (dailyLimit > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(width = 18.dp, height = 3.dp).background(Color(0xFFFF8F00)))
                Spacer(Modifier.width(6.dp))
                Text("daily limit (${dailyLimit}회)", fontSize = 11.sp, color = Color(0xFF888888))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// 해당 시각이 속한 주의 월요일 0시(local) millisecond 반환
private fun mondayOf(timeMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    // Calendar.DAY_OF_WEEK: SUNDAY=1, MONDAY=2, ..., SATURDAY=7
    val daysFromMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7  // MON=0 ... SUN=6
    cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
    return cal.timeInMillis
}

// 한 주의 막대 그래프 — counts 는 [월, 화, 수, 목, 금, 토, 일] 순서
@Composable
private fun WeekBarChart(counts: List<Int>) {
    val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    val maxCount = counts.max().coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        counts.forEachIndexed { idx, count ->
            BarColumn(
                count = count,
                maxCount = maxCount,
                label = dayLabels[idx],
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// 막대 하나 — count=0 이면 회색 placeholder, 그 외엔 검정 막대
@Composable
private fun BarColumn(
    count: Int,
    maxCount: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    val fraction = (count.toFloat() / maxCount).coerceIn(0f, 1f)

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (count > 0) "$count" else "",
            fontSize = 11.sp,
            color = Color(0xFF666666),
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // 막대 영역 — 빈 공간(위) + 막대(아래) 를 weight 로 비율 제어
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (count == 0) {
                // 미래/빈 요일 — 회색 얇은 placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .background(Color(0xFFEEEEEE), RoundedCornerShape(2.dp))
                )
            } else {
                // 그 주 최댓값 막대는 fraction == 1f → Spacer.weight(0f) 가 되어 크래시.
                // fraction < 1f 일 때만 위쪽 Spacer 추가.
                if (fraction < 1f) {
                    Spacer(modifier = Modifier.weight(1f - fraction))
                }
                Box(
                    modifier = Modifier
                        .weight(fraction)
                        .fillMaxWidth(0.7f)
                        .background(
                            color = Color(0xFF1A1A1A),
                            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1A1A1A)
        )
    }
}

// 그룹홈 탭 — 추후 구현 예정 placeholder
@Composable
private fun GroupTabContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "2학기 구성 예정",
            fontSize = 18.sp,
            color = Color(0xFF888888)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// 설정 탭
// ─────────────────────────────────────────────────────────────────────────

private enum class SettingsSubScreen { ROOT, LIMIT, ACCOUNT }

@Composable
private fun SettingsTabContent(onAuthChanged: () -> Unit) {
    var subScreen by remember { mutableStateOf(SettingsSubScreen.ROOT) }

    // ROOT 가 아닐 때만 시스템 뒤로가기를 가로채서 ROOT 로 복귀
    BackHandler(enabled = subScreen != SettingsSubScreen.ROOT) {
        subScreen = SettingsSubScreen.ROOT
    }

    when (subScreen) {
        SettingsSubScreen.ROOT -> SettingsRootScreen(
            onOpenLimit = { subScreen = SettingsSubScreen.LIMIT },
            onOpenAccount = { subScreen = SettingsSubScreen.ACCOUNT }
        )
        SettingsSubScreen.LIMIT -> SettingsLimitScreen(
            onBack = { subScreen = SettingsSubScreen.ROOT }
        )
        SettingsSubScreen.ACCOUNT -> SettingsAccountScreen(
            onBack = { subScreen = SettingsSubScreen.ROOT },
            onAuthChanged = onAuthChanged
        )
    }
}

@Composable
private fun SettingsRootScreen(
    onOpenLimit: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }

    val prefs = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
    }

    // 닉네임 초기값 — 저장된 값 → Firebase displayName → 이메일 prefix
    val initialNickname = remember {
        prefs.getString("nickname", null)
            ?: auth.currentUser?.displayName
            ?: auth.currentUser?.email?.substringBefore('@')
            ?: ""
    }
    var nicknameDraft by remember { mutableStateOf(initialNickname) }
    var savedNickname by remember { mutableStateOf(initialNickname) }

    // 모드 — 현재는 "normal" 만 지원. "hard" 선택 시 안내 토스트.
    var appMode by remember {
        mutableStateOf(prefs.getString("appMode", "normal") ?: "normal")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "설정",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1A1A1A)
        )

        Spacer(Modifier.height(4.dp))

        // ── 닉네임 ─────────────────────────────
        SectionTitle("닉네임")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = nicknameDraft,
                onValueChange = { nicknameDraft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("닉네임 입력") }
            )
            Button(
                onClick = {
                    prefs.edit().putString("nickname", nicknameDraft.trim()).apply()
                    savedNickname = nicknameDraft.trim()
                    Toast.makeText(context, "닉네임이 저장됐어요", Toast.LENGTH_SHORT).show()
                },
                enabled = nicknameDraft.trim().isNotEmpty() && nicknameDraft.trim() != savedNickname,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("저장")
            }
        }

        // ── 모드 선택 ──────────────────────────
        SectionTitle("모드")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeChip(
                label = "일반 모드",
                selected = appMode == "normal",
                modifier = Modifier.weight(1f),
                onClick = {
                    appMode = "normal"
                    prefs.edit().putString("appMode", "normal").apply()
                }
            )
            ModeChip(
                label = "하드 모드",
                selected = appMode == "hard",
                modifier = Modifier.weight(1f),
                onClick = {
                    appMode = "hard"
                    prefs.edit().putString("appMode", "hard").apply()
                    Toast.makeText(context, "하드 모드 — Hourly 초과 시 까다로운 팝업이 뜹니다", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // ── 스크롤 한도 ────────────────────────
        SectionTitle("스크롤 한도")
        SettingsRow(
            label = "Limit 설정",
            value = "변경하기 →",
            onClick = onOpenLimit
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(8.dp))

        // ── 계정 ───────────────────────────────
        SectionTitle("계정")
        SettingsRow(
            label = "계정 관리",
            value = "열기 →",
            onClick = onOpenAccount
        )
    }
}

// ── 계정 하위 화면 (전체화면) ──────────────────────────────────────────
// 로그인된 Google 계정 정보 표시 + 로그아웃 / 탈퇴. 작은 다이얼로그가 아닌 별도 페이지로 띄움.
@Composable
private fun SettingsAccountScreen(onBack: () -> Unit, onAuthChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
    }

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    val accountEmail = auth.currentUser?.email ?: "이메일 정보 없음"
    val accountName = auth.currentUser?.displayName

    Column(modifier = Modifier.fillMaxSize()) {
        // 상단 뒤로가기 + 타이틀
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                text = "내 계정",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))

            // 로그인 계정 정보 카드
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF7F7F7)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("로그인된 Google 계정", fontSize = 13.sp, color = Color(0xFF888888))
                    Spacer(Modifier.height(6.dp))
                    if (!accountName.isNullOrBlank()) {
                        Text(
                            accountName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1A1A)
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(accountEmail, fontSize = 15.sp, color = Color(0xFF1A1A1A))
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color(0xFFEEEEEE))

            // 로그아웃 / 탈퇴 행
            SettingsRow(label = "로그아웃", value = "", onClick = { showLogoutConfirm = true })
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteConfirm = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "탈퇴하기", fontSize = 16.sp, color = Color(0xFFC62828))
            }
        }
    }

    // 로그아웃 확인
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("로그아웃") },
            text = { Text("로그아웃 하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    auth.signOut()
                    prefs.edit().remove("userId").apply()
                    showLogoutConfirm = false
                    onAuthChanged()
                }) { Text("예") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("아니요") }
            }
        )
    }

    // 탈퇴 확인 + 처리
    // 서버 DELETE /users/:userId 가 Firestore 데이터 + Firebase Auth 계정을 함께 삭제하므로
    // 클라이언트에서 user.delete() 를 별도로 호출하지 않음. 토큰이 살아있을 때 서버를 먼저 호출.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            title = { Text("탈퇴하기") },
            text = { Text("탈퇴하면 계정과 모든 데이터(서버·로컬)가 삭제됩니다.\n정말 진행하시겠습니까?") },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        val user = auth.currentUser
                        if (user == null) {
                            showDeleteConfirm = false
                            onAuthChanged()
                            return@TextButton
                        }
                        isDeleting = true
                        scope.launch {
                            val ok = deleteAccountOnServer(user.uid)
                            if (ok) {
                                db.scrollHistoryDao().deleteAll()
                                prefs.edit().clear().apply()
                                try { auth.signOut() } catch (_: Exception) {}
                                Toast.makeText(context, "탈퇴 완료", Toast.LENGTH_SHORT).show()
                                showDeleteConfirm = false
                                isDeleting = false
                                onAuthChanged()
                            } else {
                                Toast.makeText(context, "탈퇴 실패 — 네트워크를 확인해 주세요", Toast.LENGTH_LONG).show()
                                isDeleting = false
                            }
                        }
                    }
                ) { Text(if (isDeleting) "처리 중..." else "예") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { showDeleteConfirm = false }
                ) { Text("아니요") }
            }
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color(0xFF1A1A1A) else Color(0xFFF1F1F1)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (selected) Color.White else Color(0xFF1A1A1A),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 16.sp, color = Color(0xFF1A1A1A))
        Text(text = value, fontSize = 14.sp, color = Color(0xFF888888))
    }
}

// ── Limit 설정 하위 화면 ───────────────────────────────────────────────
// 현재 적용중인 limit + 다음날 적용 예정 limit + 새 draft 편집
// 변경하기 → "다음날부터 적용됩니다. 바꾸시겠습니까?" 다이얼로그 → 응/아니요
@Composable
private fun SettingsLimitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
            .getString("userId", null)
    }

    var currentHourly by remember { mutableStateOf(50) }
    var currentDaily by remember { mutableStateOf(100) }
    var pendingHourly by remember { mutableStateOf<Int?>(null) }
    var pendingDaily by remember { mutableStateOf<Int?>(null) }

    var draftHourly by remember { mutableStateOf(50) }
    var draftDaily by remember { mutableStateOf(100) }
    var loaded by remember { mutableStateOf(false) }

    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        if (userId != null) {
            // 만료된 pending 먼저 승격
            db.userLimitDao().promoteExpiredPending(System.currentTimeMillis())
            val limit = db.userLimitDao().getLimit(userId)
            if (limit != null) {
                currentHourly = limit.hourlyLimit
                currentDaily = limit.dailyLimit
                pendingHourly = limit.pendingHourlyLimit
                pendingDaily = limit.pendingDailyLimit
                draftHourly = limit.pendingHourlyLimit ?: limit.hourlyLimit
                draftDaily = limit.pendingDailyLimit ?: limit.dailyLimit
            } else {
                draftHourly = currentHourly
                draftDaily = currentDaily
            }
        }
        loaded = true
    }

    // 내일 적용 예정 값 (저장 직전에는 pending, 미설정시엔 current)
    val tomorrowHourly = pendingHourly ?: currentHourly
    val tomorrowDaily = pendingDaily ?: currentDaily
    val canCommit = loaded && (draftHourly != tomorrowHourly || draftDaily != tomorrowDaily)

    // 헤더 + 스크롤 가능 컨텐츠 + 하단 고정 버튼 구조
    // 컨텐츠가 길어져도 변경하기 버튼이 화면 밖으로 잘리지 않도록
    Column(modifier = Modifier.fillMaxSize()) {
        // 상단 뒤로가기 + 타이틀 (고정)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                text = "Limit 설정",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
        }

        // 스크롤 가능 컨텐츠 — 화면 좁아도 버튼 가리지 않음
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 내일 적용 예정 — pending 이 있을 때만 표시
            if (pendingHourly != null || pendingDaily != null) {
                SectionTitle("내일부터 적용 예정")
                InfoRow("Daily Limit", "${pendingDaily ?: currentDaily}회")
                InfoRow("Hourly Limit", "${pendingHourly ?: currentHourly}회")
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Color(0xFFEEEEEE))
            }

            // 새 값 편집
            SectionTitle("새 Limit 설정")
            LimitRow(
                label = "Daily Limit",
                value = draftDaily,
                step = 100,
                minValue = 100,
                onChange = { draftDaily = it }
            )
            LimitRow(
                label = "Hourly Limit",
                value = draftHourly,
                step = 10,
                minValue = 10,
                onChange = { draftHourly = it }
            )

            Spacer(Modifier.height(8.dp))
        }

        // 하단 고정 버튼
        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = { showConfirm = true },
                enabled = canCommit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A1A),
                    disabledContainerColor = Color(0xFFCCCCCC)
                )
            ) {
                Text(
                    text = "변경하기",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Limit 변경") },
            text = { Text("다음날부터 적용됩니다. 바꾸시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    if (userId == null) {
                        showConfirm = false
                        onBack()
                        return@TextButton
                    }
                    scope.launch {
                        // draft 가 current 와 같으면 해당 필드는 pending null 로 (예약 해제 효과)
                        val newPendingHourly = if (draftHourly != currentHourly) draftHourly else null
                        val newPendingDaily = if (draftDaily != currentDaily) draftDaily else null
                        val effectiveAt =
                            if (newPendingHourly != null || newPendingDaily != null) startOfTomorrowMs()
                            else null
                        db.userLimitDao().insert(
                            UserLimit(
                                userId = userId,
                                hourlyLimit = currentHourly,
                                dailyLimit = currentDaily,
                                pendingHourlyLimit = newPendingHourly,
                                pendingDailyLimit = newPendingDaily,
                                pendingEffectiveAt = effectiveAt
                            )
                        )
                        // 서버에도 새 값 push (draft 기준으로 — 서버는 pending 개념 없으니 새 limit 으로 즉시 동기화)
                        val ok = pushLimitToServer(userId, draftHourly, draftDaily)
                        val msg = if (ok) "내일부터 새 limit 적용 (서버 동기화 완료)"
                                  else "내일부터 새 limit 적용 (서버 동기화 실패)"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        showConfirm = false
                        onBack()
                    }
                }) { Text("예") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("아니요") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 15.sp, color = Color(0xFF1A1A1A))
        Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
    }
}

// 오늘 자정 0시 (local) Unix ms. offset=0 이면 오늘, -1=어제, 1=내일.
private fun startOfDayMs(offset: Int = 0): Long {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }
    return cal.timeInMillis
}

// 내일 자정 0시 (local) Unix ms — limit 변경이 effective 가 되는 시각
private fun startOfTomorrowMs(): Long = startOfDayMs(1)

// POST /limits/:userId — 서버에 새 limit 동기화
// Firebase ID 토큰을 Authorization: Bearer 헤더로 전송 (서버 미들웨어가 검증)
// 서버는 pending 개념이 없으므로 사용자가 변경한 값(draft)을 그대로 보냄.
// 로컬은 다음날부터 적용이지만 서버 기록은 변경 시점 = 적용으로 본다.
private suspend fun pushLimitToServer(userId: String, hourlyLimit: Int, dailyLimit: Int): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (token == null) {
                Log.e("LimitSync", "토큰 없음 — 전송 중단")
                return@withContext false
            }
            val json = """{"hourlyLimit":$hourlyLimit,"dailyLimit":$dailyLimit}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/limits/$userId")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val client = OkHttpClient()
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            Log.d("LimitSync", "POST /limits 응답 — ${response.code} (success=$success)")
            response.close()
            success
        } catch (e: Exception) {
            Log.e("LimitSync", "전송 실패 — ${e.message}")
            false
        }
    }
}

// ── 서버 통계/limit API 공통 ───────────────────────────────────────────
private const val SERVER_BASE_URL = "https://short-cut-server-production.up.railway.app"

// 일간 통계 — hourlyCounts 는 0..23 시간대별 카운트 배열 (서버 hourlyGraph 를 24 길이로 펴서 채움)
private data class DailyStatsRemote(
    val totalScroll: Int,
    val dailyLimit: Int,
    val stopCount: Int,
    val ignoreCount: Int,
    val hourlyCounts: IntArray
)

private data class MonthStatsRemote(
    val totalScroll: Int,
    val avgScrollPerDay: Int,
    val peakDate: String?,
    val peakCount: Int?
)

// Firebase ID 토큰을 Bearer 헤더로 붙여 GET 요청 → JSON 파싱. 실패 시 null.
private suspend fun authedGetJson(url: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
    try {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: return@withContext null
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val resp = OkHttpClient().newCall(req).execute()
        val body = resp.body?.string()
        val ok = resp.isSuccessful
        resp.close()
        if (ok && body != null) org.json.JSONObject(body) else {
            Log.w("StatsApi", "GET $url → ${resp.code}")
            null
        }
    } catch (e: Exception) {
        Log.e("StatsApi", "GET $url 실패 — ${e.message}")
        null
    }
}

// GET /limits/:userId — 다른 기기에서 설정한 한도값 동기화에 사용
private suspend fun fetchLimitsFromServer(userId: String): Pair<Int, Int>? {
    val json = authedGetJson("$SERVER_BASE_URL/limits/$userId") ?: return null
    val h = json.optInt("hourlyLimit", -1)
    val d = json.optInt("dailyLimit", -1)
    return if (h > 0 && d > 0) h to d else null
}

private suspend fun fetchDailyStats(userId: String, date: String): DailyStatsRemote? {
    val json = authedGetJson("$SERVER_BASE_URL/stats/$userId/daily?date=$date") ?: return null
    val hourly = IntArray(24)
    json.optJSONArray("hourlyGraph")?.let { arr ->
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val hr = obj.optInt("hour", -1)
            if (hr in 0..23) hourly[hr] = obj.optInt("scrollCount", 0)
        }
    }
    return DailyStatsRemote(
        totalScroll = json.optInt("totalScroll", 0),
        dailyLimit = json.optInt("dailyLimit", 0),
        stopCount = json.optInt("stopCount", 0),
        ignoreCount = json.optInt("ignoreCount", 0),
        hourlyCounts = hourly
    )
}

private suspend fun fetchMonthlyStats(userId: String, month: String): MonthStatsRemote? {
    val json = authedGetJson("$SERVER_BASE_URL/stats/$userId/monthly?date=$month") ?: return null
    val peak = json.optJSONObject("peakDay")
    return MonthStatsRemote(
        totalScroll = json.optInt("totalScroll", 0),
        avgScrollPerDay = json.optInt("avgScrollPerDay", 0),
        peakDate = peak?.optString("date")?.takeIf { it.isNotEmpty() },
        peakCount = peak?.optInt("scrollCount")
    )
}

// POST /analyze — 통계 프롬프트를 서버로 보내 AI 분석 결과(텍스트)만 돌려받음.
// 프롬프트는 서버→AI 로만 전달되고 사용자에겐 결과만 노출됨. AI API 키는 서버가 보관.
// 서버 응답 형식: { "analysis": "..." }
private suspend fun fetchAiAnalysis(userId: String, prompt: String): String? = withContext(Dispatchers.IO) {
    try {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: return@withContext null
        // JSONObject 로 직렬화해 프롬프트의 줄바꿈/따옴표가 안전하게 이스케이프되도록 함
        val payload = org.json.JSONObject()
            .put("userId", userId)
            .put("prompt", prompt)
            .toString()
        val req = Request.Builder()
            .url("$SERVER_BASE_URL/analyze")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        // Gemini 응답이 기본 10초를 넘길 수 있어 타임아웃을 넉넉히 — 기본값이면 앱이 먼저 끊어 "다시 시도" 에러
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string()
        val ok = resp.isSuccessful
        val code = resp.code
        resp.close()
        if (ok && body != null) {
            org.json.JSONObject(body).optString("analysis").takeIf { it.isNotBlank() }
        } else {
            Log.w("StatsApi", "POST /analyze → $code")
            null
        }
    } catch (e: Exception) {
        Log.e("StatsApi", "POST /analyze 실패 — ${e.message}")
        null
    }
}

// DELETE /users/:userId — 본인 계정 탈퇴
// 서버가 Firestore 데이터 전체 + Firebase Auth 계정을 한 번에 삭제
private suspend fun deleteAccountOnServer(userId: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (token == null) {
                Log.e("AccountDelete", "토큰 없음 — 전송 중단")
                return@withContext false
            }
            val request = Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/users/$userId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            val response = OkHttpClient().newCall(request).execute()
            val success = response.isSuccessful
            Log.d("AccountDelete", "DELETE /users 응답 — ${response.code} (success=$success)")
            response.close()
            success
        } catch (e: Exception) {
            Log.e("AccountDelete", "전송 실패 — ${e.message}")
            false
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HomePreview() {
    ShortCutTheme {
        HomeScreen()
    }
}
