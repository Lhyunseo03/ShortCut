package com.example.short_cut

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 설정 > Devices 화면의 데이터 (API 스펙 §1 GET /devices 의 devices[] 한 항목과 같은 모양)
internal data class DeviceItem(
    val deviceId: String,
    val deviceName: String,
    val permissionsOk: Boolean,     // 접근성 · 사용통계 · 오버레이 3개 모두 ON
    val lastSeenAt: Long?,          // 마지막 접속 시각 (Unix ms)
    val loggedOutAt: Long?,         // 로그아웃 시각. null = 로그인 상태
    val isThisDevice: Boolean       // 지금 보고 있는 이 기기인지
)

internal data class DevicesUiState(
    val loading: Boolean = true,
    val devices: List<DeviceItem> = emptyList(),
    val error: String? = null
)

// 기기 목록을 어디서 가져올지 — 화면과 ViewModel 은 이 인터페이스만 안다.
internal interface DevicesSource {
    // 실패하면 null
    suspend fun load(myDeviceId: String): List<DeviceItem>?
}

// 서버 GET /devices 배포됨(9/22) → 실제 데이터 사용. 서버 없이 화면만 볼 때는 true 로.
private const val USE_FAKE_DEVICES = false

// 서버가 나오기 전까지 쓰는 가짜 데이터 — 폰(이 기기, 정상) + 태블릿(로그아웃 · 권한 꺼짐)
internal class FakeDevicesSource : DevicesSource {
    override suspend fun load(myDeviceId: String): List<DeviceItem> {
        delay(300)   // 로딩 상태가 보이도록 서버 호출 흉내
        val now = System.currentTimeMillis()
        return listOf(
            DeviceItem(
                deviceId = myDeviceId,
                deviceName = Build.MODEL ?: "Galaxy 폰",
                permissionsOk = true,
                lastSeenAt = now - 2 * 60_000L,
                loggedOutAt = null,
                isThisDevice = true
            ),
            DeviceItem(
                deviceId = "fake-tablet-0001",
                deviceName = "Galaxy Tab S9 (가짜 데이터)",
                permissionsOk = false,
                lastSeenAt = now - 26 * 60 * 60_000L,
                loggedOutAt = now - 25 * 60 * 60_000L,
                isThisDevice = false
            )
        )
    }
}

// GET /devices → { userId, count, devices: [ { deviceId, deviceName, permissionsOk,
//                  registeredAt, lastSeenAt, loggedOutAt, hasToken } ] }
internal class ServerDevicesSource : DevicesSource {
    override suspend fun load(myDeviceId: String): List<DeviceItem>? {
        val res = authedRequest("GET", "/devices")
        val arr = res.json?.optJSONArray("devices")
        if (!res.ok || arr == null) return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("deviceId")
            DeviceItem(
                deviceId = id,
                deviceName = o.optString("deviceName").ifBlank { "이름 없는 기기" },
                permissionsOk = o.optBoolean("permissionsOk", true),
                lastSeenAt = if (o.isNull("lastSeenAt")) null else o.optLong("lastSeenAt").takeIf { it > 0 },
                loggedOutAt = if (o.isNull("loggedOutAt")) null else o.optLong("loggedOutAt").takeIf { it > 0 },
                isThisDevice = id == myDeviceId
            )
        }
    }
}

internal class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val source: DevicesSource = if (USE_FAKE_DEVICES) FakeDevicesSource() else ServerDevicesSource()

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val myPermissionsOk = isAccessibilityServiceEnabled(app) && hasUsageStatsPermission(app) && hasOverlayPermission(app)
            // 이 기기의 권한 상태는 서버 값(최대 10분 늦음) 대신 지금 실제 상태로 표시
            val list = source.load(DeviceId.get(app))?.map { if (it.isThisDevice) it.copy(permissionsOk = myPermissionsOk) else it }
            _state.value = if (list != null) {
                // 이 기기를 맨 위에, 나머지는 최근 접속 순
                DevicesUiState(
                    loading = false,
                    devices = list.sortedWith(
                        compareByDescending<DeviceItem> { it.isThisDevice }.thenByDescending { it.lastSeenAt ?: 0L }
                    )
                )
            } else {
                DevicesUiState(loading = false, error = "기기 목록을 불러오지 못했어요")
            }
        }
    }
}
