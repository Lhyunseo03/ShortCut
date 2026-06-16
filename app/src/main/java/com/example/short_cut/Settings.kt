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

internal enum class SettingsSubScreen { ROOT, LIMIT, ACCOUNT }

@Composable
internal fun SettingsTabContent(
    onAuthChanged: () -> Unit,
    prefillLimit: Pair<Int, Int>? = null,   // AI 추천 한도 (daily, hourly) — 있으면 한도 화면 자동 진입
    onConsumePrefill: () -> Unit = {}
) {
    var subScreen by remember { mutableStateOf(SettingsSubScreen.ROOT) }

    // AI 추천 "적용하기" 로 진입하면 한도 화면으로 자동 이동
    LaunchedEffect(prefillLimit) {
        if (prefillLimit != null) subScreen = SettingsSubScreen.LIMIT
    }

    // ROOT 가 아닐 때만 시스템 뒤로가기를 가로채서 ROOT 로 복귀
    BackHandler(enabled = subScreen != SettingsSubScreen.ROOT) {
        subScreen = SettingsSubScreen.ROOT
        onConsumePrefill()
    }

    when (subScreen) {
        SettingsSubScreen.ROOT -> SettingsRootScreen(
            onOpenLimit = { subScreen = SettingsSubScreen.LIMIT },
            onOpenAccount = { subScreen = SettingsSubScreen.ACCOUNT }
        )
        SettingsSubScreen.LIMIT -> SettingsLimitScreen(
            onBack = { subScreen = SettingsSubScreen.ROOT; onConsumePrefill() },
            prefill = prefillLimit,
            onPrefillConsumed = onConsumePrefill
        )
        SettingsSubScreen.ACCOUNT -> SettingsAccountScreen(
            onBack = { subScreen = SettingsSubScreen.ROOT },
            onAuthChanged = onAuthChanged
        )
    }
}

@Composable
internal fun SettingsRootScreen(
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
            .verticalScroll(rememberScrollState())  // 화면이 짧은 기기에서 계정 버튼이 잘리지 않도록 스크롤 허용
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
internal fun SettingsAccountScreen(onBack: () -> Unit, onAuthChanged: () -> Unit) {
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
internal fun ModeChip(
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
internal fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
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
internal fun SettingsLimitScreen(
    onBack: () -> Unit,
    prefill: Pair<Int, Int>? = null,        // AI 추천 (daily, hourly) — 들어오면 draft 를 이 값으로 자동 입력
    onPrefillConsumed: () -> Unit = {}
) {
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
            // [변경됨] promoteExpiredPending → promoteAndSyncLimit (승격 시 서버 동기화 포함)
            promoteAndSyncLimit(db, userId)
            val limit = db.userLimitDao().getLimit(userId)
            if (limit != null) {
                currentHourly = limit.hourlyLimit
                currentDaily = limit.dailyLimit
                pendingHourly = limit.pendingHourlyLimit
                pendingDaily = limit.pendingDailyLimit
                // step 그리드로 보정 — 과거 자유 입력으로 저장된 비-배수 값 정규화
                draftHourly = snapToStep(limit.pendingHourlyLimit ?: limit.hourlyLimit, 10, 10)
                draftDaily = snapToStep(limit.pendingDailyLimit ?: limit.dailyLimit, 100, 100)
            } else {
                draftHourly = currentHourly
                draftDaily = currentDaily
            }
        }
        loaded = true
    }

    // AI 추천값 자동 입력 — 로드 완료 후 적용해 위 LaunchedEffect 가 덮어쓰지 않게 함.
    // 한 번만 적용하고 소비(consume)해 사용자가 +/- 로 조정한 값을 다시 덮지 않는다.
    LaunchedEffect(prefill, loaded) {
        if (loaded && prefill != null) {
            val (d, h) = prefill
            draftDaily = snapToStep(d, 100, 100)
            draftHourly = snapToStep(h, 10, 10)
            onPrefillConsumed()
        }
    }

    // 다음 주 적용 예정 값 (pending 있으면 그 값, 없으면 현재 적용값)
    val nextWeekHourly = pendingHourly ?: currentHourly
    val nextWeekDaily = pendingDaily ?: currentDaily
    val canCommit = loaded && (draftHourly != nextWeekHourly || draftDaily != nextWeekDaily)

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
            // 다음 주 월요일 적용 예정 — pending 이 있을 때만 표시
            if (pendingHourly != null || pendingDaily != null) {
                SectionTitle("다음 주 월요일부터 적용 예정")
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
            text = { Text("다음 주 월요일부터 한 주 동안 적용됩니다. 바꾸시겠습니까?") },
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
                            if (newPendingHourly != null || newPendingDaily != null) startOfNextMondayMs()
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
                        // [변경됨] 예전엔 여기서 pushLimitToServer(draft...) 로 새 값을 즉시 서버에 보냈다.
                        //   하지만 새 한도는 다음 주 월요일부터 적용인데 서버가 미리 새 값을 갖게 되어,
                        //   오늘(이번 주) 통계가 아직 적용 안 된 한도로 계산되는 버그가 있었다.
                        // → 즉시 push 제거. 서버 동기화는 pending 이 실제로 승격되는 시점에
                        //   promoteAndSyncLimit() 가 대신 처리한다.
                        //   (현재 적용값은 서버에 그대로 남아 있으므로 이번 주 통계는 옛 한도 기준으로 정확히 유지됨)
                        Toast.makeText(context, "다음 주 월요일부터 새 limit 적용 예정", Toast.LENGTH_SHORT).show()
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
