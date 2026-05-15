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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.short_cut.db.DailyCount
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
// 통계 탭 — 월간/주간/일간 서브탭
// ─────────────────────────────────────────────────────────────────────────

private enum class StatsSubTab(val label: String) {
    MONTHLY("월간"), WEEKLY("주간"), DAILY("일간")
}

@Composable
private fun StatsTabContent() {
    var subTab by remember { mutableStateOf(StatsSubTab.MONTHLY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
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
            StatsSubTab.MONTHLY -> StatsMonthly()
            StatsSubTab.WEEKLY -> StatsWeekly()
            StatsSubTab.DAILY -> StatsDaily()
        }
    }
}

// ── 월간: 달력 히트맵 ──────────────────────────────────────────────────
// 한 달 캘린더 그리드. 그 날 스크롤 횟수에 비례해 빨간색 농도로 칸을 칠함.
// 좌우 화살표로 월 이동. monthOffset 0=이번 달, -1=저번 달, ...
@Composable
private fun StatsMonthly() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    var rows by remember { mutableStateOf<List<DailyCount>>(emptyList()) }

    LaunchedEffect(Unit) {
        rows = db.scrollHistoryDao().countByDay()
    }

    var monthOffset by remember { mutableStateOf(0) }
    val cal = remember(monthOffset) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) // 0~11
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // MON=0..SUN=6

    val countByDate = remember(monthOffset, rows) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tmp = Calendar.getInstance().apply { timeInMillis = cal.timeInMillis }
        (1..daysInMonth).associateWith { day ->
            tmp.set(Calendar.DAY_OF_MONTH, day)
            val key = fmt.format(tmp.time)
            rows.firstOrNull { it.day == key }?.count ?: 0
        }
    }
    val maxCount = (countByDate.values.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxSize()) {
        // 월 네비 ← 2026.05 →
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { monthOffset-- }) {
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
                onClick = { if (monthOffset < 0) monthOffset++ },
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
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF888888)
                )
            }
        }

        // 달력 셀들 — 7개씩 끊어서 weeks 단위로 출력
        val totalCells = firstDayOfWeek + daysInMonth
        val totalRows = (totalCells + 6) / 7
        for (rowIdx in 0 until totalRows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIdx = rowIdx * 7 + col
                    val dayNum = cellIdx - firstDayOfWeek + 1
                    if (dayNum in 1..daysInMonth) {
                        val count = countByDate[dayNum] ?: 0
                        val intensity = if (maxCount > 0) (count.toFloat() / maxCount).coerceIn(0f, 1f) else 0f
                        val bg = if (count == 0) {
                            Color(0xFFF5F5F5)
                        } else {
                            // 옅은 분홍 → 진한 빨강 lerp. 최저 부터 어느정도 색이 보이도록 0.15부터 시작.
                            lerp(Color(0xFFFFE5E5), Color(0xFFC62828), 0.15f + 0.85f * intensity)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .background(bg, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = dayNum.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (intensity > 0.55f) Color.White else Color(0xFF1A1A1A)
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
                        // 이번 달 범위 밖 셀 — 빈 자리 차지만
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                        )
                    }
                }
            }
        }

        // 범례
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
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
    }
}

// ── 주간: 기존 막대 그래프 (월~일) ─────────────────────────────────────
// HorizontalPager 로 좌우 스크롤. 가운데 페이지(=initialPage)가 "이번 주",
// 양쪽으로 +1주/-1주 이동.
@Composable
private fun StatsWeekly() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    var rows by remember { mutableStateOf<List<DailyCount>>(emptyList()) }

    LaunchedEffect(Unit) {
        rows = db.scrollHistoryDao().countByDay()
    }

    val thisWeekMonday = remember { mondayOf(System.currentTimeMillis()) }
    val pageCount = 2000
    val initialPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "주간 Daily Scroll",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            text = "← 좌우로 밀어 다른 주 보기 →",
            fontSize = 12.sp,
            color = Color(0xFF888888),
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val weekOffset = page - initialPage
            val weekStart = thisWeekMonday + weekOffset.toLong() * 7L * 24L * 60L * 60L * 1000L
            val counts = remember(weekStart, rows) { buildWeekCounts(weekStart, rows) }
            WeekBarChart(weekStart = weekStart, counts = counts)
        }
    }
}

// ── 일간: 24시간 누적 그래프 + 피크 시간대 빨강 강조 ─────────────────────
// 시간(0~23) 별 카운트를 누적해서 단조 증가 선 그래프로 그림.
// 그 중 시간당 증가폭이 가장 큰 한 시간 구간을 빨간 띠로 강조.
@Composable
private fun StatsDaily() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }

    var dayOffset by remember { mutableStateOf(0) } // 0=오늘, -1=어제
    var hourly by remember { mutableStateOf<List<HourlyCount>>(emptyList()) }

    LaunchedEffect(dayOffset) {
        val startOfDay = startOfDayMs(dayOffset)
        val endOfDay = startOfDayMs(dayOffset + 1)
        hourly = db.scrollHistoryDao().countByHourForDay(startOfDay, endOfDay)
    }

    // 24 길이의 시간대별 카운트 배열로 채워넣기
    val counts = remember(hourly) {
        IntArray(24).also { arr ->
            hourly.forEach { hc ->
                if (hc.hour in 0..23) arr[hc.hour] = hc.count
            }
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

    // 날짜 헤더
    val dateLabel = remember(dayOffset) {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREAN).format(c.time)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { dayOffset-- }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 날")
            }
            Text(
                text = dateLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(
                onClick = { if (dayOffset < 0) dayOffset++ },
                enabled = dayOffset < 0
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 날")
            }
        }

        Text(
            text = "총 ${total}회",
            fontSize = 14.sp,
            color = Color(0xFF555555),
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        if (peakCount > 0) {
            Text(
                text = "피크 시간대: ${peakHour}시 ~ ${peakHour + 1}시 (${peakCount}회)",
                fontSize = 13.sp,
                color = Color(0xFFC62828),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
            )
        }

        // Canvas 차트 영역
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
                val maxY = total.coerceAtLeast(1)

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

// weekStart(월요일 0시) 부터 7일치 카운트 배열 반환. rows 의 day("YYYY-MM-DD") 와 매칭.
private fun buildWeekCounts(weekStart: Long, rows: List<DailyCount>): List<Int> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    return (0 until 7).map { i ->
        cal.timeInMillis = weekStart
        cal.add(Calendar.DAY_OF_YEAR, i)
        val key = fmt.format(cal.time)
        rows.firstOrNull { it.day == key }?.count ?: 0
    }
}

// 한 주의 막대 그래프 — counts 는 [월, 화, 수, 목, 금, 토, 일] 순서
@Composable
private fun WeekBarChart(weekStart: Long, counts: List<Int>) {
    val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    val rangeFmt = SimpleDateFormat("yyyy.MM.dd", Locale.US)
    val endFmt = SimpleDateFormat("MM.dd", Locale.US)

    val cal = Calendar.getInstance().apply { timeInMillis = weekStart }
    val startStr = rangeFmt.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, 6)
    val endStr = endFmt.format(cal.time)

    val maxCount = counts.max().coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "$startStr ~ $endStr",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
        )

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

private enum class SettingsSubScreen { ROOT, LIMIT }

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
            onAuthChanged = onAuthChanged
        )
        SettingsSubScreen.LIMIT -> SettingsLimitScreen(
            onBack = { subScreen = SettingsSubScreen.ROOT }
        )
    }
}

@Composable
private fun SettingsRootScreen(
    onOpenLimit: () -> Unit,
    onAuthChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
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

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
        OutlinedButton(
            onClick = { showLogoutConfirm = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("로그아웃", color = Color(0xFF1A1A1A))
        }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
        ) {
            Text("탈퇴하기")
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
                }) { Text("응") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("아니요") }
            }
        )
    }

    // 탈퇴 확인 + 처리
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("탈퇴하기") },
            text = { Text("탈퇴하면 계정과 로컬 데이터가 모두 삭제됩니다.\n정말 진행하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    val user = auth.currentUser
                    if (user == null) {
                        showDeleteConfirm = false
                        onAuthChanged()
                        return@TextButton
                    }
                    scope.launch {
                        // 로컬 데이터 정리 — Room 스크롤 기록 전부 삭제
                        db.scrollHistoryDao().deleteAll()
                    }
                    user.delete().addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            prefs.edit().clear().apply()
                            Toast.makeText(context, "탈퇴 완료", Toast.LENGTH_SHORT).show()
                            showDeleteConfirm = false
                            onAuthChanged()
                        } else {
                            val msg = task.exception?.message ?: "알 수 없는 오류"
                            Toast.makeText(context, "탈퇴 실패: $msg", Toast.LENGTH_LONG).show()
                            showDeleteConfirm = false
                        }
                    }
                }) { Text("응") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("아니요") }
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
                }) { Text("응") }
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HomePreview() {
    ShortCutTheme {
        HomeScreen()
    }
}
