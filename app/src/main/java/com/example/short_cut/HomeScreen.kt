package com.example.short_cut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.graphics.StrokeCap
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
import com.example.short_cut.db.AppCount
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    // AI 분석 → "적용하기" 로 넘어온 추천 한도 (daily, hourly). 설정 탭이 받아 한도 화면에 자동 입력.
    var limitPrefill by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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
                HomeTab.STATS -> StatsTabContent(
                    onApplyAiLimit = { daily, hourly ->
                        // AI 추천 한도를 들고 설정 탭의 한도 화면으로 자동 이동
                        limitPrefill = daily to hourly
                        selectedTab = HomeTab.SETTINGS
                    }
                )
                HomeTab.GROUP -> GroupTabContent()
                HomeTab.SETTINGS -> SettingsTabContent(
                    onAuthChanged = onAuthChanged,
                    prefillLimit = limitPrefill,
                    onConsumePrefill = { limitPrefill = null }
                )
            }
        }
    }
}

// 홈 탭 — 오늘의 목표(현재 적용 중인 limit) + 오늘의 스크롤 카운트
// 카운트가 목표를 초과하면 빨간색으로 강조
@Composable
internal fun HomeTabContent() {
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
            todayCount = db.scrollHistoryDao().countToday(startOfDayMs(), startOfDayMs(1))
            lastHourCount = db.scrollHistoryDao().countLastHour(now - 60 * 60 * 1000, now)
            if (userId != null) {
                // 만료된 pending 먼저 promote 해서 오늘 적용되는 값으로 정렬
                // [변경됨] promoteExpiredPending → promoteAndSyncLimit:
                //   승격(새 한도 적용)이 일어나는 그 순간 서버에도 새 값을 동기화한다.
                promoteAndSyncLimit(db, userId)
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HomePreview() {
    ShortCutTheme {
        HomeScreen()
    }
}
