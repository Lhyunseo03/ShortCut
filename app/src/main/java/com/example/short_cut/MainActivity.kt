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

// 화면 전환(컴포저블 이탈)으로 취소되면 안 되는 백그라운드 작업용 앱 수명 스코프.
// rememberCoroutineScope 는 컴포저블이 composition 을 떠나면 즉시 취소되므로,
// 로그인 직후 한도 fetch+Room 저장처럼 화
// 면이 곧바로 전환되는 시점에 끝까지 돌아야 하는 작업엔 부적합.
// (이 fetch 가 죽으면 로컬에 한도가 저장되지 않아 서비스가 기본값 50/100 으로 과잉 차단함.)
internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// 앱 화면 상태
// LOGIN: 로그인 필요
// ACCESSIBILITY_GUIDE: 로그인됨 + 접근성 권한 꺼짐 → 권한 설정 안내
// HOME: 로그인됨 + 접근성 권한 켜짐 → 실질 홈 화면
// LIMIT_SETUP: 로그인됨 + 아직 한도 미설정 → 한도 설정(온보딩) 화면. 설정 전엔 못 넘어감.
enum class Screen {
    LOGIN, LIMIT_SETUP, ACCESSIBILITY_GUIDE, HOME
}

class MainActivity : ComponentActivity() {
    // Android 13+ 알림 권한 런처 — 거부해도 다른 기능엔 영향 없고 주간 리포트 알림만 안 뜸
    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 결과 무시 */ }

    // 주간 리포트 알림을 눌러 진입했는지 — true 면 홈 위에 주간 리포트 화면을 띄움
    private val openWeeklyReport = mutableStateOf(false)

    companion object {
        const val EXTRA_OPEN_WEEKLY_REPORT = "open_weekly_report"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openWeeklyReport.value = intent?.getBooleanExtra(EXTRA_OPEN_WEEKLY_REPORT, false) == true
        // 주간 리포트 알림용 권한 (Android 13+). 이미 있거나 하위 버전이면 그냥 패스.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ShortCutTheme {
                AppRoot(openWeeklyReport = openWeeklyReport)
            }
        }
    }

    // 앱이 이미 떠 있을 때 알림을 누르면 onNewIntent 로 들어옴 → 주간 리포트 플래그 갱신
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_WEEKLY_REPORT, false)) {
            openWeeklyReport.value = true
        }
    }
}

// 앱 진입점 — 로그인/권한 상태에 따라 화면 분기
// ON_RESUME 마다 재평가해서 사용자가 접근성 설정에서 권한 토글하고 돌아오면 자동 전환
// onAuthChanged 콜백을 통해 설정 탭의 로그아웃/탈퇴에서도 LOGIN 으로 돌아갈 수 있게 함
@Composable
fun AppRoot(openWeeklyReport: MutableState<Boolean> = remember { mutableStateOf(false) }) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember { context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE) }

    fun resolveScreen(): Screen {
        val uid = prefs.getString("userId", null)
        return when {
            auth.currentUser == null || uid == null -> Screen.LOGIN
            // 가입 직후(한도 미설정)면 한도 설정 화면으로 — 설정해야만 다음 단계로. 사용자별 플래그로 판단.
            !prefs.getBoolean("limitSet_$uid", false) -> Screen.LIMIT_SETUP
            // 접근성·사용통계·오버레이 셋 중 하나라도 OFF 면 권한 안내로 — 셋 다 켜야 우회 차단 안전망 완성.
            // 오버레이('다른 앱 위에 표시')가 없으면 접근성 OFF 시 가드가 홈으로 못 보냄(백그라운드 액티비티 시작 제한).
            !isAccessibilityServiceEnabled(context) || !hasUsageStatsPermission(context) || !hasOverlayPermission(context) -> Screen.ACCESSIBILITY_GUIDE
            else -> Screen.HOME
        }
    }

    var currentScreen by remember { mutableStateOf(resolveScreen()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentScreen = resolveScreen()
                // 사용 통계 권한이 있으면 GuardForegroundService 시작(이미 실행 중이면 무시)
                // — 접근성 OFF 우회 차단용. 권한이 없으면 그냥 패스.
                startGuardServiceIfReady(context)
                // 로그인 상태면 주간 리포트(일요일 저녁) 예약 — REPLACE 라 매 resume 마다 다음 일요일로 맞춰짐
                if (auth.currentUser != null) {
                    com.example.short_cut.services.WeeklyReportScheduler.schedule(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (currentScreen) {
        Screen.LOGIN -> LoginScreen(
            onLoginSuccess = { currentScreen = resolveScreen() }
        )
        Screen.LIMIT_SETUP -> LimitOnboardingScreen(
            onDone = { currentScreen = resolveScreen() }
        )
        Screen.ACCESSIBILITY_GUIDE -> AccessibilityGuideScreen()
        Screen.HOME -> {
            // 주간 리포트 알림으로 진입했으면 홈 대신 리포트 화면을 띄움(뒤로가기로 홈 복귀)
            if (openWeeklyReport.value) {
                BackHandler { openWeeklyReport.value = false }
                WeeklyReportScreen(onBack = { openWeeklyReport.value = false })
            } else {
                HomeScreen(
                    onAuthChanged = { currentScreen = resolveScreen() }
                )
            }
        }
    }
}

// ShortCutAccessibilityService 가 시스템 설정에서 활성화돼 있는지 확인
// Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 는 활성화된 서비스들의 ComponentName 을 ':' 으로 구분해 저장
internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
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

// 사용 통계 접근 권한(PACKAGE_USAGE_STATS) 부여 여부. 특수 권한이라 AppOpsManager 로 체크.
// GuardForegroundService 가 동작하려면 이 권한이 필요(접근성 OFF 시 우회 차단용).
internal fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

// '다른 앱 위에 표시'(SYSTEM_ALERT_WINDOW) 권한 부여 여부.
// 접근성 OFF 시 GuardForegroundService 가 백그라운드에서 홈 인텐트를 실행하려면 이 권한이 필요하다.
// Android 10+ 의 '백그라운드 액티비티 시작 제한' 때문에, 이 권한이 없으면 startActivity(home) 가
// 조용히 무시돼 "타겟앱 감지는 되는데 홈으로 안 가는" 증상이 생긴다. 이 권한이 있으면 제한이 면제됨.
internal fun hasOverlayPermission(context: Context): Boolean =
    Settings.canDrawOverlays(context)

// GuardForegroundService 시작 — 사용 통계 권한 있을 때만. 이미 실행 중이면 시스템이 무시.
internal fun startGuardServiceIfReady(context: Context) {
    if (!hasUsageStatsPermission(context)) return
    val intent = Intent(context, com.example.short_cut.services.GuardForegroundService::class.java)
    androidx.core.content.ContextCompat.startForegroundService(context, intent)
}
