package com.example.short_cut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 설정 > Devices — 이 계정으로 로그인한 기기 목록 (목록만. 삭제 버튼은 4주차)
@Composable
internal fun SettingsDevicesScreen(onBack: () -> Unit, vm: DevicesViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(text = "Devices", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                state.loading -> StatsLoadingSpinner()
                state.error != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = state.error!!, fontSize = 15.sp, color = Color(0xFF888888))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { vm.refresh() }) { Text("다시 시도") }
                }
                state.devices.isEmpty() -> Text(
                    text = "등록된 기기 없음",
                    fontSize = 16.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(vertical = 48.dp).align(Alignment.CenterHorizontally)
                )
                else -> {
                    SectionTitle("이 계정으로 로그인한 기기 ${state.devices.size}대")
                    state.devices.forEach { DeviceCard(it) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF7F7F7)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.deviceName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (device.isThisDevice) {
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(text = "이 기기", fg = Color(0xFF1565C0), bg = Color(0xFFE3F2FD))
                }
            }

            // 마지막 접속 — 로그아웃한 기기는 로그아웃 시각을 대신 보여 준다
            val loggedOutAt = device.loggedOutAt
            Text(
                // 서버의 lastSeenAt 은 앱 시작 · 10분 주기 heartbeat 때만 갱신돼 최대 10분 늦다.
                // 이 기기는 지금 보고 있으니 "지금 사용 중", 다른 기기는 11분 안이면 "접속 중" 으로 표시.
                text = when {
                    loggedOutAt != null -> "로그아웃 ${formatDateTime(loggedOutAt)}"
                    device.isThisDevice -> "지금 사용 중"
                    // 생존 신호는 앱 화면이 아니라 보호 서비스가 보낸다 — 앱을 닫아도 계속 오므로 "접속 중" 이 아니라
                    // "보호 중"(= 이 기기에서 Short-Cut 이 동작하고 있음) 으로 표시한다.
                    device.lastSeenAt != null && System.currentTimeMillis() - device.lastSeenAt < 11 * 60_000L ->
                        "보호 중 · ${formatAgo(device.lastSeenAt)} 확인"
                    else -> "마지막 접속 ${formatAgo(device.lastSeenAt)}"
                },
                fontSize = 14.sp,
                color = Color(0xFF888888)
            )

            // 상태 배지 — 문제가 있을 때만 (로그아웃 / 권한 꺼짐)
            if (loggedOutAt != null || !device.permissionsOk) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (loggedOutAt != null) StatusBadge("로그아웃됨", fg = Color(0xFF616161), bg = Color(0xFFE0E0E0))
                    if (!device.permissionsOk) StatusBadge("권한 꺼짐", fg = Color(0xFFC62828), bg = Color(0xFFFFEBEE))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, fg: Color, bg: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun formatAgo(ms: Long?): String {
    if (ms == null) return "기록 없음"
    val diffMin = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
    return when {
        diffMin < 1 -> "방금"
        diffMin < 60 -> "${diffMin}분 전"
        diffMin < 24 * 60 -> "${diffMin / 60}시간 전"
        else -> "${diffMin / (24 * 60)}일 전"
    }
}

// "9/11 23:41" 형식
private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(ms))
