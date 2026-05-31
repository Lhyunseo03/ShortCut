package com.example.short_cut.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat

// 접근성 권한이 꺼져 있을 때 우회 차단용 — UsageStatsManager 로 포그라운드 앱을 폴링해
// 타겟 앱(YT/IG/TT)에 진입하면 홈으로 강제 이동.
// 접근성 권한이 켜져 있으면 그쪽이 정교한 차단을 담당하므로 여기선 아무것도 안 함(중복 방지).
class GuardForegroundService : Service() {
    companion object {
        private const val TAG = "ShortCutGuard"
        private const val CHANNEL_ID = "shortcut_guard"
        private const val NOTIF_ID = 9101
        private const val POLL_INTERVAL_MS = 2000L
        private val TARGET_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )
        private val ACCESSIBILITY_SERVICE_CLASS = ShortCutAccessibilityService::class.java.name

        // 같은 포그라운드 이벤트에 대해 홈 인텐트 한 번만 — 짧은 시간 안에 반복 발사 방지
        private const val GUARD_COOLDOWN_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastGuardMs = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            try { pollAndGuard() } catch (e: Exception) { Log.e(TAG, "poll 실패 — ${e.message}") }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = buildNotification()
        // Android 14+ (UPSIDE_DOWN_CAKE, API 34) — manifest 의 foregroundServiceType=specialUse 와
        // 동일한 type 을 startForeground 에도 명시 안 하면 즉시 ForegroundServiceTypeException 으로 종료됨.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 실패 — ${e.message}")
            stopSelf()
            return
        }
        handler.post(pollRunnable)
        Log.d(TAG, "Guard 서비스 시작")
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "Guard 서비스 종료")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ShortCut 보호",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "쇼츠 앱 진입 차단을 위해 백그라운드에서 실행 중"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ShortCut 보호 중")
            .setContentText("타겟 앱 접근을 모니터링하고 있어요")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun pollAndGuard() {
        // 접근성 서비스가 켜져 있으면 정교한 차단(쇼츠/릴스만)을 그쪽이 담당 — 여기선 아무것도 안 함
        if (isAccessibilityEnabled()) return
        val foreground = getForegroundPackage() ?: return
        if (foreground !in TARGET_PACKAGES) return

        val now = System.currentTimeMillis()
        if (now - lastGuardMs < GUARD_COOLDOWN_MS) return
        lastGuardMs = now

        Log.d(TAG, "접근성 OFF + 타겟앱 $foreground 감지 → 홈으로 강제 이동")
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { startActivity(homeIntent) } catch (e: Exception) { Log.e(TAG, "홈 인텐트 실패 — ${e.message}") }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val parsed = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (parsed.packageName == packageName && parsed.className == ACCESSIBILITY_SERVICE_CLASS) {
                return true
            }
        }
        return false
    }

    // 최근 N초간의 MOVE_TO_FOREGROUND 이벤트 중 마지막 패키지 = 현재 포그라운드 앱
    // (queryEvents 는 PACKAGE_USAGE_STATS 권한 필요)
    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 10_000L
        val events = usm.queryEvents(begin, end)
        var lastPkg: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = ev.packageName
            }
        }
        return lastPkg
    }
}
