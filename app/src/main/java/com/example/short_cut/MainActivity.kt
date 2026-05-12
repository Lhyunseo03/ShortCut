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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.short_cut.db.UserLimit
import com.example.short_cut.services.ShortCutAccessibilityService
import com.example.short_cut.ui.theme.ShortCutTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
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
        Screen.HOME -> HomeScreen()
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
// 하단 BottomNavigation 으로 홈/통계/그룹홈 3개 탭 전환
enum class HomeTab(val label: String) {
    HOME("홈"),
    STATS("통계"),
    GROUP("그룹홈")
}

@Composable
fun HomeScreen() {
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
            }
        }
    }
}

// 홈 탭 — limit 설정 (Daily 100단위, Hourly 10단위) + 오늘 스크롤 카운트
// 주의: ShortCutAccessibilityService 에 테스트 하드코딩 (5/10) 이 살아있는 한
// 여기서 변경한 값은 DB 에는 저장되지만 실제 서비스 동작에는 즉시 반영되지 않음
@Composable
private fun HomeTabContent() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    val userId = remember {
        context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
            .getString("userId", null)
    }

    var hourlyLimit by remember { mutableStateOf(50) }
    var dailyLimit by remember { mutableStateOf(100) }
    var todayCount by remember { mutableStateOf(0) }
    var lastHourCount by remember { mutableStateOf(0) }

    LaunchedEffect(userId) {
        if (userId != null) {
            db.userLimitDao().getLimit(userId)?.let {
                hourlyLimit = it.hourlyLimit
                dailyLimit = it.dailyLimit
            }
        }
        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        todayCount = db.scrollHistoryDao().countToday(startOfDay)
        lastHourCount = db.scrollHistoryDao().countLastHour(now - 60 * 60 * 1000)
    }

    fun persistLimit(hourly: Int, daily: Int) {
        if (userId == null) return
        scope.launch {
            db.userLimitDao().insert(
                UserLimit(userId = userId, hourlyLimit = hourly, dailyLimit = daily)
            )
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

        SectionTitle("스크롤 한도")
        LimitRow(
            label = "Daily Limit",
            value = dailyLimit,
            step = 100,
            minValue = 100,
            onChange = {
                dailyLimit = it
                persistLimit(hourlyLimit, it)
            }
        )
        LimitRow(
            label = "Hourly Limit",
            value = hourlyLimit,
            step = 10,
            minValue = 10,
            onChange = {
                hourlyLimit = it
                persistLimit(it, dailyLimit)
            }
        )

        Spacer(Modifier.height(16.dp))

        SectionTitle("오늘 스크롤")
        CountRow(label = "오늘 Daily Scroll", value = todayCount)
        CountRow(label = "최근 1시간 Scroll", value = lastHourCount)
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

@Composable
private fun CountRow(label: String, value: Int) {
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
            color = Color(0xFF1A1A1A)
        )
    }
}

// 통계 탭 — 주간 막대 그래프 (월~일)
// HorizontalPager 로 좌우 스크롤. 가운데 페이지(=initialPage)가 "이번 주",
// 양쪽으로 +1주/-1주 이동. Room 데이터(1주일치)에 없는 주는 빈 그래프.
// 추후 서버 GET API 연동되면 page 진입 시 fetch 추가 예정.
@Composable
private fun StatsTabContent() {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text(
            text = "주간 Daily Scroll",
            fontSize = 24.sp,
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HomePreview() {
    ShortCutTheme {
        HomeScreen()
    }
}