package com.example.short_cut.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.short_cut.MainActivity
import com.example.short_cut.db.AppDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// 일요일 저녁 주간 리포트 — 전주 대비 이번 주 스크롤 변화 + 다음 주 데일리 리밋 추천을 알림으로 띄운다.
// 데이터는 서버 /stats/:userId/daily 에서 14일치(이번주/지난주)를 받아 앱에서 비교·추천 계산.
// (서버 추가 엔드포인트 없이 기존 /daily 재사용)
//
// 추천 공식(점진적 감소): 이번 주 일평균(7일 기준) × 0.9 를 100단위로 반올림, 하한 100.
//   현재 데일리 리밋보다 낮을 때만 "줄이기"를 제안하고, 그렇지 않으면 현재 값 유지를 권한다.
class WeeklyReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WeeklyReport"
        private const val CHANNEL_ID = "shortcut_weekly_report"
        private const val NOTIF_ID = 9201
        private const val SERVER_BASE_URL = "https://short-cut-server-production.up.railway.app"
        private val DATE_FMT get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    override suspend fun doWork(): Result {
        try {
            buildAndNotify()
        } catch (e: Exception) {
            Log.e(TAG, "주간 리포트 생성 실패 — ${e.message}")
        } finally {
            // 성공/실패와 무관하게 다음 일요일 저녁으로 재예약 (체인 유지)
            WeeklyReportScheduler.schedule(applicationContext)
        }
        return Result.success()
    }

    private suspend fun buildAndNotify() {
        val ctx = applicationContext
        val userId = ctx.getSharedPreferences("short_cut_prefs", Context.MODE_PRIVATE)
            .getString("userId", null) ?: run {
                Log.d(TAG, "userId 없음 — 리포트 생략")
                return
            }

        // 이번 주(월~일) / 지난 주 날짜 문자열
        val thisMonday = mondayOf(System.currentTimeMillis())
        val lastMonday = thisMonday - 7L * 24 * 60 * 60 * 1000
        val thisWeekDays = weekDayStrings(thisMonday)
        val lastWeekDays = weekDayStrings(lastMonday)

        val thisWeekTotal = thisWeekDays.sumOf { fetchDayTotal(userId, it) }
        val lastWeekTotal = lastWeekDays.sumOf { fetchDayTotal(userId, it) }

        // 현재 적용 중인 데일리 리밋 (추천 비교용)
        val currentDailyLimit = AppDatabase.getDatabase(ctx).userLimitDao()
            .getLimit(userId)?.dailyLimit ?: 100

        // 점진적 감소 추천 — 이번 주 일평균(7일) × 0.9, 100단위 반올림, 하한 100
        val avgDaily = thisWeekTotal / 7.0
        val recommendedDaily = (Math.round(avgDaily * 0.9 / 100.0).toInt() * 100).coerceAtLeast(100)
        val suggestLower = recommendedDaily < currentDailyLimit

        // 전주 대비 추세
        val trend = when {
            lastWeekTotal <= 0 -> "지난주 기록이 없어 비교는 다음 주부터 가능해요."
            thisWeekTotal < lastWeekTotal -> {
                val pct = (lastWeekTotal - thisWeekTotal) * 100 / lastWeekTotal
                "지난주보다 ${pct}% 줄었어요 👍"
            }
            thisWeekTotal > lastWeekTotal -> {
                val pct = (thisWeekTotal - lastWeekTotal) * 100 / lastWeekTotal
                "지난주보다 ${pct}% 늘었어요"
            }
            else -> "지난주와 비슷한 양이에요."
        }

        val recLine = if (suggestLower) {
            "다음 주 추천 데일리 리밋: ${recommendedDaily}회 (현재 ${currentDailyLimit}회) — 설정에서 바꾸면 다음 주 월요일부터 적용돼요."
        } else {
            "현재 데일리 리밋 ${currentDailyLimit}회를 유지해도 좋아요."
        }

        val title = "주간 스크롤 리포트"
        val shortText = "이번 주 ${thisWeekTotal}회 · $trend"
        val bigText = "이번 주 총 ${thisWeekTotal}회 (지난주 ${lastWeekTotal}회)\n$trend\n\n$recLine"

        notify(ctx, title, shortText, bigText)
    }

    // GET /stats/:userId/daily?date=YYYY-MM-DD 에서 totalScroll 만 가볍게 파싱. 실패 시 0.
    private suspend fun fetchDayTotal(userId: String, date: String): Int {
        return try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
                ?: return 0
            val req = Request.Builder()
                .url("$SERVER_BASE_URL/stats/$userId/daily?date=$date")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            // Railway 콜드 스타트 대비 30초 타임아웃 (앱의 다른 GET 과 동일)
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            val ok = resp.isSuccessful
            resp.close()
            if (ok && body != null) JSONObject(body).optInt("totalScroll", 0) else 0
        } catch (e: Exception) {
            Log.w(TAG, "GET daily($date) 실패 — ${e.message}")
            0
        }
    }

    private fun notify(ctx: Context, title: String, shortText: String, bigText: String) {
        // Android 13+ 알림 권한 없으면 표시 불가 — 조용히 생략
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "알림 권한 없음 — 리포트 표시 생략")
            return
        }
        createChannel(ctx)

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "주간 리포트",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "매주 일요일 저녁 주간 스크롤 요약" }
                )
            }
        }
    }

    // 해당 시각이 속한 주의 월요일 0시(local) ms
    private fun mondayOf(timeMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val daysFromMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // MON=0..SUN=6
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        return cal.timeInMillis
    }

    // 월요일 0시부터 7일치 "yyyy-MM-dd" 리스트
    private fun weekDayStrings(mondayMs: Long): List<String> {
        val cal = Calendar.getInstance().apply { timeInMillis = mondayMs }
        val fmt = DATE_FMT
        return (0 until 7).map {
            val s = fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            s
        }
    }
}

// 주간 리포트 스케줄러 — 다음 일요일 20:00 으로 unique OneTimeWork 예약(REPLACE).
// 앱 시작 시 호출해 항상 예약돼 있게 하고, 워커가 끝나면 스스로 다음 주를 재예약한다.
object WeeklyReportScheduler {
    const val WORK_NAME = "weekly_report"

    fun schedule(context: Context) {
        val delay = delayToNextSunday20()
        val req = OneTimeWorkRequestBuilder<WeeklyReportWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    // 지금 이후 가장 가까운 일요일 20:00(local)까지 남은 ms
    private fun delayToNextSunday20(): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 7)
        return cal.timeInMillis - now
    }
}
