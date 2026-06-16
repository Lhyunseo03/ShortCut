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

                    // 서버에 저장된 hourly/daily limit 동기화 — 다른 기기에서 변경한 값이 있다면 가져옴
                    // appScope(앱 수명) 에서 실행 — onLoginSuccess() 로 화면이 곧바로 전환돼도
                    // fetch+Room 저장이 취소되지 않고 끝까지 완료됨(과잉 차단 방지).
                    appScope.launch {
                        val limits = fetchLimitsFromServer(userId)
                        if (limits != null) {
                            val (h, d) = limits
                            AppDatabase.getDatabase(context).userLimitDao()
                                .insert(UserLimit(userId = userId, hourlyLimit = h, dailyLimit = d))
                            // 서버에 이미 한도가 있으면 기존 사용자 → 한도 설정(온보딩) 건너뜀
                            prefs.edit().putBoolean("limitSet_$userId", true).apply()
                            Log.d("Auth", "서버 limit 동기화 — hourly=$h, daily=$d")
                        } else {
                            Log.w("Auth", "서버 limit 동기화 실패 — 신규 사용자면 온보딩에서 설정")
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
            // statusCode=12501 → SIGN_IN_CANCELLED: 사용자가 계정 선택 화면을 그냥 닫음(실패 아님) → 조용히 무시
            if (e.statusCode == 12501) {
                Log.d("Auth", "구글 로그인 취소됨 (code=12501) — 토스트 생략")
            } else {
                Log.e("Auth", "구글 로그인 실패 — code=${e.statusCode}, msg=${e.message}")
                Toast.makeText(context, "구글 로그인 실패 (code=${e.statusCode})", Toast.LENGTH_LONG).show()
            }
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

// 한도 설정(온보딩) 화면 — 가입 직후 진입. 한도를 정해야만 다음 단계로 넘어갈 수 있다(건너뛰기 불가).
// 여기서 정한 한도는 "다음 주 월요일부터"가 아니라 즉시 적용된다(pending 없이 현재값으로 저장 + 서버 push).
// 기존 사용자(다른 기기/재설치)는 로컬 또는 서버에 이미 한도가 있으므로 자동으로 건너뜀.
@Composable
fun LimitOnboardingScreen(onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE) }
    val userId = remember { prefs.getString("userId", null) }

    var draftDaily by remember { mutableStateOf(100) }
    var draftHourly by remember { mutableStateOf(50) }
    var checking by remember { mutableStateOf(true) }  // 기존 한도 존재 여부 확인 중
    var saving by remember { mutableStateOf(false) }

    // 한도 설정 전엔 뒤로가기로 빠져나가지 못하게 막음(건너뛰기 불가)
    BackHandler(enabled = true) { /* 무시 */ }

    LaunchedEffect(userId) {
        if (userId == null) { onDone(); return@LaunchedEffect }
        // 1) 로컬에 이미 한도가 있으면 온보딩 불필요
        val local = db.userLimitDao().getLimit(userId)
        if (local != null) {
            prefs.edit().putBoolean("limitSet_$userId", true).apply()
            onDone(); return@LaunchedEffect
        }
        // 2) 서버에 한도가 있으면(기존 사용자) 받아와서 건너뜀
        val server = fetchLimitsFromServer(userId)
        if (server != null) {
            val (h, d) = server
            db.userLimitDao().insert(UserLimit(userId = userId, hourlyLimit = h, dailyLimit = d))
            prefs.edit().putBoolean("limitSet_$userId", true).apply()
            onDone(); return@LaunchedEffect
        }
        // 3) 신규 사용자 → 직접 설정하게 함
        checking = false
    }

    if (checking) {
        StatsLoadingSpinner(modifier = Modifier.fillMaxSize())
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "스크롤 한도 설정",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
            Text(
                text = "하루·1시간 동안 볼 숏폼 스크롤 한도를 정해주세요.\n지금 정한 한도는 바로 적용됩니다.",
                fontSize = 14.sp,
                color = Color(0xFF888888),
                lineHeight = 21.sp
            )

            Spacer(Modifier.height(8.dp))

            SectionTitle("한도 설정")
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
        }

        // 하단 고정 버튼 — 설정해야만 진행
        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    if (userId == null) { onDone(); return@Button }
                    saving = true
                    scope.launch {
                        // pending 없이 현재값으로 즉시 적용
                        db.userLimitDao().insert(
                            UserLimit(
                                userId = userId,
                                hourlyLimit = draftHourly,
                                dailyLimit = draftDaily
                            )
                        )
                        // 이번 주 통계가 새 한도 기준으로 계산되도록 서버에 즉시 반영
                        pushLimitToServer(userId, draftHourly, draftDaily)
                        prefs.edit().putBoolean("limitSet_$userId", true).apply()
                        onDone()
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A1A),
                    disabledContainerColor = Color(0xFFCCCCCC)
                )
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(
                        text = "이 한도로 시작하기",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

// 권한 안내 화면 — 접근성 + 사용 통계 둘 다 켜야 통과.
// 사용자가 접근성만 끄고 우회하려는 시도까지 막으려면 둘 다 필요(GuardForegroundService 가 사용 통계로 폴링).
@Composable
fun AccessibilityGuideScreen() {
    val context = LocalContext.current
    // ON_RESUME 마다 권한 상태 재평가용 — recomposition 트리거
    var refreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityOn = remember(refreshKey) { isAccessibilityServiceEnabled(context) }
    val usageStatsOn = remember(refreshKey) { hasUsageStatsPermission(context) }
    val overlayOn = remember(refreshKey) { hasOverlayPermission(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "권한 설정",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
            Text(
                text = "세 권한 모두 켜야 쇼츠/릴스 차단이 동작해요.\n앱을 끄거나 권한을 꺼도 우회되지 않도록 합니다.",
                fontSize = 14.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 접근성 권한 행
            PermissionRow(
                title = "접근성 권한",
                description = "쇼츠/릴스 화면에서 스크롤을 감지하고 한도 초과 시 팝업을 띄움",
                granted = accessibilityOn,
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )

            // 사용 통계 권한 행
            PermissionRow(
                title = "사용 통계 접근",
                description = "접근성 권한이 꺼졌을 때 우회 차단(타겟 앱 진입 시 홈으로 이동)",
                granted = usageStatsOn,
                onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            )

            // 다른 앱 위에 표시 권한 행 — 접근성 OFF 시 가드가 홈으로 보낼 수 있게 하는 핵심 권한
            PermissionRow(
                title = "다른 앱 위에 표시",
                description = "접근성이 꺼진 상태에서도 타겟 앱을 감지하면 홈으로 보낼 수 있게 함 (없으면 차단이 동작하지 않음)",
                granted = overlayOn,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }
            )
        }
    }
}

@Composable
internal fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (granted) Color(0xFFE8F5E9) else Color(0xFFF7F7F7),
        border = BorderStroke(1.dp, if (granted) Color(0xFF2E7D32) else Color(0xFFE0E0E0))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1A1A)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (granted) "✓ 켜짐" else "⚠ 꺼짐",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    lineHeight = 17.sp
                )
            }
            if (!granted) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFF888888))
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
