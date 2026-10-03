package com.example.short_cut.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.short_cut.R
import com.example.short_cut.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// 마지막으로 체크한 hourly 스크롤 횟수 — violation 전송 시 사용
private var lastHourlyCount = 0

class ShortCutAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortCut"

        // 그만보기(Stop) 선택 후 쇼츠 진입 차단 시간 — 5분
        const val STOP_BLOCK_MS = 5 * 60 * 1000L

        // hourly limit 초과 후 추가 popup 발생 간격 (10회당)
        const val HOURLY_STEP = 10

        // daily limit 초과 후 추가 popup 발생 간격 (100회당)
        const val DAILY_STEP = 100

        // SharedPreferences 키 — 영구 상태 (서비스 재시작/YT 강제종료 대비)
        const val PREFS = "short_cut_prefs"
        const val PK_DAILY_MILESTONE = "dailyMilestone"        // 직전 popup 트리거된 dailyCount 값 (-1=미발생)
        const val PK_HOURLY_MILESTONE = "hourlyMilestone"      // 직전 popup 트리거된 hourlyCount 값 (-1=미발생)
        const val PK_STOP_UNTIL = "stopUntilMs"                // 쇼츠 진입 차단 만료 시각 (0=차단 없음)
        const val PK_PENDING_TYPE = "pendingPopupType"         // "daily"/"hourly"/null — 현재 미응답 popup 종류
        const val PK_PENDING_OVERAGE = "pendingPopupOverage"   // 현재 미응답 popup 의 overage (0,10,20.../0,100,200...)
        const val PK_TODAY_START = "todayStartMs"              // 마지막으로 처리한 "오늘 0시" — 날짜 롤오버 감지용
        const val PK_PENDING_USERLOGS = "pendingUserLogs"      // 서버 전송 대기/실패한 userlog 큐 (JSON 배열 [{ts,count}])
        const val PK_PENDING_VIOLATIONS = "pendingViolations"  // 서버 전송 대기/실패한 violation 큐 (JSON 배열)
        // 서비스가 들고 있는 "계정 전체 기준" 오늘 카운트(서버 합계 반영) — 홈 탭이 로컬 Room 값과 비교해 큰 쪽을 표시
        const val PK_SYNCED_DAILY_COUNT = "syncedDailyCount"
        const val PK_SYNCED_DAILY_DAY = "syncedDailyDay"          // 위 값이 어느 날(0시 ms)의 것인지

        // 쇼츠 보는 동안 /sync 호출 주기 (API 스펙 §3)
        const val SYNC_INTERVAL_MS = 60 * 1000L
        // 자정 롤오버 때 어제 통계를 굳히기 전에 다른 기기의 업로드를 기다리는 시간
        const val FINALIZE_DELAY_MS = 90 * 1000L

        // 실행 중인 서비스 인스턴스 — FCM 서비스(같은 프로세스)가 FLUSH 수신 시 즉시 업로드를 요청하는 데 사용.
        // onServiceConnected 에서 설정, onDestroy 에서 해제.
        @Volatile var instance: ShortCutAccessibilityService? = null
            private set
    }

    // ── Room DB ───────────────────────────────────────────────
    // 스크롤 카운트 읽기/쓰기는 전부 이 저장소를 거친다 (단일 스레드로 직렬화 — 동시 쓰기 경합 방지)
    private lateinit var scrollCounts: com.example.short_cut.db.ScrollCountRepository
    private lateinit var userLimitDao: com.example.short_cut.db.UserLimitDao

    // 코루틴 스코프 — DB 작업은 메인 스레드에서 실행하면 안 되므로 별도 스코프 사용
    // SupervisorJob: 하나의 코루틴이 실패해도 나머지에 영향 없음
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Limit 설정 ────────────────────────────────────────────
    private var hourlyLimit = 50
    private var dailyLimit = 100

    // ── 카운터 ────────────────────────────────────────────────
    // 오늘 총 스크롤 횟수 — 앱 재시작 시 Room DB 에서 복원, 자정 롤오버 시 0으로 재로드
    private var dailyCount = 0

    // 직전 popup 이 발생한 카운트 값. -1 이면 popup 미발생 상태.
    //  - 다음 daily popup 트리거: max(dailyLimit, dailyMilestone + DAILY_STEP)
    //  - 다음 hourly popup 트리거: max(hourlyLimit, hourlyMilestone + HOURLY_STEP)
    private var dailyMilestone = -1
    private var hourlyMilestone = -1

    // 미응답 popup — YT 강제종료 등으로 popup view 가 사라져도 재진입 시 같은 popup 복원
    // null 이면 미응답 popup 없음
    private var pendingPopupType: String? = null
    private var pendingPopupOverage = 0

    // 그만보기(Stop) 선택 후 쇼츠 진입 차단 만료 시각 (Unix ms). 0 = 차단 없음.
    private var stopUntilMs = 0L

    // 마지막으로 처리한 "오늘 0시" — 자정 롤오버 감지에 사용
    private var todayStartMs = 0L

    // ── 다중 기기 동기화 (GET /sync) ─────────────────────────
    // 다른 기기들이 최근 1시간 동안 스크롤한 횟수 — 마지막 /sync 응답값. 시간당 한도 검사 때 로컬 카운트에 더한다.
    // (로컬 Room 에는 이 기기 스크롤만 있으므로, 이 값을 더해야 계정 전체 기준으로 한도가 걸린다)
    @Volatile private var otherDevicesLastHour = 0
    // 이 기기가 마지막으로 POST /milestone 한 값 — /sync 로 받은 서버 milestone 이 이 값보다 크면 다른 기기가 더 나간 것
    private var uploadedHourlyMilestone = -1
    private var uploadedDailyMilestone = -1
    // 다른 기기가 최근 1시간 안에 띄운 시간당 단계 — 슬라이딩 윈도우로 카운트가 잠깐 내려갔다 올라와도
    // 이 단계 아래로 milestone 을 낮추지 않는다 (같은 팝업이 두 기기에 한 번씩 뜨는 것 방지)
    private var remoteHourlyFloor = -1
    // 이 기기에서 사용자가 마지막으로 답한(Stop/계속보기) 단계 — 방금 답한 팝업이 /sync 응답 지연으로 다시 뜨지 않게
    private var localAnsweredHourly = -1
    private var localAnsweredDaily = -1
    // 동시에 두 /sync 가 겹쳐 서로 값을 엎지 않게 — 한 번에 하나만
    private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    // 쇼츠 보는 동안 1분마다 /sync — 쇼츠에서 나가면 다음 주기에 스스로 멈춘다
    private val syncTimerRunnable = object : Runnable {
        override fun run() {
            if (!detectors.any { it.inShortsMode }) return
            serviceScope.launch { syncFromServer("1분 주기") }
            mainHandler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    // ── 앱별 쇼츠 검출기 ──────────────────────────────────────
    // 각 detector 가 자기 앱의 진입/이탈/스크롤 상태를 따로 관리. 메인 서비스는 outcome 만 받아 처리.
    private val detectors = listOf(
        com.example.short_cut.services.detectors.YoutubeDetector(),
        com.example.short_cut.services.detectors.InstagramDetector(),
        com.example.short_cut.services.detectors.TiktokDetector(
            com.example.short_cut.services.detectors.TiktokDetector.PACKAGE_GLOBAL
        ),
        com.example.short_cut.services.detectors.TiktokDetector(
            com.example.short_cut.services.detectors.TiktokDetector.PACKAGE_TRILL
        )
    )

    // ── popup 상태 ────────────────────────────────────────────
    private var isPopupShowing = false
    private var windowManager: WindowManager? = null
    // popup view 들 — variant 4(stacked) 를 위해 list 로 관리. 단일 popup variant 에서도 list 에 1개로 저장.
    private val popupViews = mutableListOf<View>()
    // 팝업 뒤 전체화면 차단막 — 카드 밖 영역 터치를 흡수해 뒤(유튜브)가 스크롤/터치되지 않게 함.
    private var scrimView: View? = null
    // 5분 차단 안내 popup 표시 중 여부 — 쇼츠 이탈(BACK) 로 조기 dismiss 되지 않고 3초 타이머로만 닫히게 구분
    private var blockPopupShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 화면이 꺼지면(전원 버튼 등) 떠 있던 popup 을 내려서 잠금화면 위에 남지 않게 함.
    // pending 상태는 그대로 유지 → 잠금 해제 후 타겟 앱에 "다시 진입할 때만" popup 이 복원된다
    // (다른 앱 전환 처리와 동일한 패턴). detector 의 inShortsMode 도 리셋해야 재진입 시 entered 가 다시 발사됨.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (popupViews.isNotEmpty()) {
                Log.d(TAG, "화면 꺼짐 → popup 숨김 (pending 유지)")
                dismissAllPopups()
            }
            detectors.forEach { if (it.inShortsMode) it.onPackageLeft() }
            // 화면 꺼짐 → 미전송분 즉시 업로드 (다른 기기의 첫 /sync 조회가 바로 정확하도록)
            flushIfUnsent("화면 꺼짐")
            stopSyncTimer("화면 꺼짐")
        }
    }

    // ── 배치 전송 ─────────────────────────────────────────────
    // 현재 쌓이는 중인 배치. batchFirstScrollMs = 이 배치의 첫 스크롤 시각(전송 timestamp 로 사용).
    // batchAppPkg = 이 배치가 어느 앱에서 발생한 스크롤인지 — 다른 앱 스크롤이 끼면 먼저 flush 후 새 배치 시작
    // (서버에 플랫폼별로 분리 집계되도록).
    private var batchScrollCount = 0
    private var batchFirstScrollMs = 0L
    private var batchAppPkg: String = ""
    // 마지막으로 감지된 스크롤의 앱 패키지 — violation 전송 시 어느 플랫폼에서 발생했는지 식별용
    @Volatile private var lastScrollPkg: String = ""
    private val BATCH_SIZE = 10
    private val batchHandler = Handler(Looper.getMainLooper())
    private val batchTimerRunnable = Runnable {
        flushBatch()
        scheduleBatchTimer()
    }
    // batchScrollCount/batchFirstScrollMs 와 pending 큐(prefs) 접근을 보호하는 락 (네트워크 I/O 는 락 밖에서 수행)
    private val pendingLock = Any()
    // 동시에 두 전송이 큐 앞쪽을 중복 제거하지 않도록 — 전송은 한 번에 하나만
    private val isSending = java.util.concurrent.atomic.AtomicBoolean(false)
    // violation 큐 전용 락/전송 가드 (userlog 큐와 독립적으로 동작)
    private val violationLock = Any()
    private val isSendingViolations = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            // packageNames 를 비워서 다른 앱 이벤트도 받음 — YT 가 포그라운드에서 벗어나면 popup 만 dismiss
            // (pending 상태는 유지되어 YT/쇼츠 재진입 시 popup 복원)
            packageNames = null
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
        serviceInfo = info
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this

        val db = AppDatabase.getDatabase(this)
        scrollCounts = com.example.short_cut.db.ScrollCountRepository.get(this)
        userLimitDao = db.userLimitDao()

        serviceScope.launch {
            initializeOnStart()
        }

        Log.d(TAG, "서비스 연결 | API ${android.os.Build.VERSION.SDK_INT}")

        // 화면 꺼짐 감지 — ACTION_SCREEN_OFF 는 동적 등록만 가능(매니페스트 불가)
        registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        scheduleBatchTimer()
    }

    // 로그인 계정이 바뀌었으면(로그아웃 후 다른 계정 로그인) 이전 계정의 흔적을 전부 지운다.
    // 서비스 시작 때만이 아니라 스크롤 · /sync 때도 확인 — 서비스가 계속 떠 있는 채로 계정만 바뀌는 경우가 있어서.
    // 안 지우면 이전 계정 스크롤이 새 계정 통계(로컬)에 섞이고, 카운트 · 팝업 단계 · 차단이 새 계정에 이어진다.
    private suspend fun resetIfUserChanged(userId: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lastUserId = prefs.getString("lastUserId", "") ?: ""
        if (userId == lastUserId) return
        scrollCounts.deleteAll()
        prefs.edit()
            .putString("lastUserId", userId)
            .remove(PK_DAILY_MILESTONE)
            .remove(PK_HOURLY_MILESTONE)
            .remove(PK_PENDING_TYPE)
            .remove(PK_PENDING_OVERAGE)
            .remove(PK_STOP_UNTIL)
            .remove(PK_SYNCED_DAILY_COUNT)
            .remove(PK_SYNCED_DAILY_DAY)
            .remove(PK_PENDING_USERLOGS)      // 이전 계정의 미전송분은 새 계정 토큰으로 올라가면 안 됨
            .remove(PK_PENDING_VIOLATIONS)
            .remove(com.example.short_cut.PK_DEVICE_COUNT)
            .apply()
        synchronized(pendingLock) { batchScrollCount = 0; batchFirstScrollMs = 0L; batchAppPkg = "" }
        dailyCount = 0
        dailyMilestone = -1
        hourlyMilestone = -1
        uploadedHourlyMilestone = -1
        uploadedDailyMilestone = -1
        stopUntilMs = 0L
        otherDevicesLastHour = 0
        pendingPopupType = null
        pendingPopupOverage = 0
        withContext(Dispatchers.Main) { dismissAllPopups() }
        // 새 계정의 한도 로드
        if (userId != "unknown") {
            userLimitDao.getLimit(userId)?.let { hourlyLimit = it.hourlyLimit; dailyLimit = it.dailyLimit }
        }
        Log.d(TAG, "userId 변경 감지 → 상태 초기화 ($lastUserId → $userId)")
    }

    // 서비스 시작 시 Room DB / SharedPreferences 에서 상태 복원
    private suspend fun initializeOnStart() {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 1주일 이상 된 스크롤 기록 삭제
        val oneWeekAgo = now - (7 * 24 * 60 * 60 * 1000L)
        scrollCounts.deleteOlderThan(oneWeekAgo)

        // 오늘 자정 이후 스크롤 횟수를 Room DB 에서 불러와 dailyCount 복원
        todayStartMs = getStartOfDayTimestamp()
        dailyCount = scrollCounts.dailyCount(todayStartMs)
        Log.d(TAG, "오늘 스크롤 복원: $dailyCount")

        // userId 변경 감지 → 다른 유저 데이터 흔적 제거
        val userId = prefs.getString("userId", "unknown") ?: "unknown"
        resetIfUserChanged(userId)

        // pending limit 변경 예약이 만료됐으면 promote
        // [변경됨] promoteExpiredPending → promoteAndSyncLimit:
        //   서비스 시작 시점에 승격이 일어나면(주말 동안 앱을 안 열었다가 월요일 재시작 등)
        //   그 순간 서버에도 새 한도를 동기화한다.
        promoteAndSyncLimit(userId)
        val userLimit = userLimitDao.getLimit(userId)
        if (userLimit != null) {
            hourlyLimit = userLimit.hourlyLimit
            dailyLimit = userLimit.dailyLimit
            Log.d(TAG, "limit 로드 — hourly: $hourlyLimit, daily: $dailyLimit")
        }

        // 영구 상태 복원
        dailyMilestone = prefs.getInt(PK_DAILY_MILESTONE, -1)
        hourlyMilestone = prefs.getInt(PK_HOURLY_MILESTONE, -1)
        stopUntilMs = prefs.getLong(PK_STOP_UNTIL, 0L)
        pendingPopupType = prefs.getString(PK_PENDING_TYPE, null)
        pendingPopupOverage = prefs.getInt(PK_PENDING_OVERAGE, 0)

        // milestone 유효성 검증 — count 가 milestone 보다 낮으면 stale 이므로 리셋
        // (자정 롤오버나 hourly 슬라이딩 윈도우로 인해 count 가 줄어든 경우)
        if (dailyMilestone >= 0 && dailyCount < dailyMilestone) {
            dailyMilestone = -1
        }
        val hourlyAtStart = scrollCounts.hourlyCount(now)
        if (hourlyMilestone >= 0 && hourlyAtStart < hourlyMilestone) {
            hourlyMilestone = -1
        }

        // 만료된 stopUntilMs 정리
        if (stopUntilMs > 0 && now >= stopUntilMs) {
            stopUntilMs = 0
        }

        savePersistedState()
        Log.d(TAG, "상태 복원 — dailyMilestone=$dailyMilestone, hourlyMilestone=$hourlyMilestone, stopUntilMs=$stopUntilMs, pending=$pendingPopupType($pendingPopupOverage)")

        // 지난 실행에서 전송 못 하고 남은 userlog/violation 이 있으면 재시도 (앱 강제종료 등으로 유실 방지)
        sendPendingUserLogs()
        sendPendingViolations()

        // 재설치/데이터 손실 후 당일 카운트 복원 — 서버 /daily 의 totalScroll 로 시드.
        // 네트워크 의존이므로 위 로컬 복원/상태저장을 막지 않게 별도 코루틴에서 수행.
        serviceScope.launch {
            // 재설치 직후(폰 안 기록이 비어 있음)면 서버의 최근 7일 기록으로 Room 을 먼저 채운다
            // → 홈 탭 · 통계 · 시간당 한도가 0 부터가 아니라 서버 값에서 이어서 쌓인다.
            // 위의 "계정 변경 시 전체 삭제" 보다 뒤에서 실행돼야 복원한 기록이 지워지지 않는다.
            val restored = com.example.short_cut.HistoryRestore
                .restoreIfNeeded(this@ShortCutAccessibilityService, userId)
            if (restored) {
                val fromRoom = scrollCounts.dailyCount(todayStartMs)
                if (fromRoom > dailyCount) dailyCount = fromRoom
                Log.d(TAG, "서버 기록 복원 후 오늘 카운트: $dailyCount")
            }
            seedDailyCountFromServer(userId)
        }
    }

    // [신규] 재설치/데이터 손실 시 당일 카운트 복원 — 서버 GET /stats/:userId/daily 의 totalScroll 로 시드.
    // 서버가 canonical 이므로(통계 source of truth) 로컬보다 크면 그 값으로 올린다 → 한도 차단이 0 부터 다시 세지 않음.
    // 로컬보다 작거나 같으면(서버 배치 지연 등) 손대지 않음.
    private suspend fun seedDailyCountFromServer(userId: String) {
        if (userId == "unknown") return
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
        }.format(java.util.Date(todayStartMs))
        val serverTotal = fetchServerDailyTotal(userId, todayStr) ?: return
        if (serverTotal > dailyCount) {
            Log.d(TAG, "당일 카운트 서버 시드 — local=$dailyCount → server=$serverTotal")
            dailyCount = serverTotal
            publishDailyCount()
        }
    }

    // GET /stats/:userId/daily?date= → totalScroll. 실패/없으면 null.
    private suspend fun fetchServerDailyTotal(userId: String, date: String): Int? {
        return try {
            val token = getFirebaseToken() ?: return null
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/stats/$userId/daily?date=$date")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            val ok = response.isSuccessful
            response.close()
            if (ok && body != null) {
                org.json.JSONObject(body).optInt("totalScroll", -1).takeIf { it >= 0 }
            } else {
                Log.w(TAG, "당일 카운트 시드 GET 실패 — code=${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "당일 카운트 시드 GET 실패 — ${e.message}")
            null
        }
    }

    // 두 시각이 같은 '시(hour)'(로컬 타임존, 같은 날 같은 시)인지 — 배치가 시 경계를 넘는지 판단용
    private fun sameHour(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR) &&
                ca.get(java.util.Calendar.HOUR_OF_DAY) == cb.get(java.util.Calendar.HOUR_OF_DAY)
    }

    // 오늘 자정(00:00:00) 타임스탬프 계산
    private fun getStartOfDayTimestamp(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // 자정 롤오버 처리 — countShorts() 진입 시 매번 호출
    // 날짜가 바뀌었으면 dailyCount 와 milestone 을 새 날짜 기준으로 재설정
    private suspend fun handleDayRolloverIfNeeded(now: Long, userId: String) {
        val startToday = getStartOfDayTimestamp()
        if (startToday == todayStartMs) return

        Log.d(TAG, "자정 롤오버 감지 → 상태 재설정")

        // 롤오버 전 어제 날짜·limit 스냅샷 후 서버에 통계 확정 저장
        // (과거 통계에 "그날 적용됐던 한도"가 남도록 — 현재 한도로 덮이지 않게)
        val yesterdayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
        }.format(java.util.Date(todayStartMs))
        val snapHourly = hourlyLimit
        val snapDaily  = dailyLimit
        // [다중 기기] 어제 통계는 서버가 finalize 때 한 번 굳히고 다시 계산하지 않는다.
        //   다른 기기가 아직 안 올린 어제 스크롤이 있으면 영영 빠지므로, 먼저 /sync 를 불러 다른 기기에 FLUSH 를 보내고
        //   업로드될 시간을 준 뒤 finalize 한다. (서버가 늦게 온 로그로 캐시를 다시 계산하게 되면 이 대기는 없애도 됨)
        serviceScope.launch {
            syncFromServer("자정 롤오버 — 다른 기기 FLUSH")
            kotlinx.coroutines.delay(FINALIZE_DELAY_MS)
            finalizeStats(userId, yesterdayDate, snapHourly, snapDaily)
        }

        // 날짜가 바뀌기 전에 이전 날 배치를 먼저 flush — batchFirstScrollMs(이전 날 시각)로 전송돼
        // 어제 스크롤이 어제 날짜로 집계됨
        flushBatch()
        todayStartMs = startToday
        dailyCount = scrollCounts.dailyCount(startToday)
        dailyMilestone = -1
        localAnsweredDaily = -1

        // pending limit 변경이 있었다면 새 날짜에 맞춰 적용
        // [변경됨] promoteExpiredPending → promoteAndSyncLimit:
        //   자정 롤오버(특히 월요일 0시)에 승격이 일어나면 그 순간 서버도 동기화한다.
        //   ← 이 케이스가 "다음 주 월요일부터 적용"이 실제 발생하는 핵심 지점.
        promoteAndSyncLimit(userId)
        userLimitDao.getLimit(userId)?.let {
            hourlyLimit = it.hourlyLimit
            dailyLimit = it.dailyLimit
        }
        savePersistedState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val pkg = event.packageName?.toString()

        // 자기 자신/시스템 이벤트는 무시 — popup 이 띄워지면서 발생하는 이벤트(TYPE_ACCESSIBILITY_OVERLAY 등),
        // "android" 시스템 이벤트, SystemUI (잠금화면/알림창) 는 우리 상태 변화로 잘못 해석하지 않기 위해 제외.
        val ownPackage = packageName
        val isTransientEvent = pkg == null || pkg == ownPackage || pkg == "android" ||
                pkg == "com.android.systemui"

        // 매칭되는 detector 찾기
        val detector = if (pkg != null) detectors.firstOrNull { it.packageName == pkg } else null

        if (detector == null) {
            // self/system 이벤트는 조용히 무시
            if (isTransientEvent) return
            // 지원 안 하는 다른 앱으로 전환된 경우 — popup 만 숨기고 모든 detector 의 모드 리셋
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (popupViews.isNotEmpty()) {
                    Log.d(TAG, "다른 앱($pkg) 전환 → popup 숨김 (pending 유지)")
                    dismissAllPopups()
                }
                detectors.forEach { if (it.inShortsMode) it.onPackageLeft() }
                // 홈/다른 앱 전환 → 미전송분 즉시 업로드
                flushIfUnsent("다른 앱($pkg) 전환")
                stopSyncTimer("다른 앱 전환")
            }
            return
        }

        // detector 에 위임
        val outcome = detector.onEvent(this, event)

        // 5분 차단 기간 중 — 쇼츠/릴스 진입(entered) 또는 안에서 스와이프(scrolled) 할 때마다 BACK 으로 밀어냄.
        // 진입 시점에만 popup 표시. CONTENT_CHANGED 같은 outcome=NONE 이벤트로는 BACK 발사 안 함
        // (예전 코드처럼 모든 이벤트에 BACK 발사하면 IG 일반 피드 진입할 때 잠깐 노출되는 reel_pager 노드까지
        //  잡혀서 BACK 무한 루프로 앱 자체에 못 들어가는 문제 발생 → outcome 기반으로 정확히 쇼츠 행위 시에만 차단).
        if (detector.inShortsMode && now < stopUntilMs) {
            if (outcome.entered) {
                val remaining = ((stopUntilMs - now) / 1000).toInt() + 1
                Log.d(TAG, "차단 기간 중 쇼츠 진입 시도 (${detector.packageName}) — 남은 ${remaining}초")
                showBlockPopup(remaining)
            }
            if (outcome.entered || outcome.scrolled) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }

        // 기기 2대 이상이면 쇼츠 진입 시점부터 1분 주기로 — 이미 예약된 5분 타이머가 울릴 때까지 기다리지 않게 다시 예약.
        // (1대뿐이면 건드리지 않음 — 진입할 때마다 5분 타이머가 리셋되면 안 되므로)
        if (outcome.entered && isMultiDevice()) scheduleBatchTimer()

        // 쇼츠 진입 → 미전송분 올리고 /sync 로 계정 전체 카운트를 받아 적용 (D4). 이후 보는 동안 1분마다 반복.
        // (/sync 를 부르면 서버가 다른 기기에 FLUSH 를 보내고, 그 기기 업로드 후 COUNT_UPDATED 가 이쪽으로 온다)
        if (outcome.entered) {
            serviceScope.launch { syncFromServer("쇼츠 진입") }
            mainHandler.removeCallbacks(syncTimerRunnable)
            mainHandler.postDelayed(syncTimerRunnable, SYNC_INTERVAL_MS)
        }

        // 미응답 popup 복원 — 다음 두 경우에 즉시 다시 띄운다.
        //  1) detector 가 진입(entered)을 보고했을 때 (YT/IG: 창 전환으로 진입 감지, 앱 강제종료 후 재진입 등)
        //  2) [신규] 타겟 앱으로 창이 전환됐을 때 (WINDOW_STATE_CHANGED).
        //     TikTok 은 진입을 '첫 스크롤'로 감지해서 outcome.entered 가 늦게 떴고, 그래서 홈→재진입 시
        //     스크롤을 한 번 더 해야 popup 이 떴다. 창 전환만으로도 복원되게 해 돌아오자마자 다시 뜨게 한다.
        val reenteredTargetWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if ((outcome.entered || reenteredTargetWindow) && now >= stopUntilMs && popupViews.isEmpty()) {
            pendingPopupType?.let { type ->
                Log.d(TAG, "미응답 popup 재표시 — type=$type, overage=$pendingPopupOverage")
                showLimitPopup(type, pendingPopupOverage)
            }
        }

        if (outcome.exited) {
            // 쇼츠 이탈 — popup 숨김 (pending 유지). 차단 안내 popup 은 자체 6초 타이머에 맡김.
            if (popupViews.isNotEmpty() && !blockPopupShowing) {
                Log.d(TAG, "쇼츠 이탈 → popup 숨김 (pending 유지)")
                dismissAllPopups()
            }
            // 쇼츠 이탈 → 미전송분 즉시 업로드
            flushIfUnsent("쇼츠 이탈")
            if (!detectors.any { it.inShortsMode }) stopSyncTimer("쇼츠 이탈")
        }

        if (outcome.scrolled) {
            countShorts(now, detector.packageName)
        }
    }

    // [신규] Detector 의 비동기 지문 재확인 결과(느린 스크롤 복구) — 동기 outcome.scrolled 와 같은 처리.
    // 동기 경로의 5분 차단(stopUntilMs) 분기를 그대로 적용해야 차단 중 카운트되는 일이 없다.
    // 호출 시점에 detector 가 이미 inShortsMode 여부/지문 변화/debounce 를 모두 통과시킨 상태.
    fun onDeferredScroll(appPkg: String) {
        val now = System.currentTimeMillis()
        val detector = detectors.firstOrNull { it.packageName == appPkg } ?: return
        if (!detector.inShortsMode) return
        if (now < stopUntilMs) {
            // 차단 기간 중 — 카운트하지 않고 동기 경로처럼 사용자를 밀어냄
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        countShorts(now, appPkg)
    }

    // 스크롤 한 건 처리 — debounce/noise 검사는 각 detector 가 이미 수행함.
    // appPkg: 어느 앱(YT/IG/TT)에서 발생한 스크롤인지 — DB 저장용.
    private fun countShorts(now: Long, appPkg: String) {
        lastScrollPkg = appPkg
        serviceScope.launch {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"
            resetIfUserChanged(userId)

            // 자정 롤오버 처리 (limit/카운트/마일스톤 재로드)
            handleDayRolloverIfNeeded(now, userId)

            // 스크롤 이벤트를 Room DB 에 저장
            scrollCounts.recordScroll(appPkg, now)

            dailyCount++
            publishDailyCount()

            // 배치 카운터 증가 — BATCH_SIZE(10개) 쌓이면 즉시 서버 전송.
            // 배치의 첫 스크롤 시각(now)을 timestamp 로 보냄 → 서버가 실제 스크롤 시각 기준으로 집계.
            // 이번 스크롤이 배치 첫 스크롤과 다른 시(hour)/다른 앱이면 먼저 flush → 한 배치가 두 시간대/두 플랫폼에 안 걸치게.
            val needFlush = synchronized(pendingLock) {
                batchScrollCount > 0 && (!sameHour(batchFirstScrollMs, now) || batchAppPkg != appPkg)
            }
            if (needFlush) flushBatch()

            val reachedBatch: Boolean
            synchronized(pendingLock) {
                if (batchScrollCount == 0) {
                    batchFirstScrollMs = now
                    batchAppPkg = appPkg
                }
                batchScrollCount++
                reachedBatch = batchScrollCount >= BATCH_SIZE
            }
            if (reachedBatch) flushBatch()

            // 최근 1시간 스크롤 횟수 조회 (슬라이딩 윈도우)
            // 로컬(이 기기) + 다른 기기 최근 1시간(마지막 /sync 값) = 계정 전체 기준 시간당 카운트
            val hourlyCount = scrollCounts.hourlyCount(now) + otherDevicesLastHour
            lastHourlyCount = hourlyCount

            Log.d(TAG, "스크롤 카운트 — hourly: $hourlyCount/$hourlyLimit (다른 기기 $otherDevicesLastHour), daily: $dailyCount/$dailyLimit")

            // 한도 검사 — 스크롤 직후. (3주차: 서버 /sync 값을 반영한 직후에도 같은 함수를 호출)
            checkLimits(hourlyCount, dailyCount)
        }
    }

    // 한도 검사 — 현재 카운트를 한도와 비교(≥)해서 필요하면 팝업을 띄운다. 팝업을 띄웠으면 true.
    // 스크롤할 때마다 호출되고, 카운트가 다른 경로(서버 동기화 등)로 바뀐 직후에도 그대로 호출할 수 있다.
    // hourly 팝업이 뜨면 daily 는 이번에 검사하지 않는다(팝업은 한 번에 하나) — 기존 동작 그대로.
    private suspend fun checkLimits(hourlyNow: Int, dailyNow: Int): Boolean {
        // hourly milestone 하향 조정 — 슬라이딩 윈도우로 카운트가 줄면 milestone 도 따라 낮춘다.
        //  (예전엔 "한도 미만으로 떨어졌을 때만" 리셋해서, 한도 위에서 카운트가 줄었다 다시 오르는
        //   binge 중에는 milestone 이 높은 값에 stuck 돼 재경고가 안 뜨던 문제가 있었음.)
        //  - 한도 미만으로 떨어지면 완전 리셋(-1): 다음에 다시 limit 도달 시 "첫 팝업"부터.
        //  - 한도 이상이지만 현재 카운트가 milestone 보다 낮아졌으면, 현재 카운트의 step 레벨로 낮춤
        //    → 거기서 다시 STEP 만큼 오르면 재경고. (예: limit50, 63→milestone60 후 55로 감소 → milestone50,
        //       다시 60 도달 시 재경고)
        if (hourlyMilestone >= 0) {
            if (hourlyNow < hourlyLimit) {
                hourlyMilestone = -1
                remoteHourlyFloor = -1
                localAnsweredHourly = -1
                savePersistedState()
                postMilestone(answered = false)   // 슬라이딩 윈도우로 한도 아래로 내려감 → 서버도 -1 로 (스펙: 리셋 허용)
            } else if (hourlyNow < hourlyMilestone) {
                // 다른 기기가 이미 띄운 단계(remoteHourlyFloor) 아래로는 내리지 않는다 — 기기를 옮기는 사이 창이 조금 흘러
                // 카운트가 40→35 로 내려갔다 40 이 되면, 같은 40 팝업이 이 기기에서 또 뜨던 문제
                val stepLevel = maxOf(hourlyLimit + ((hourlyNow - hourlyLimit) / HOURLY_STEP) * HOURLY_STEP, remoteHourlyFloor)
                if (stepLevel < hourlyMilestone) {
                    hourlyMilestone = stepLevel
                    savePersistedState()
                }
            }
        }

        // hourly 체크
        val hourlyTrigger = reachedMilestone(hourlyNow, hourlyLimit, hourlyMilestone, HOURLY_STEP)
        if (hourlyTrigger != null) {
            hourlyMilestone = hourlyTrigger
            val overage = hourlyTrigger - hourlyLimit
            setPendingPopup("hourly", overage)
            withContext(Dispatchers.Main) { showLimitPopup("hourly", overage) }
            postMilestone(answered = false)   // 다른 기기가 같은 단계 팝업을 건너뛰게 (D6)
            return true
        }

        // daily 체크
        val dailyTrigger = reachedMilestone(dailyNow, dailyLimit, dailyMilestone, DAILY_STEP)
        if (dailyTrigger != null) {
            dailyMilestone = dailyTrigger
            val overage = dailyTrigger - dailyLimit
            setPendingPopup("daily", overage)
            withContext(Dispatchers.Main) { showLimitPopup("daily", overage) }
            postMilestone(answered = false)   // 다른 기기가 같은 단계 팝업을 건너뛰게 (D6)
            return true
        }
        return false
    }

    // ── 개입 상태 업로드 (스펙 §4) — 다른 기기가 /sync 로 받아 같은 팝업 생략 · 같은 시각까지 차단 ──
    // 실패해도 재시도하지 않음: 다음 팝업/Stop 때 다시 올라가고, 그 사이엔 로컬 동작에 영향 없음.
    // answered=false: 팝업을 띄웠다(다른 기기는 같은 단계 팝업 생략). answered=true: 사용자가 답했다(Stop/계속보기)
    // → 서버가 lastAnsweredMilestone 갱신 + 다른 기기에 COUNT_UPDATED → 그 기기에 남은 같은 팝업이 닫힌다.
    private fun postMilestone(answered: Boolean) {
        val h = hourlyMilestone
        val d = dailyMilestone
        if (answered) { localAnsweredHourly = h; localAnsweredDaily = d }
        uploadedHourlyMilestone = h
        uploadedDailyMilestone = d
        serviceScope.launch {
            val body = org.json.JSONObject()
                .put("deviceId", com.example.short_cut.DeviceId.get(this@ShortCutAccessibilityService))
                .put("hourly", h).put("daily", d)
                .put("answered", answered)
            val res = com.example.short_cut.authedRequest("POST", "/milestone", body)
            Log.d(TAG, "POST /milestone hourly=$h daily=$d answered=$answered → ${res.code}")
        }
    }

    private fun postBlock(blockUntil: Long) {
        serviceScope.launch {
            val body = org.json.JSONObject()
                .put("deviceId", com.example.short_cut.DeviceId.get(this@ShortCutAccessibilityService))
                .put("blockUntil", blockUntil)
            val res = com.example.short_cut.authedRequest("POST", "/block", body)
            Log.d(TAG, "POST /block until=$blockUntil → ${res.code}")
        }
    }

    // 서버가 준 milestone 값을 받아도 되는지.
    //  - 서버가 기록 시각(at)을 주면: 시간당은 최근 1시간 안, 일간은 오늘 것만 받는다 (정확).
    //  - 시각이 없으면: 현재 카운트로 도달 가능한 단계까지만 받는다 (그보다 높으면 낡은 값으로 본다).
    //    한계: 기기를 옮기는 사이 창이 흘러 40→35 가 되면 방금 띄운 40 도 버려져 같은 팝업이 한 번 더 뜰 수 있다.
    //    반대로 느슨하게 하면(2단계 위까지 허용) 한 시간 전의 60 을 받아 50 팝업이 아예 안 뜨는데(실기기 재현),
    //    "안 뜨는 것" 보다 "한 번 더 뜨는 것" 이 낫다. 정확한 판단은 서버가 시각(At)을 주면 된다.
    private fun acceptRemoteMilestone(value: Int, at: Long, stageNow: Int, limit: Int, step: Int, freshMs: Long, now: Long): Int {
        if (value < 0) return -1
        if (at > 0) return if (now - at <= freshMs) value else -1
        return if (value <= stageNow) value else -1
    }

    // 카운트가 지금 도달해 있는 단계 (limit, limit+step, ...). 한도 미만이면 -1.
    private fun stageOf(count: Int, limit: Int, step: Int): Int =
        if (limit <= 0 || count < limit) -1 else limit + ((count - limit) / step) * step

    // 이번에 팝업을 띄워야 하는 milestone 값. 아직 아니면 null. (hourly/daily 공통 — 비교는 전부 ≥)
    //  - milestone = -1 (오늘/이번 윈도우 첫 팝업, 또는 서비스 재시작 · 슬라이딩 윈도우 리셋 직후):
    //    카운트가 이미 limit+N*STEP 이상이면 가장 가까운 STEP 배수로 맞춘다.
    //    (아니면 limit → limit+STEP → ... 까지 매 스크롤마다 popup 폭주)
    //  - milestone ≥ 0: 직전 팝업에서 STEP 만큼 더 올라야 다음 팝업.
    //    스크롤은 1씩 오르므로 결과는 항상 milestone+STEP — 기존과 동일.
    //    카운트가 한 번에 여러 STEP 을 건너뛴 경우(서버 동기화로 값이 점프)에는 현재 단계로 바로 맞춰서
    //    팝업이 1번만 뜬다. (아니면 이후 스크롤마다 밀린 단계 수만큼 팝업이 연달아 뜸)
    private fun reachedMilestone(count: Int, limit: Int, milestone: Int, step: Int): Int? {
        if (count < limit) return null
        if (milestone < 0) return limit + ((count - limit) / step) * step
        val next = milestone + step
        if (count < next) return null
        return next + ((count - next) / step) * step
    }

    // ── Popup 빌더 ────────────────────────────────────────────

    // overage(=초과 분) 에 따라 popup 의 제목/본문 결정
    private fun buildPopupTexts(type: String, overage: Int): Pair<String, String> {
        return when (type) {
            "daily" -> {
                val title = "Daily Limit 초과"
                val body = if (overage == 0) {
                    "Daily limit을 초과했습니다.\n계속 보시겠습니까?"
                } else {
                    "오늘 목표보다 ${overage}회 더 보셨습니다.\n계속 보시겠습니까?"
                }
                title to body
            }
            "hourly" -> {
                val title = "Hourly Limit 초과"
                val body = if (overage == 0) {
                    "Hourly limit을 초과했습니다.\n계속 보시겠습니까?"
                } else {
                    "${overage}회 더 보셨습니다.\n계속 보시겠습니까?"
                }
                title to body
            }
            else -> "알림" to ""
        }
    }

    // 한도 초과 popup — type 과 모드(normal/hard) 에 따라 다른 popup 으로 분기
    // hard 모드 + hourly 만 3단계 하드 popup. daily 와 normal 모드는 표준 popup.
    private fun showLimitPopup(type: String, overage: Int) {
        if (type == "hourly" && isHardMode()) {
            showHourlyPopupHard(overage)
        } else {
            showLimitPopupNormal(type, overage)
        }
    }

    // 표준 popup (normal 모드 or daily)
    private fun showLimitPopupNormal(type: String, overage: Int) {
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = makeStandardPopupView(
                title = title,
                message = body,
                stopText = "그만보기",
                ignoreText = "계속보기",
                onStopClick = { executeStop(type) },
                onIgnoreClick = { executeIgnore(type) }
            )
            addPopupView(view, standardCenterParams(280))
        }
    }

    // 5분 차단 안내 popup — 버튼 없이 3초 후 자동 dismiss (튕겨내기[BACK]는 호출부에서 그대로 수행)
    private fun showBlockPopup(remainingSec: Int) {
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
            view.findViewById<TextView>(R.id.popupTitle).text = "그만보기를 눌렀습니다"
            view.findViewById<TextView>(R.id.popupMessage).text = "5분간 진입금지!"
            view.findViewById<LinearLayout>(R.id.popupButtonRow).visibility = View.GONE
            blockPopupShowing = true
            addPopupView(view, standardCenterParams(280))
            mainHandler.postDelayed({ dismissAllPopups() }, 3000L)
        }
    }

    // ── 하드 모드 — hourly 전용, 3단계 ──────────
    //   1단계 (처음 초과, overage == 0): 일반 팝업 (normal 모드와 동일)
    //   2단계 (초과 ~ 2배 이하, 0 < overage <= hourlyLimit): 수학(variant 6) 제외한 variant 1~5 중 랜덤
    //   3단계 (2배 초과, overage > hourlyLimit): 수학 문제(variant 6) 만

    // 사용자가 설정 탭에서 "하드 모드" 선택했는지 확인
    private fun isHardMode(): Boolean {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("appMode", "normal") == "hard"
    }

    private fun showHourlyPopupHard(overage: Int) {
        // overage = (현재 시간당 카운트 - hourlyLimit). 첫 초과 팝업은 overage == 0.
        // "2배 초과" = overage > hourlyLimit (카운트 > 2 × hourlyLimit ⟺ hourlyLimit + overage > 2 × hourlyLimit)
        when {
            overage == 0 -> {
                // 1단계 — 처음 초과: 일반 팝업
                Log.d(TAG, "하드 모드 hourly popup — 첫 초과 → 일반 팝업")
                showLimitPopupNormal("hourly", overage)
            }
            overage > hourlyLimit -> {
                // 3단계 — limit 2배 초과: 수학 문제만
                Log.d(TAG, "하드 모드 hourly popup — 2배 초과 → 수학(variant 6)")
                showHardMath(overage)
            }
            else -> {
                // 2단계 — 초과 ~ 2배 이하 추가 팝업: 수학 제외 1~5 랜덤
                val variant = (1..5).random()
                Log.d(TAG, "하드 모드 hourly popup — 추가 팝업 → variant=$variant")
                when (variant) {
                    1 -> showHardCountdown(overage)
                    2 -> showHardVerticalGrid(overage)
                    3 -> showHardSwappedLabels(overage)
                    4 -> showHardStacked(overage)
                    5 -> showHardHorizontalScroll(overage)
                }
            }
        }
    }

    // ── Variant 1 — 계속보기 버튼 60초 카운트다운 후 활성화 ──────
    private fun showHardCountdown(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body

            val btnStop = view.findViewById<Button>(R.id.btnStop)
            val btnIgnore = view.findViewById<Button>(R.id.btnIgnore)
            val countdownTv = view.findViewById<TextView>(R.id.ignoreCountdown)

            btnStop.text = "그만보기"
            btnStop.setOnClickListener { executeStop(type) }

            btnIgnore.text = "계속보기"
            btnIgnore.isEnabled = false
            btnIgnore.alpha = 0.5f
            btnIgnore.setOnClickListener { executeIgnore(type) }

            countdownTv.visibility = View.VISIBLE
            countdownTv.text = "60"

            addPopupView(view, standardCenterParams(280))

            // 1초마다 카운트다운 — popup 이 dismiss 되면 중단
            val tickRunnable = object : Runnable {
                var remaining = 60
                override fun run() {
                    if (!popupViews.contains(view)) return  // dismiss 됐으면 중단
                    remaining--
                    if (remaining > 0) {
                        countdownTv.text = "$remaining"
                        mainHandler.postDelayed(this, 1000)
                    } else {
                        countdownTv.visibility = View.GONE
                        btnIgnore.isEnabled = true
                        btnIgnore.alpha = 1f
                    }
                }
            }
            mainHandler.postDelayed(tickRunnable, 1000)
        }
    }

    // ── Variant 2 — 세로 스크롤. 처음엔 일반 팝업과 똑같은 크기/버튼 위치로 보이되 둘 다 "그만보기"(=stop).
    //   ScrollView 높이를 딱 한 줄로 고정해 첫 줄만 노출 → 아래로 스크롤하면 나오는 줄들 중 1곳에만 진짜 계속보기. ──
    private fun showHardVerticalGrid(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_vertical, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            val container = view.findViewById<LinearLayout>(R.id.rowsContainer)

            val density = resources.displayMetrics.density
            val rowCount = 10
            // 진짜 계속보기 — 첫 줄(0)은 제외, 1..9 줄 중 한 곳의 좌/우 랜덤
            val realRow = (1 until rowCount).random()
            val realRight = (0..1).random() == 1

            val red = android.graphics.Color.parseColor("#FF4444")   // 일반 팝업 그만보기 색(왼쪽)
            val gray = android.graphics.Color.parseColor("#888888")  // 일반 팝업 계속보기 색(오른쪽)
            val gapPx = (20 * density).toInt()                       // 일반 팝업과 동일한 버튼 간격
            val btnHeightPx = (48 * density).toInt()
            val rowMarginPx = (4 * density).toInt()

            fun cell(label: String, bg: Int, marginEnd: Int, action: () -> Unit): Button =
                Button(this).apply {
                    text = label
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(bg)
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(0, btnHeightPx, 1f)
                        .apply { setMargins(0, 0, marginEnd, 0) }
                }

            for (r in 0 until rowCount) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, rowMarginPx, 0, rowMarginPx) }
                }
                // 모든 줄을 빨강|회색 쌍으로 — 첫 줄은 일반 팝업과 동일하게 보이고, 둘 다 그만보기.
                val leftReal = (r == realRow && !realRight)
                val rightReal = (r == realRow && realRight)
                row.addView(cell(if (leftReal) "계속보기" else "그만보기", red, gapPx) {
                    if (leftReal) executeIgnore(type) else executeStop(type)
                })
                row.addView(cell(if (rightReal) "계속보기" else "그만보기", gray, 0) {
                    if (rightReal) executeIgnore(type) else executeStop(type)
                })
                container.addView(row)
            }

            // ScrollView 높이를 딱 한 줄로 고정 → 첫 줄(버튼 2개)만 보이고 나머지는 아래로 스크롤해야 노출
            val scrollView = container.parent as android.widget.ScrollView
            scrollView.layoutParams = scrollView.layoutParams.apply {
                height = btnHeightPx + rowMarginPx * 2
            }

            addPopupView(view, standardCenterParams(280))
        }
    }

    // ── Variant 3 — 색상 위치 고정, 글자만 swap (red=계속보기, gray=그만보기) ──
    private fun showHardSwappedLabels(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            // btnStop (red, 왼쪽) 라벨 = "계속보기" → 클릭 시 ignore
            // btnIgnore (gray, 오른쪽) 라벨 = "그만보기" → 클릭 시 stop
            val view = makeStandardPopupView(
                title = title,
                message = body,
                stopText = "계속보기",
                ignoreText = "그만보기",
                onStopClick = { executeIgnore(type) },  // 라벨 따라 동작
                onIgnoreClick = { executeStop(type) }
            )
            addPopupView(view, standardCenterParams(280))
        }
    }

    // ── Variant 4 — 10개 popup 대각선 스택. 1개(랜덤, 첫번째 제외) 만 라벨 swap ──
    private fun showHardStacked(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()

            val popupCount = 10
            val trapIdx = (1..9).random()  // 0 (맨 아래) 제외
            val density = resources.displayMetrics.density
            val offsetPx = (16 * density).toInt()

            // 각 popup 생성. 맨 아래(idx=0) 부터 맨 위(idx=9) 까지 순서대로 addView.
            // 따라서 idx=9 가 topmost. 사용자가 클릭하는 건 idx=9 부터 시작.
            for (idx in 0 until popupCount) {
                val isTrap = (idx == trapIdx)
                val view = if (isTrap) {
                    makeStandardPopupView(
                        title = title,
                        message = body,
                        stopText = "계속보기",  // red 위치에 계속보기 라벨
                        ignoreText = "그만보기",  // gray 위치에 그만보기 라벨
                        onStopClick = { handleStackedIgnore(type) },  // 라벨 따라 동작
                        onIgnoreClick = { executeStop(type) }
                    )
                } else {
                    makeStandardPopupView(
                        title = title,
                        message = body,
                        stopText = "그만보기",
                        ignoreText = "계속보기",
                        onStopClick = { executeStop(type) },
                        onIgnoreClick = { handleStackedIgnore(type) }
                    )
                }

                val isTop = (idx == popupCount - 1)
                val params = standardCenterParams(260).apply {
                    x = idx * offsetPx
                    y = idx * offsetPx
                    if (!isTop) {
                        // 아래 popup 들은 터치 비활성 — 맨 위만 클릭 가능
                        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    }
                }
                addPopupView(view, params)
            }
        }
    }

    // Stacked 모드에서 계속보기 클릭 시: 현재 top popup 하나만 dismiss, 모두 사라지면 ignore 처리
    private fun handleStackedIgnore(type: String) {
        if (popupViews.isEmpty()) return
        val top = popupViews.last()
        popupViews.remove(top)
        try { windowManager?.removeView(top) } catch (_: Exception) {}

        if (popupViews.isEmpty()) {
            // 마지막 popup 도 사라짐 → 진짜 ignore 처리
            isPopupShowing = false
            removeScrim()
            sendViolation(type, lastHourlyCount, dailyCount, "ignore")
            postMilestone(answered = true)
            clearPendingPopup()
            Log.d(TAG, "스택 popup 전부 처리 → ignore 완료")
        } else {
            // 다음 top popup 의 터치를 활성화
            val newTop = popupViews.last()
            try {
                val params = newTop.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager?.updateViewLayout(newTop, params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "스택 popup 터치 활성화 실패 — ${e.message}")
            }
        }
    }

    // ── Variant 5 — 가로 스크롤. 처음엔 일반 팝업과 똑같은 크기/버튼 위치로 보이되 둘 다 "그만보기"(=stop).
    //   버튼 폭을 딱 2개만 보이게 고정 → 오른쪽으로 스크롤하면 나오는 버튼들 중 단 1개만 진짜 계속보기. ──
    private fun showHardHorizontalScroll(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)
        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_horizontal, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            val container = view.findViewById<LinearLayout>(R.id.buttonsContainer)

            val density = resources.displayMetrics.density
            val total = 20
            // 진짜 계속보기 — 처음 보이는 2칸(0,1) 밖에 한 개만 숨김
            val ignoreIdx = (2 until total).random()

            // 일반 팝업과 동일: 폭 280dp, padding 24, 버튼 간격 20dp → 버튼 폭 = (280-48-20)/2
            val popupWidthDp = 280
            val gapDp = 20
            val btnWidthPx = (((popupWidthDp - 48 - gapDp) / 2) * density).toInt()
            val gapPx = (gapDp * density).toInt()

            val red = android.graphics.Color.parseColor("#FF4444")   // 일반 팝업 그만보기 색(왼쪽)
            val gray = android.graphics.Color.parseColor("#888888")  // 일반 팝업 계속보기 색(오른쪽)

            for (i in 0 until total) {
                val isReal = (i == ignoreIdx)
                val btn = Button(this).apply {
                    text = if (isReal) "계속보기" else "그만보기"
                    setTextColor(android.graphics.Color.WHITE)
                    // 빨강|회색 번갈아 → 첫 화면(0,1)이 빨강+회색 = 일반 팝업 모양
                    setBackgroundColor(if (i % 2 == 0) red else gray)
                    setOnClickListener { if (isReal) executeIgnore(type) else executeStop(type) }
                    layoutParams = LinearLayout.LayoutParams(btnWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, 0, gapPx, 0) }
                }
                container.addView(btn)
            }

            addPopupView(view, standardCenterParams(popupWidthDp))
        }
    }

    // ── Variant 6 — 수학 문제. 정답이면 그만보기/계속보기 둘 다 활성화(선택권), 오답이면 그만보기만 활성화 ──
    // 5지선다 중 1개 정답. 한 번 선택하면 다른 선택지는 잠금 — 재시도 불가.
    private fun showHardMath(overage: Int) {
        val type = "hourly"
        val (title, body) = buildPopupTexts(type, overage)

        val (questionText, correctAnswer) = generateMathProblem()
        val wrongAnswers = generateWrongAnswers(correctAnswer, 4)
        val allChoices = (wrongAnswers + correctAnswer).shuffled()

        mainHandler.post {
            dismissAllPopups()
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup_math, null)
            view.findViewById<TextView>(R.id.popupTitle).text = title
            view.findViewById<TextView>(R.id.popupMessage).text = body
            view.findViewById<TextView>(R.id.mathQuestion).text = questionText
            val container = view.findViewById<LinearLayout>(R.id.choicesContainer)

            val btnStop = view.findViewById<Button>(R.id.btnStop)
            val btnIgnore = view.findViewById<Button>(R.id.btnIgnore)

            btnStop.text = "그만보기"
            btnStop.isEnabled = false
            btnStop.alpha = 0.5f
            btnStop.setOnClickListener { executeStop(type) }

            btnIgnore.text = "계속보기"
            btnIgnore.isEnabled = false
            btnIgnore.alpha = 0.5f
            btnIgnore.setOnClickListener { executeIgnore(type) }

            val density = resources.displayMetrics.density
            val uniformColor = android.graphics.Color.parseColor("#555555")
            val choiceButtons = mutableListOf<Button>()
            var answered = false

            for (choice in allChoices) {
                val btn = Button(this).apply {
                    text = choice.toString()
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(uniformColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0) }
                    setOnClickListener { clicked ->
                        if (answered) return@setOnClickListener
                        answered = true
                        // 다른 선택지 잠금
                        choiceButtons.forEach { it.isEnabled = false }
                        // 내가 선택한 답 하이라이트 — 파란 배경 + 흰 테두리로 어떤 답을 골랐는지 표시
                        val highlight = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.parseColor("#1E88E5"))
                            cornerRadius = 8 * density
                            setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                        }
                        clicked.background = highlight
                        clicked.alpha = 1f  // 비활성화로 흐려지지 않게 선택지만 또렷하게
                        if (choice == correctAnswer) {
                            // 정답 — 그만보기/계속보기 둘 다 활성화해 사용자가 직접 선택
                            btnStop.isEnabled = true
                            btnStop.alpha = 1f
                            btnIgnore.isEnabled = true
                            btnIgnore.alpha = 1f
                        } else {
                            // 오답 — 그만보기만 활성화
                            btnStop.isEnabled = true
                            btnStop.alpha = 1f
                        }
                    }
                }
                choiceButtons.add(btn)
                container.addView(btn)
            }

            addPopupView(view, standardCenterParams(360))
        }
    }

    // 사칙연산 문제 생성 — 3개 피연산자 + 2개 연산자, 피연산자는 최대 2자리(1..99)
    // 난이도 규약: 연산자 두 개 중 하나는 무조건 곱셈(×), 나머지 하나는 덧셈/뺄셈.
    //   곱셈이 앞(op1)에 올지 뒤(op2)에 올지는 랜덤.
    // 예: "11 + 29 × 6 = ?" (× 우선 적용해서 11 + 174 = 185)
    // ÷ 는 다항식에서 정수 보장이 까다로워 제외.
    private fun generateMathProblem(): Pair<String, Long> {
        val a = (1..99).random()
        val b = (1..99).random()
        val c = (1..99).random()

        val addSub = listOf("+", "-").random()   // 나머지 한 자리는 덧셈/뺄셈 중 하나
        val mulFirst = (0..1).random() == 0       // 곱셈을 앞/뒤 어디에 둘지 랜덤
        val op1 = if (mulFirst) "×" else addSub
        val op2 = if (mulFirst) addSub else "×"

        fun apply(x: Long, op: String, y: Long): Long = when (op) {
            "+" -> x + y
            "-" -> x - y
            "×" -> x * y
            else -> error("unknown op: $op")
        }

        // 곱셈이 항상 정확히 하나이므로 × 위치를 먼저 계산해 우선순위 반영
        val result: Long = if (mulFirst) {
            apply(a.toLong() * b, op2, c.toLong())   // (a×b) op2 c
        } else {
            apply(a.toLong(), op1, b.toLong() * c)   // a op1 (b×c)
        }

        return "$a $op1 $b $op2 $c = ?" to result
    }

    // 정답의 1의 자리는 그대로 두고, 나머지 자릿수는 모두 다른 숫자로 바꾼 오답을 count 개 생성
    // (1자리 정답이면 앞에 1~2 자리를 새로 붙여 만듦)
    private fun generateWrongAnswers(correct: Long, count: Int): List<Long> {
        val absValue = if (correct < 0) -correct else correct
        val sign = if (correct < 0) -1L else 1L
        val digits = absValue.toString().toCharArray()
        val onesIdx = digits.size - 1
        val candidates = linkedSetOf<Long>()

        var attempt = 0
        while (candidates.size < count && attempt < 500) {
            attempt++
            val candidate: Long = if (digits.size == 1) {
                // 1자리 정답 — 앞에 자릿수 1~2개 prefix
                val prefixLen = (1..2).random()
                val sb = StringBuilder()
                for (k in 0 until prefixLen) {
                    sb.append(if (k == 0) ('1'..'9').random() else ('0'..'9').random())
                }
                sb.append(digits[0])
                sb.toString().toLong() * sign
            } else {
                val newDigits = digits.copyOf()
                for (i in 0 until onesIdx) {
                    val orig = newDigits[i]
                    var pick: Char
                    val pool = if (i == 0) ('1'..'9') else ('0'..'9')  // 맨 앞은 0 불가
                    do { pick = pool.random() } while (pick == orig)
                    newDigits[i] = pick
                }
                String(newDigits).toLong() * sign
            }
            if (candidate != correct) candidates.add(candidate)
        }
        return candidates.toList()
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────

    // overlay_popup.xml 을 inflate 해서 라벨/색/콜백 채워 반환
    private fun makeStandardPopupView(
        title: String,
        message: String,
        stopText: String,
        ignoreText: String,
        onStopClick: () -> Unit,
        onIgnoreClick: () -> Unit
    ): View {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_popup, null)
        view.findViewById<TextView>(R.id.popupTitle).text = title
        view.findViewById<TextView>(R.id.popupMessage).text = message
        view.findViewById<Button>(R.id.btnStop).apply {
            text = stopText
            setOnClickListener { onStopClick() }
        }
        view.findViewById<Button>(R.id.btnIgnore).apply {
            text = ignoreText
            setOnClickListener { onIgnoreClick() }
        }
        return view
    }

    // 화면 중앙, 너비 widthDp dp, 높이 wrap_content 인 popup params
    private fun standardCenterParams(widthDp: Int): WindowManager.LayoutParams {
        val widthPx = (widthDp * resources.displayMetrics.density).toInt()
        return WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    // popup view 를 list 에 등록 + WindowManager 에 추가
    private fun addPopupView(view: View, params: WindowManager.LayoutParams) {
        try {
            // [B2] 팝업 뒤에 차단막을 먼저 깐다 — 팝업 창은 FLAG_NOT_TOUCH_MODAL 이라 카드 밖 터치가 뒤(쇼츠)로
            //   통과해 팝업이 떠 있는데도 스크롤이 됐다. 차단막이 카드 밖 터치를 전부 흡수한다.
            //   차단막은 시스템 바(내비게이션 바) 영역은 덮지 않으므로 홈/뒤로/최근 앱은 그대로 동작하고,
            //   나가면 popup 은 숨겨지되 pending 상태는 유지되어 타겟 앱 재진입 시 복원된다(기존 동작).
            //   같은 타입 창은 나중에 추가한 것이 위에 오므로 팝업보다 먼저 추가해야 팝업이 가려지지 않는다.
            ensureScrim()
            windowManager?.addView(view, params)
            popupViews.add(view)
            isPopupShowing = true
            Log.d(TAG, "popup add — 현재 ${popupViews.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "popup add 실패 — ${e.message}")
        }
    }

    // 전체화면 차단막을 (없으면) 추가. 모든 팝업 뒤에 깔려 카드 밖 터치를 전부 흡수 → 뒤 화면 조작 불가.
    private fun ensureScrim() {
        if (scrimView != null) return
        val scrim = View(this).apply {
            setBackgroundColor(0x40000000)  // 살짝 어둡게 — 모달(뒤 비활성) 상태 표시
            setOnTouchListener { _, _ -> true }  // 카드 밖 모든 터치 흡수(소비)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE 만 — 키(뒤로가기) 포커스는 안 가져가되 터치는 받도록(NOT_TOUCHABLE 미설정)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 상태 바 · 내비게이션 바 안쪽으로만 — 3버튼 내비의 홈/뒤로 버튼은 차단막 밖이라 눌린다.
            // (제스처 내비의 홈 스와이프는 시스템이 먼저 가로채므로 어차피 막히지 않음)
            fitInsetsTypes = android.view.WindowInsets.Type.systemBars()
            fitInsetsSides = android.view.WindowInsets.Side.all()
        }
        try {
            windowManager?.addView(scrim, params)
            scrimView = scrim
        } catch (e: Exception) {
            Log.e(TAG, "scrim add 실패 — ${e.message}")
        }
    }

    // 차단막 제거
    private fun removeScrim() {
        scrimView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        scrimView = null
    }

    // ── 사용자 응답 처리 액션 ─────────────────────────────────

    // 그만보기 — 5분 차단 시작, popup 전부 dismiss, 화면 빠져나가기.
    // 인스타는 앱 전체가 아닌 릴스만 차단 대상 → BACK 으로 릴스 화면만 닫고 IG 일반 화면(피드/검색/DM)은 계속 이용 가능.
    // YT/TikTok 은 기존대로 HOME (이쪽은 앱 자체가 쇼츠 비중 큼).
    // 5분 차단 기간 중 다시 릴스/쇼츠에 들어가면 onAccessibilityEvent 의 stopUntilMs 분기가 BACK 으로 또 밀어냄.
    private fun executeStop(type: String) {
        val now = System.currentTimeMillis()
        stopUntilMs = now + STOP_BLOCK_MS
        savePersistedState()
        postBlock(stopUntilMs)   // 다른 기기도 같은 시각까지 차단 (D6)
        postMilestone(answered = true)
        sendViolation(type, lastHourlyCount, dailyCount, "stop")
        clearPendingPopup()
        dismissAllPopups()
        Log.d(TAG, "그만보기 선택 → 5분 차단 시작")
        // Stop → 미전송분 즉시 업로드
        flushIfUnsent("그만보기")
        // 현재 쇼츠 모드 detector 중 인스타가 있으면 BACK, 아니면 HOME
        val isInstagram = detectors.any {
            it.packageName == "com.instagram.android" && it.inShortsMode
        }
        performGlobalAction(if (isInstagram) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)
    }

    // 계속보기 (variant 4 제외) — popup 전부 dismiss + violation 전송
    private fun executeIgnore(type: String) {
        sendViolation(type, lastHourlyCount, dailyCount, "ignore")
        postMilestone(answered = true)   // 다른 기기에 남은 같은 팝업이 닫히게
        clearPendingPopup()
        dismissAllPopups()
        Log.d(TAG, "계속보기 선택 → 다음 milestone 까지 대기")
    }

    // ── 상태 저장 헬퍼 ────────────────────────────────────────

    private fun savePersistedState() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .putInt(PK_DAILY_MILESTONE, dailyMilestone)
            .putInt(PK_HOURLY_MILESTONE, hourlyMilestone)
            .putLong(PK_STOP_UNTIL, stopUntilMs)
            .putLong(PK_TODAY_START, todayStartMs)
            .apply()
        publishDailyCount()
    }

    // 오늘 카운트(서버 합계 반영본)를 prefs 에 공개 — 홈 탭 표시용. 스크롤 · 서버 시드 · /sync 때마다 호출.
    private fun publishDailyCount() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(PK_SYNCED_DAILY_COUNT, dailyCount)
            .putLong(PK_SYNCED_DAILY_DAY, todayStartMs)
            .apply()
    }

    // 미응답 popup 기록 — YT 강제종료 후에도 재진입 시 같은 popup 복원
    private fun setPendingPopup(type: String, overage: Int) {
        pendingPopupType = type
        pendingPopupOverage = overage
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PK_PENDING_TYPE, type)
            .putInt(PK_PENDING_OVERAGE, overage)
            .apply()
    }

    // 사용자 응답(Stop/Ignore) 시 pending 해제
    private fun clearPendingPopup() {
        pendingPopupType = null
        pendingPopupOverage = 0
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(PK_PENDING_TYPE)
            .remove(PK_PENDING_OVERAGE)
            .apply()
    }

    // ── 서버 통신 ─────────────────────────────────────────────

    // Firebase ID 토큰 가져오기
    private suspend fun getFirebaseToken(): String? {
        return try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Log.e(TAG, "Firebase 토큰 가져오기 실패 — ${e.message}")
            null
        }
    }

    // violation 발생 시 — 영속 큐에 적재 후 전송 트리거.
    // [변경됨] 예전엔 즉시 fire-and-forget POST 라 토큰/네트워크 실패 시 그대로 유실(월간 '그만보기/무시' 누락)됐다.
    //   이제 userlog 큐와 같은 패턴: prefs 에 먼저 적재 → 전송 성공이 확인된 항목만 큐에서 제거.
    //   각 violation 에 고유 id 부여(서버 dedup/멱등성용 — 재전송돼도 서버가 같은 id 를 중복 집계하지 않게).
    private fun sendViolation(limitType: String, hourlyScrollCount: Int, dailyScrollCount: Int, action: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val userId = prefs.getString("userId", "unknown") ?: "unknown"
        val v = PendingViolation(
            id = java.util.UUID.randomUUID().toString(),
            userId = userId,
            ts = System.currentTimeMillis(),
            limitType = limitType,
            hourlyScrollCount = hourlyScrollCount,
            dailyScrollCount = dailyScrollCount,
            action = action,
            platform = platformOf(lastScrollPkg)
        )
        // prefs 에 먼저 영속화(동기) — 큐에 들어간 뒤에만 전송을 시도하므로, 전송 전에 프로세스가 죽어도 유실되지 않음.
        synchronized(violationLock) { appendViolationLocked(v) }
        serviceScope.launch { sendPendingViolations() }
    }

    // ── violation pending 큐 (prefs 영속) ───────────────────────
    // userlog 큐와 동일한 패턴 — 전송 성공이 확인된 항목만 큐에서 빠짐. 아래 read/write/append 헬퍼는
    // 모두 synchronized(violationLock) 안에서만 호출해야 함.
    private data class PendingViolation(
        val id: String,
        val userId: String,
        val ts: Long,
        val limitType: String,
        val hourlyScrollCount: Int,
        val dailyScrollCount: Int,
        val action: String,
        val platform: String
    )

    private fun readViolationsLocked(): List<PendingViolation> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PK_PENDING_VIOLATIONS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val id = o.optString("id").takeIf { it.isNotEmpty() }
                        ?: java.util.UUID.randomUUID().toString()
                    PendingViolation(
                        id = id,
                        userId = o.optString("userId", "unknown"),
                        ts = o.optLong("ts"),
                        limitType = o.optString("limitType", ""),
                        hourlyScrollCount = o.optInt("hourlyScrollCount"),
                        dailyScrollCount = o.optInt("dailyScrollCount"),
                        action = o.optString("action", ""),
                        platform = o.optString("platform", "unknown")
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeViolationsLocked(list: List<PendingViolation>) {
        val arr = org.json.JSONArray()
        list.forEach { v ->
            arr.put(org.json.JSONObject()
                .put("id", v.id)
                .put("userId", v.userId)
                .put("ts", v.ts)
                .put("limitType", v.limitType)
                .put("hourlyScrollCount", v.hourlyScrollCount)
                .put("dailyScrollCount", v.dailyScrollCount)
                .put("action", v.action)
                .put("platform", v.platform))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PK_PENDING_VIOLATIONS, arr.toString()).apply()
    }

    private fun appendViolationLocked(v: PendingViolation) {
        writeViolationsLocked(readViolationsLocked() + v)
    }

    // /violations 1건 전송(블로킹). 성공하면 true. violationId 를 함께 보내 서버가 재전송 중복을 dedup 하도록 함.
    private fun postViolationBlocking(token: String, v: PendingViolation): Boolean {
        return try {
            val json = """
            {
                "userId": "${v.userId}",
                "violationId": "${v.id}",
                "timestamp": ${v.ts},
                "limitType": "${v.limitType}",
                "hourlyScrollCount": ${v.hourlyScrollCount},
                "dailyScrollCount": ${v.dailyScrollCount},
                "action": "${v.action}",
                "platform": "${v.platform}"
            }
        """.trimIndent()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/violations")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            val code = response.code
            response.close()
            Log.d(TAG, "violation 전송 — id=${v.id}, action=${v.action}, success=$ok ($code)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "violation 전송 실패 — ${e.message}")
            false
        }
    }

    // pending violation 큐를 서버로 전송. 성공분만 큐에서 제거, 실패분은 남겨 다음 기회에 재시도.
    // sendPendingUserLogs 와 동일 구조 — isSendingViolations 가드로 한 번에 하나만 실행.
    private suspend fun sendPendingViolations() {
        if (!isSendingViolations.compareAndSet(false, true)) return  // 이미 전송 중
        try {
            while (true) {
                val queue = synchronized(violationLock) { readViolationsLocked() }
                if (queue.isEmpty()) return

                val token = getFirebaseToken()
                if (token == null) {
                    Log.e(TAG, "violation 전송 보류 — 토큰 없음 (다음 기회 재시도)")
                    return
                }

                // 네트워크 I/O 는 락 밖에서
                val failed = ArrayList<PendingViolation>()
                for (v in queue) {
                    if (!postViolationBlocking(token, v)) failed.add(v)
                }

                val keepLooping = synchronized(violationLock) {
                    val current = readViolationsLocked()
                    val newer = if (current.size > queue.size) current.subList(queue.size, current.size).toList()
                    else emptyList()
                    writeViolationsLocked(failed + newer)
                    // 실패가 있으면 네트워크 불안정으로 보고 중단(다음 기회 재시도). 없으면 newer 만큼 더 처리.
                    failed.isEmpty() && newer.isNotEmpty()
                }
                if (!keepLooping) return
            }
        } finally {
            isSendingViolations.set(false)
        }
    }

    // 자정 롤오버 시 호출 — 어제 날짜와 "그때 적용됐던" hourly/daily limit 스냅샷을 서버에 보내
    // 과거 통계가 현재 한도로 덮이지 않게 확정 저장(POST /stats/:userId/daily/finalize).
    // 실패는 치명적 X (서버가 재계산 가능) — 토큰 없거나 네트워크 안 되면 그냥 로그만 남기고 종료.
    private suspend fun finalizeStats(userId: String, date: String, hourlyLimitSnap: Int, dailyLimitSnap: Int) {
        try {
            val token = getFirebaseToken() ?: run {
                Log.e(TAG, "finalize 전송 실패 — 토큰 없음 (date=$date)")
                return
            }
            val json = """{"date":"$date","dailyLimit":$dailyLimitSnap,"hourlyLimit":$hourlyLimitSnap}"""
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/stats/$userId/daily/finalize")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "daily finalize 전송 완료 — date=$date, code=${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "daily finalize 전송 실패 — date=$date, ${e.message}")
        }
    }

    // [신규] pending limit 승격 + (승격됐을 때만) 서버 동기화
    // MainActivity.promoteAndSyncLimit 과 같은 목적의 서비스(백그라운드) 버전.
    //
    // [왜 필요한가]
    //   새 한도는 "다음 주 월요일 0시"부터 적용된다. 그 시각엔 앱 UI 가 닫혀 있고
    //   접근성 서비스만 떠 있는 경우가 대부분이다. 예전엔 서비스가 로컬 승격만 하고
    //   서버엔 알리지 않아, UI 를 다시 열기 전까지 서버 한도가 옛 값 그대로 남았다.
    //   (= 편집 시 즉시 push 하던 동작을 제거한 뒤 생기는 공백을 여기서 메운다.)
    //
    // [동작]
    //   승격 대상(pendingEffectiveAt 도달)이 있으면 승격 후 새 한도를 서버에 push.
    //   없으면 기존과 동일하게 로컬 정렬만 한다.
    private suspend fun promoteAndSyncLimit(userId: String) {
        val now = System.currentTimeMillis()
        val limit = userLimitDao.getLimit(userId) ?: return

        val effectiveAt = limit.pendingEffectiveAt
        val willPromote = effectiveAt != null && effectiveAt <= now
        val promotedHourly = limit.pendingHourlyLimit ?: limit.hourlyLimit
        val promotedDaily  = limit.pendingDailyLimit  ?: limit.dailyLimit

        // 로컬 승격 — 기존 동작 그대로
        userLimitDao.promoteExpiredPending(now)

        // 승격이 실제로 일어난 경우에만 서버 동기화 (네트워크는 serviceScope 로 분리)
        if (willPromote) {
            serviceScope.launch { pushLimitToServer(userId, promotedHourly, promotedDaily) }
        }
    }

    // POST /limits/:userId — 승격된 "현재 적용 한도"를 서버에 반영 (finalizeStats 와 동일 패턴)
    private suspend fun pushLimitToServer(userId: String, hourlyLimitSnap: Int, dailyLimitSnap: Int) {
        try {
            val token = getFirebaseToken() ?: run {
                Log.e(TAG, "limit 동기화 실패 — 토큰 없음")
                return
            }
            val json = """{"hourlyLimit":$hourlyLimitSnap,"dailyLimit":$dailyLimitSnap}"""
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/limits/$userId")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "limit 동기화 전송 완료 — code=${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "limit 동기화 전송 실패 — ${e.message}")
        }
    }

    // ── userlog pending 큐 (prefs 영속) ───────────────────────
    // 큐는 JSON 배열 [{"id":String,"ts":Long,"count":Int}, ...] 로 prefs 에 저장 — 앱이 강제종료돼도 살아남음.
    // 전송 성공이 확인된 항목만 큐에서 빠지므로, 네트워크/토큰 실패 시 스크롤이 유실되지 않음.
    // id 는 중복 방지(idempotency)용 고유 키 — 재전송돼도 서버가 같은 id 를 dedup 하면 중복 집계 안 됨.
    // 아래 read/write/append 헬퍼는 모두 synchronized(pendingLock) 안에서만 호출해야 함.

    private data class PendingLog(val id: String, val ts: Long, val count: Int, val appPkg: String)

    // 패키지명 → 서버 전송용 정규화된 플랫폼 이름. 구버전 큐에는 appPkg 가 비어있을 수 있음("unknown").
    private fun platformOf(pkg: String): String = when (pkg) {
        "com.google.android.youtube" -> "youtube"
        "com.instagram.android" -> "instagram"
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "tiktok"
        else -> "unknown"
    }

    private fun readPendingLocked(): List<PendingLog> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PK_PENDING_USERLOGS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    // 구버전 큐(id/appPkg 없음) 호환 — 없으면 새 id 부여, appPkg 는 빈 문자열로
                    val id = o.optString("id").takeIf { it.isNotEmpty() }
                        ?: java.util.UUID.randomUUID().toString()
                    val appPkg = o.optString("appPkg", "")
                    PendingLog(id, o.optLong("ts"), o.optInt("count"), appPkg)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writePendingLocked(list: List<PendingLog>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject()
                .put("id", p.id).put("ts", p.ts).put("count", p.count).put("appPkg", p.appPkg))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PK_PENDING_USERLOGS, arr.toString()).apply()
    }

    private fun appendPendingLocked(ts: Long, count: Int, appPkg: String) {
        val entry = PendingLog(java.util.UUID.randomUUID().toString(), ts, count, appPkg)
        writePendingLocked(readPendingLocked() + entry)
    }

    // /userlogs 1건 전송(블로킹). 성공하면 true. ts = 배치 첫 스크롤 시각(실제 발생 시각).
    // logId 를 함께 보내 서버가 재전송 중복을 dedup 하도록 함.
    private fun postUserLogBlocking(token: String, userId: String, log: PendingLog): Boolean {
        return try {
            val json = """
            {
                "userId": "$userId",
                "logId": "${log.id}",
                "timestamp": ${log.ts},
                "scrollCount": ${log.count},
                "platform": "${platformOf(log.appPkg)}",
                "deviceId": "${com.example.short_cut.DeviceId.get(this)}"
            }
        """.trimIndent()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("https://short-cut-server-production.up.railway.app/userlogs")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            val code = response.code
            response.close()
            Log.d(TAG, "userLog 전송 — id=${log.id}, count=${log.count}, ts=${log.ts}, success=$ok ($code)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "userLog 전송 실패 — ${e.message}")
            false
        }
    }

    // pending 큐를 서버로 전송. 성공분만 큐에서 제거, 실패분은 남겨 다음 기회에 재시도.
    // isSending 가드로 한 번에 하나만 실행 — 큐 앞쪽 항목을 중복 전송/중복 제거하지 않게 함.
    private suspend fun sendPendingUserLogs() {
        if (!isSending.compareAndSet(false, true)) return  // 이미 전송 중
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"
            while (true) {
                val queue = synchronized(pendingLock) { readPendingLocked() }
                if (queue.isEmpty()) return

                val token = getFirebaseToken()
                if (token == null) {
                    Log.e(TAG, "userLog 전송 보류 — 토큰 없음 (다음 기회 재시도)")
                    return
                }

                // 네트워크 I/O 는 락 밖에서 — 전송 중에도 스크롤 카운팅이 막히지 않게
                val failed = ArrayList<PendingLog>()
                for (log in queue) {
                    if (log.count <= 0) continue
                    if (!postUserLogBlocking(token, userId, log)) failed.add(log)
                }

                // 처리한 앞쪽 queue.size 개를 제거하고, 그 사이 새로 들어온 항목(newer)과 실패분을 다시 씀.
                // 앞쪽에서 제거하는 건 이 전송(isSending 가드로 단일)뿐이고 enqueue 는 뒤에만 붙으므로 안전.
                val keepLooping = synchronized(pendingLock) {
                    val current = readPendingLocked()
                    val newer = if (current.size > queue.size) current.subList(queue.size, current.size).toList()
                    else emptyList()
                    writePendingLocked(failed + newer)
                    // 실패가 있으면 네트워크 불안정으로 보고 중단(다음 flush/시작 시 재시도). 없으면 newer 만큼 더 처리.
                    failed.isEmpty() && newer.isNotEmpty()
                }
                if (!keepLooping) return
            }
        } finally {
            isSending.set(false)
        }
    }

    // 이 계정의 로그인 상태 기기가 2대 이상인지 — /devices/register 응답의 deviceCount(prefs) 기준.
    private fun isMultiDevice(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(com.example.short_cut.PK_DEVICE_COUNT, 1) >= 2

    // 배치 타이머 주기(D2): 기기 2대 이상 + 지금 쇼츠를 보는 중이면 1분, 그 외에는 기존대로 5분.
    // 타이머가 울릴 때마다 다시 계산하므로 쇼츠에서 나가면 다음 주기부터 자동으로 5분으로 돌아간다.
    private fun scheduleBatchTimer() {
        val watching = detectors.any { it.inShortsMode }
        val delayMs = if (watching && isMultiDevice()) 60 * 1000L else 5 * 60 * 1000L
        batchHandler.removeCallbacks(batchTimerRunnable)   // 중복 예약 방지 — 타이머는 항상 하나만
        batchHandler.postDelayed(batchTimerRunnable, delayMs)
    }

    // 현재 배치를 pending 큐에 영속화(즉시, 동기)하고 전송을 트리거.
    // 큐에 넣은 뒤에만 카운터를 비우므로, 전송 코루틴이 끝나기 전에 프로세스가 죽어도 유실되지 않음.
    private fun flushBatch() {
        persistBatchToQueue()
        serviceScope.launch { sendPendingUserLogs() }
    }

    // 현재 배치를 pending 큐로 옮기기만 (전송은 호출부가)
    private fun persistBatchToQueue() {
        synchronized(pendingLock) {
            if (batchScrollCount > 0) {
                appendPendingLocked(batchFirstScrollMs, batchScrollCount, batchAppPkg)
                batchScrollCount = 0
                batchFirstScrollMs = 0L
                batchAppPkg = ""
            }
        }
    }

    // 아직 서버에 안 올라간 오늘 스크롤 수 — 큐(전송 실패분) + 현재 배치. /sync 의 dailyTotal 에 더해 준다.
    private fun unsentTodayCount(): Int = synchronized(pendingLock) {
        readPendingLocked().filter { it.ts >= todayStartMs }.sumOf { it.count } + batchScrollCount
    }

    // ── 다중 기기 동기화 ─────────────────────────────────────
    // 1) 미전송분 업로드 → 2) GET /sync → 3) 일간 = 서버 합계(+아직 안 올라간 분), 시간당 = 로컬 + 다른 기기 1시간
    //  → 4) 지금 쇼츠를 보는 중이면 한도 검사(≥). 여러 단계를 건너뛰어도 팝업은 1번(checkLimits 가 처리).
    // blockUntil / lastShownMilestone 은 서버가 값을 주면 받아서 적용 (3주차까지는 null / -1).
    private suspend fun syncFromServer(reason: String) {
        if (!isSyncing.compareAndSet(false, true)) return
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val userId = prefs.getString("userId", "unknown") ?: "unknown"
            if (userId == "unknown") return
            resetIfUserChanged(userId)
            val now = System.currentTimeMillis()
            handleDayRolloverIfNeeded(now, userId)

            // 1) 미전송분 먼저 — 서버 합계에 이 기기 분이 빠진 채로 읽지 않게
            persistBatchToQueue()
            sendPendingUserLogs()

            // 2) GET /sync
            val deviceId = com.example.short_cut.DeviceId.get(this)
            val res = com.example.short_cut.authedRequest("GET", "/sync?deviceId=$deviceId")
            val json = res.json
            if (!res.ok || json == null) {
                Log.w(TAG, "/sync 실패 ($reason) — code=${res.code}")
                return
            }

            // 3) 적용
            val serverDate = json.optString("date")
            val localDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
            }.format(java.util.Date(todayStartMs))
            if (serverDate.isNotEmpty() && serverDate != localDate) {
                // 자정 직후 서버/앱 날짜가 엇갈린 순간 — 어제 합계로 오늘을 덮으면 안 되므로 이번 응답은 버림
                Log.w(TAG, "/sync 날짜 불일치 (서버 $serverDate / 앱 $localDate) — 이번 응답 무시")
                return
            }
            val dailyTotal = json.optInt("dailyTotal", -1)
            if (dailyTotal >= 0) {
                // 서버 합계 + 방금 전송에 실패해 아직 서버에 없는 분. 서버 값이 기준이라 로컬이 더 커도 덮어쓴다.
                dailyCount = dailyTotal + unsentTodayCount()
            }
            otherDevicesLastHour = json.optInt("otherDevicesLastHour", 0).coerceAtLeast(0)
            // 지금 카운트로 도달 가능한 단계 — 서버의 milestone 값이 이보다 높으면 낡은 값(다른 시간대 창 · 어제)이다
            val hourlyNowCombined = scrollCounts.hourlyCount(now) + otherDevicesLastHour
            val hourlyStageNow = stageOf(hourlyNowCombined, hourlyLimit, HOURLY_STEP)
            val dailyStageNow = stageOf(dailyCount, dailyLimit, DAILY_STEP)
            val deviceCount = json.optInt("deviceCount", -1)
            if (deviceCount >= 1) prefs.edit().putInt(com.example.short_cut.PK_DEVICE_COUNT, deviceCount).apply()

            // 다른 기기의 Stop 차단 — 남은 시간만큼 이 기기도 차단 (D6)
            val blockUntil = if (json.isNull("blockUntil")) 0L else json.optLong("blockUntil", 0L)
            if (blockUntil > now && blockUntil > stopUntilMs) {
                stopUntilMs = blockUntil
                Log.d(TAG, "/sync blockUntil 적용 — ${(blockUntil - now) / 1000}초 남음")
                // 다른 기기에서 그만보기를 눌렀다 = 그 팝업에 답한 것 → 이 기기에 남은 같은 팝업은 닫는다
                if (pendingPopupType != null) {
                    Log.d(TAG, "다른 기기 Stop → 이 기기 미응답 팝업 정리")
                    clearPendingPopup()
                    withContext(Dispatchers.Main) { dismissAllPopups() }
                }
            }
            // 다른 기기에서 이미 보여 준 팝업 단계 — 로컬보다 크면 올려서 같은 팝업 생략 (D6)
            json.optJSONObject("lastShownMilestone")?.let { m ->
                // [중요] 서버 값이 현재 카운트로 도달 가능한 단계보다 높으면 무시한다.
                //   시간당 창은 기기마다 다르게 흐른다. 아침에 B 가 60 단계를 띄운 값이 서버에 남아 있는데
                //   오후에 A 의 카운트가 25 라면, 60 을 받으면 A 는 "다음 팝업 70" 이 돼 30·40 에서 안 뜨고,
                //   1분마다 다시 60 으로 올라가 끝까지 안 뜬다 (실기기에서 재현됨). 낡은 값은 버리고 현재 단계까지만 받는다.
                val at = json.optJSONObject("lastShownMilestoneAt")   // 서버가 주면 사용 (요청해 둠), 없으면 null
                val h = acceptRemoteMilestone(m.optInt("hourly", -1), at?.optLong("hourly", 0L) ?: 0L,
                    hourlyStageNow, hourlyLimit, HOURLY_STEP, 60 * 60 * 1000L, now)
                val d = acceptRemoteMilestone(m.optInt("daily", -1), at?.optLong("daily", 0L) ?: 0L,
                    dailyStageNow, dailyLimit, DAILY_STEP, now - todayStartMs, now)
                Log.d(TAG, "/sync milestone — 서버 shown hourly=${m.optInt("hourly", -1)} daily=${m.optInt("daily", -1)}, " +
                    "현재 단계 hourly=$hourlyStageNow daily=$dailyStageNow → 적용 hourly=$h daily=$d (로컬 $hourlyMilestone/$dailyMilestone)")
                if (h > hourlyMilestone) hourlyMilestone = h
                if (d > dailyMilestone) dailyMilestone = d
                // 다른 기기가 띄운 단계(내가 올린 값보다 큼)면 그 아래로는 재경고하지 않는다
                remoteHourlyFloor = if (h > uploadedHourlyMilestone) h else -1
                // 이 기기에 미응답 팝업이 있는데 다른 기기가 그보다 더 나간 단계까지 갔으면(내가 올린 값보다 큼)
                // 이 팝업은 이미 지나간 단계 → 닫는다. 같은 값이면 누가 먼저 띄웠는지 알 수 없어 그대로 둔다.
                val pType = pendingPopupType
                if (pType != null) {
                    val pendingValue = pendingPopupOverage + (if (pType == "hourly") hourlyLimit else dailyLimit)
                    val serverValue = if (pType == "hourly") h else d
                    val mine = if (pType == "hourly") uploadedHourlyMilestone else uploadedDailyMilestone
                    if (serverValue > pendingValue && serverValue > mine) {
                        Log.d(TAG, "다른 기기가 $pType $serverValue 단계까지 진행 → 이 기기 미응답 팝업($pendingValue) 정리")
                        clearPendingPopup()
                        withContext(Dispatchers.Main) { dismissAllPopups() }
                    }
                }
            }
            // 다른 기기에서 사용자가 답한(Stop/계속보기) 단계 — 이 기기에 같은 단계 미응답 팝업이 남아 있으면 닫는다.
            // /violations 는 보내지 않는다 (실제로 누른 기기만 보내야 통계 ignoreCount 가 두 번 잡히지 않음).
            json.optJSONObject("lastAnsweredMilestone")?.let { m ->
                val pType = pendingPopupType ?: return@let
                // 같은 이유로 낡은 값(현재 도달 가능 단계보다 높음)은 무시 — 아침에 답한 60 이 오후의 20 팝업을 닫으면 안 됨
                val stageNow = if (pType == "hourly") hourlyStageNow else dailyStageNow
                val at = json.optJSONObject("lastAnsweredMilestoneAt")?.optLong(pType, 0L) ?: 0L
                val answered = if (pType == "hourly")
                    acceptRemoteMilestone(m.optInt(pType, -1), at, stageNow, hourlyLimit, HOURLY_STEP, 60 * 60 * 1000L, now)
                else
                    acceptRemoteMilestone(m.optInt(pType, -1), at, stageNow, dailyLimit, DAILY_STEP, now - todayStartMs, now)
                val pendingValue = pendingPopupOverage + (if (pType == "hourly") hourlyLimit else dailyLimit)
                if (answered >= 0 && answered >= pendingValue) {
                    Log.d(TAG, "다른 기기가 $pType $answered 단계에 답함 → 이 기기 미응답 팝업($pendingValue) 닫기")
                    clearPendingPopup()
                    withContext(Dispatchers.Main) { dismissAllPopups() }
                }
            }
            // [팝업 따라가기] 다른 기기에 떠 있는데 아직 아무도 답하지 않은 팝업(shown > answered)은 이 기기에서도 띄운다.
            //   안 그러면 팝업을 그대로 둔 채 기기만 바꿔서 다음 단계까지 계속 볼 수 있다 (통합 테스트 6번에서 확인).
            //   어느 한쪽에서 답하면 answered 가 올라가 다른 쪽 팝업은 위 규칙으로 닫힌다. violation 은 답한 기기만 보낸다.
            if (pendingPopupType == null) {
                val shown = json.optJSONObject("lastShownMilestone")
                val ans = json.optJSONObject("lastAnsweredMilestone")
                val atS = json.optJSONObject("lastShownMilestoneAt")
                val sh = acceptRemoteMilestone(shown?.optInt("hourly", -1) ?: -1, atS?.optLong("hourly", 0L) ?: 0L,
                    hourlyStageNow, hourlyLimit, HOURLY_STEP, 60 * 60 * 1000L, now)
                val sd = acceptRemoteMilestone(shown?.optInt("daily", -1) ?: -1, atS?.optLong("daily", 0L) ?: 0L,
                    dailyStageNow, dailyLimit, DAILY_STEP, now - todayStartMs, now)
                val ah = ans?.optInt("hourly", -1) ?: -1
                val ad = ans?.optInt("daily", -1) ?: -1
                val follow: Pair<String, Int>? = when {
                    sh >= hourlyLimit && sh > ah && sh > localAnsweredHourly -> "hourly" to (sh - hourlyLimit)
                    sd >= dailyLimit && sd > ad && sd > localAnsweredDaily -> "daily" to (sd - dailyLimit)
                    else -> null
                }
                if (follow != null) {
                    val (fType, fOverage) = follow
                    Log.d(TAG, "다른 기기의 미응답 $fType 팝업(초과 $fOverage) → 이 기기에도 표시")
                    setPendingPopup(fType, fOverage)
                    // 쇼츠를 보는 중이면 바로, 아니면 다음에 쇼츠에 들어올 때 복원된다
                    if (detectors.any { it.inShortsMode } && now >= stopUntilMs) {
                        withContext(Dispatchers.Main) { if (popupViews.isEmpty()) showLimitPopup(fType, fOverage) }
                    }
                }
            }
            savePersistedState()

            val hourlyCount = scrollCounts.hourlyCount(now) + otherDevicesLastHour
            lastHourlyCount = hourlyCount
            Log.d(TAG, "/sync 적용 ($reason) — daily=$dailyCount, hourly=$hourlyCount (다른 기기 $otherDevicesLastHour), 기기 ${deviceCount}대")

            // 4) 한도 검사 — 쇼츠를 보는 중일 때만 (홈 화면 위에 팝업이 뜨면 안 되므로. 아니면 다음 스크롤 때 검사됨)
            if (detectors.any { it.inShortsMode } && now >= stopUntilMs) {
                checkLimits(hourlyCount, dailyCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "/sync 처리 실패 ($reason) — ${e.message}")
        } finally {
            isSyncing.set(false)
        }
    }

    // 1분 /sync 타이머 중지 — 쇼츠를 보지 않는 동안엔 /sync 를 부르지 않는다
    // (/sync 한 번마다 서버가 다른 기기에 FLUSH 푸시를 보내므로, 안 볼 때 돌면 상대 기기를 계속 깨운다)
    private fun stopSyncTimer(reason: String) {
        mainHandler.removeCallbacks(syncTimerRunnable)
        Log.d(TAG, "/sync 타이머 중지 — $reason")
    }

    // FCM COUNT_UPDATED 수신 시 ShortCutMessagingService 가 호출 — 서버 합계가 바뀌었으니 다시 받아 적용
    fun syncFromRemote() {
        serviceScope.launch { syncFromServer("FCM COUNT_UPDATED") }
    }

    // 즉시 업로드 트리거(쇼츠 이탈 · 앱 전환 · Stop · 화면 꺼짐)용 — 아직 큐에 안 들어간 스크롤이 있을 때만 flush.
    // 다른 앱의 창 전환마다 불릴 수 있으므로, 보낼 게 없으면 코루틴도 띄우지 않고 바로 반환한다.
    // (10회/5분 트리거는 기존대로 flushBatch 를 직접 호출)
    private fun flushIfUnsent(reason: String) {
        val unsent = synchronized(pendingLock) { batchScrollCount }
        if (unsent <= 0) return
        Log.d(TAG, "즉시 업로드 — $reason (미전송 ${unsent}회)")
        flushBatch()
    }

    // FCM FLUSH 수신 시 ShortCutMessagingService 가 호출 — 현재 배치 + 큐에 남은 실패분까지 바로 전송.
    // 보낼 게 없으면 sendPendingUserLogs 가 아무 요청 없이 끝난다.
    fun flushFromRemote() {
        Log.d(TAG, "즉시 업로드 — FCM FLUSH")
        flushBatch()
    }

    // 모든 popup view 제거 — variant 4 stacked 포함 전체 dismiss
    private fun dismissAllPopups() {
        val copy = popupViews.toList()
        popupViews.clear()
        isPopupShowing = false
        blockPopupShowing = false
        copy.forEach { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        removeScrim()
    }

    override fun onInterrupt() {
        dismissAllPopups()
        Log.d(TAG, "서비스 중단됨")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        dismissAllPopups()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        batchHandler.removeCallbacks(batchTimerRunnable)
        mainHandler.removeCallbacks(syncTimerRunnable)
        flushBatch()
        super.onDestroy()
    }
}