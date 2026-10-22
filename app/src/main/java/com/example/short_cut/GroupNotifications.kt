package com.example.short_cut

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// 알림을 눌러 앱이 열렸을 때 어느 그룹 상세로 갈지 — MainActivity 가 넣고 그룹 탭이 꺼낸다 (PendingInvite 와 같은 방식)
internal object PendingGroupOpen {
    val groupId = mutableStateOf<String?>(null)
}

// ── 그룹 알림 (G8 한도 초과 · G7 넛지 · G9 제안/결과 · G10/G11 가입·탈퇴·로그아웃) ──
// FCM data message { type, groupId, groupName, title, body } 를 받아 종류별 채널로 띄운다. 누르면 그 그룹 상세로 이동.
internal object GroupNotifications {
    private const val CH_ALERT = "shortcut_group_alert"      // 그룹 한도 초과
    private const val CH_NUDGE = "shortcut_group_nudge"      // 넛지
    private const val CH_VOTE = "shortcut_group_vote"        // 제안 · 투표 결과 · 한도 적용
    private const val CH_MEMBER = "shortcut_group_member"    // 가입 · 탈퇴 · 로그아웃

    // 이 앱이 아는 FCM type. 서버와 이름을 맞춰야 한다 (GroupApi.kt 상단 계약)
    val KNOWN_TYPES = setOf(
        "GROUP_ALERT", "NUDGE", "PROPOSAL", "VOTE_RESULT", "LIMIT_APPLIED",
        "MEMBER_JOINED", "MEMBER_LEFT", "MEMBER_LOGOUT"
    )

    fun show(ctx: Context, type: String, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(ctx)

        val groupId = data["groupId"].orEmpty()
        val groupName = data["groupName"].orEmpty().ifBlank { "그룹" }
        val (channel, defaultTitle) = when (type) {
            "GROUP_ALERT" -> CH_ALERT to "$groupName · 한도 초과"
            "NUDGE" -> CH_NUDGE to "넛지가 왔어요"
            "PROPOSAL", "VOTE_RESULT", "LIMIT_APPLIED" -> CH_VOTE to "$groupName · 한도 투표"
            else -> CH_MEMBER to groupName
        }
        val title = data["title"].orEmpty().ifBlank { defaultTitle }
        val body = data["body"].orEmpty().ifBlank {
            when (type) {
                "GROUP_ALERT" -> "구성원이 그룹 한도를 넘었어요"
                "NUDGE" -> "구성원이 넛지를 보냈어요"
                "PROPOSAL" -> "한도 변경 제안이 올라왔어요. 투표해 주세요"
                "VOTE_RESULT" -> "투표 결과가 나왔어요"
                "LIMIT_APPLIED" -> "새 한도가 적용됐어요"
                "MEMBER_JOINED" -> "새 구성원이 들어왔어요"
                "MEMBER_LEFT" -> "구성원이 나갔어요"
                "MEMBER_LOGOUT" -> "구성원이 로그아웃했어요"
                else -> ""
            }
        }

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_GROUP_ID, groupId)
        }
        // 알림마다 다른 requestCode — 같은 PendingIntent 가 재사용돼 groupId 가 섞이지 않게
        val pi = PendingIntent.getActivity(
            ctx, (groupId + type).hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (type == "NUDGE" || type == "GROUP_ALERT") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // 같은 그룹·같은 종류는 하나로 갱신, 종류가 다르면 따로
        NotificationManagerCompat.from(ctx).notify((groupId + type).hashCode(), notif)
    }

    private fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ensure(id: String, name: String, desc: String, importance: Int) {
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = desc })
            }
        }
        ensure(CH_ALERT, "그룹 한도 초과", "구성원이 그룹 한도를 넘었을 때", NotificationManager.IMPORTANCE_HIGH)
        ensure(CH_NUDGE, "넛지", "구성원이 보낸 넛지", NotificationManager.IMPORTANCE_HIGH)
        ensure(CH_VOTE, "한도 투표", "제안 등록 · 투표 결과 · 새 한도 적용", NotificationManager.IMPORTANCE_DEFAULT)
        ensure(CH_MEMBER, "구성원 변동", "가입 · 탈퇴 · 로그아웃", NotificationManager.IMPORTANCE_LOW)
    }
}
