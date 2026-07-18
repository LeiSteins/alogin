package top.steins.autologin.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.network.AccountDevice
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.formatFlowMb
import top.steins.autologin.ui.component.AppearEasing
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.ScaleFadeBox
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardBorder
import top.steins.autologin.ui.theme.appCardElevation
import java.util.Locale

private const val CardAnimationDurationMillis = 250
private const val CardStaggerDelayMillis = 60

@Composable
fun HomeScreen(
    wifiName: String,
    ipAddress: String,
    isOnline: Boolean,
    accountOverview: AccountOverview?,
    isAccountInfoLoading: Boolean,
    accountInfoError: String,
    networkStatusError: String,
    targetWifis: List<String>,
    onRefreshAccountInfo: () -> Unit,
    onCheckNetworkStatus: () -> Unit,
    onRetryAccountInfo: () -> Unit,
    onLogin: suspend () -> LoginResult,
    onConfirmLogin: () -> Unit,
    onLogoutDevice: suspend (String) -> DeviceLogoutResult,
    onRefreshAfterDeviceLogout: (Int) -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val toastState = rememberCapsuleToastState(scope)

    var isLoggingIn by remember { mutableStateOf(false) }
    var isDeletingDevice by remember { mutableStateOf(false) }
    var lastAccountOverview by remember { mutableStateOf<AccountOverview?>(null) }
    var renderOnlineCards by remember { mutableStateOf(isOnline) }
    // 此状态随 Home 的导航返回栈条目保存，返回主页时无需重播装饰性入场动画。
    var hasPresentedHome by rememberSaveable { mutableStateOf(false) }
    var showOnlineCardsImmediately by remember { mutableStateOf(hasPresentedHome) }
    var previousOnlineState by remember { mutableStateOf(isOnline) }

    LaunchedEffect(Unit) {
        hasPresentedHome = true
    }

    LaunchedEffect(accountOverview) {
        if (accountOverview != null) {
            lastAccountOverview = accountOverview
        }
    }

    val isTargetWifi = targetWifis.contains(wifiName)
    // 网络离线后 ViewModel 会清空账号信息；保留上一份数据直到退出动画播放完成。
    val overviewForCards = accountOverview ?: if (!isOnline) lastAccountOverview else null
    val devicesForCards = overviewForCards
        ?.devices
        .orEmpty()
        .sortedByDescending { device -> device.isCurrentDevice(ipAddress) }
    val onlineCardCount = 1 + when {
        overviewForCards == null -> 0
        devicesForCards.isEmpty() -> 1
        else -> devicesForCards.size
    }

    LaunchedEffect(isOnline) {
        val wasOnline = previousOnlineState
        previousOnlineState = isOnline
        if (isOnline) {
            if (!wasOnline) {
                showOnlineCardsImmediately = false
            }
            renderOnlineCards = true
        } else if (renderOnlineCards) {
            delay(
                CardAnimationDurationMillis +
                        (onlineCardCount - 1) * CardStaggerDelayMillis.toLong()
            )
            renderOnlineCards = false
        }
    }

    fun performPrimaryAction() {
        when {
            isOnline -> {
                onRefreshAccountInfo()
                scope.launch { toastState.show("正在刷新账号信息…") }
            }

            isTargetWifi -> {
                isLoggingIn = true
                scope.launch {
                    val result = onLogin()
                    isLoggingIn = false
                    when (result) {
                        is LoginResult.Success -> {
                            toastState.show("登录成功，正在获取账号信息…")
                            onConfirmLogin()
                        }

                        is LoginResult.Failure -> toastState.show(result.message)
                        is LoginResult.NetworkError -> toastState.show(result.message)
                    }
                }
            }

            else -> {
                scope.launch {
                    toastState.show("正在刷新…")
                    onCheckNetworkStatus()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(1f)
                        .padding(12.dp)
                ) {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.settings),
                            contentDescription = "设置",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToAccount,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.manage_accounts),
                            contentDescription = "账号管理",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = ScreenHorizontalPadding,
                        end = ScreenHorizontalPadding,
                        top = 72.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        NetworkInfoCard(
                            wifiName = wifiName,
                            ipAddress = ipAddress,
                            errorMessage = networkStatusError
                        )
                    }

                    if (renderOnlineCards) {
                        item(key = "account-info") {
                            StaggeredCard(
                                visible = isOnline,
                                initiallyVisible = showOnlineCardsImmediately,
                                index = 0,
                                count = onlineCardCount
                            ) {
                                AccountInfoCard(
                                    overview = overviewForCards,
                                    isLoading = isAccountInfoLoading,
                                    errorMessage = accountInfoError,
                                    onRetry = onRetryAccountInfo
                                )
                            }
                        }

                        if (overviewForCards != null) {
                            if (devicesForCards.isEmpty()) {
                                item(key = "empty-device") {
                                    StaggeredCard(
                                        visible = isOnline,
                                        initiallyVisible = showOnlineCardsImmediately,
                                        index = 1,
                                        count = onlineCardCount
                                    ) {
                                        EmptyDeviceCard()
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = devicesForCards,
                                    key = { _, device -> device.macAddress }
                                ) { deviceIndex, device ->
                                    StaggeredCard(
                                        visible = isOnline,
                                        initiallyVisible = showOnlineCardsImmediately,
                                        index = deviceIndex + 1,
                                        count = onlineCardCount
                                    ) {
                                        DeviceCard(
                                            device = device,
                                            isCurrentDevice = device.isCurrentDevice(ipAddress),
                                            enabled = !isDeletingDevice,
                                            onDelete = {
                                                isDeletingDevice = true
                                                scope.launch {
                                                    try {
                                                        when (val result = onLogoutDevice(device.macAddress)) {
                                                            DeviceLogoutResult.Success -> {
                                                                toastState.show(
                                                                    resources.getString(
                                                                        R.string.delete_device_success,
                                                                        device.macAddress
                                                                    )
                                                                )
                                                                onRefreshAfterDeviceLogout(1)
                                                            }

                                                            is DeviceLogoutResult.Failure -> {
                                                                toastState.show(result.message)
                                                            }
                                                        }
                                                    } finally {
                                                        isDeletingDevice = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }

        FloatingActionBox(
            label = when {
                isOnline -> "刷 新"
                isTargetWifi -> "登 录"
                else -> "刷 新"
            },
            isLoading = isLoggingIn || isAccountInfoLoading,
            enabled = !isLoggingIn && !isAccountInfoLoading && !isDeletingDevice,
            onClick = ::performPrimaryAction,
            modifier = Modifier
                .width(160.dp)
                .align(Alignment.BottomEnd)
                .zIndex(1f)
                .navigationBarsPadding()
                .padding(end = ScreenHorizontalPadding, bottom = 24.dp)
        )

        CapsuleToast(
            state = toastState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

    }
}

@Composable
private fun StaggeredCard(
    visible: Boolean,
    initiallyVisible: Boolean,
    index: Int,
    count: Int,
    content: @Composable () -> Unit
) {
    ScaleFadeBox(
        visible = visible,
        modifier = Modifier.fillMaxWidth(),
        initiallyVisible = initiallyVisible,
        durationMillis = CardAnimationDurationMillis,
        enterDelayMillis = index * CardStaggerDelayMillis,
        exitDelayMillis = (count - index - 1).coerceAtLeast(0) * CardStaggerDelayMillis
    ) {
        content()
    }
}

@Composable
private fun FloatingActionBox(
    label: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = shape)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Text(
                text = label,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun NetworkInfoCard(wifiName: String, ipAddress: String, errorMessage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
        border = appCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            InfoLabel("WiFi 名称")
            Spacer(modifier = Modifier.height(4.dp))
            InfoValue(wifiName)
            Spacer(modifier = Modifier.height(16.dp))
            InfoLabel("IP 地址")
            Spacer(modifier = Modifier.height(4.dp))
            InfoValue(ipAddress)
            if (errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AccountInfoCard(
    overview: AccountOverview?,
    isLoading: Boolean,
    errorMessage: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = CardAnimationDurationMillis,
                    easing = AppearEasing
                )
            ),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
        border = appCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "已登录",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            if (overview != null) {
                AccountSummaryRow(
                    username = overview.username,
                    remainingMoneyYuan = overview.remainingMoneyYuan
                )
                FlowUsageSection(
                    usedFlowMb = overview.usedFlowMb,
                    remainingFlowMb = overview.remainingFlowMb
                )
            } else if (isLoading) {
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在获取账号信息…")
                }
            }

            if (errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRetry) {
                    Text("重新获取")
                }
            }
        }
    }
}

@Composable
private fun FlowUsageSection(usedFlowMb: String, remainingFlowMb: String) {
    val usageFraction = calculateFlowUsageFraction(usedFlowMb, remainingFlowMb)

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = usageFraction?.let { "已用 ${(it * 100).toInt()}%" } ?: "--",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { usageFraction ?: 0f },
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.secondaryContainer
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "已用 ${usedFlowMb.toDisplayFlow()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "剩余 ${remainingFlowMb.toDisplayFlow()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyDeviceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
        border = appCardBorder()
    ) {
        Text(
            text = "未查询到此账号关联的设备",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceCard(
    device: AccountDevice,
    isCurrentDevice: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
        border = appCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.macAddress,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "状态：${device.statusDisplayName()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = device.statusColor()
                )
                Text(
                    text = "IP：${device.ipAddress.ifBlank { "--" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCurrentDevice) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.current_device),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_device),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AccountSummaryRow(username: String, remainingMoneyYuan: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(modifier = Modifier.weight(1f)) {
            InfoLabel("用户名")
            Spacer(modifier = Modifier.height(4.dp))
            InfoValue(username)
        }

        if (remainingMoneyYuan.isNotBlank()) {
            Spacer(modifier = Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                InfoLabel("剩余金额")
                Spacer(modifier = Modifier.height(4.dp))
                InfoValue("${remainingMoneyYuan} 元")
            }
        }
    }
}

@Composable
private fun InfoLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun AccountDevice.statusColor() = when (isOnline) {
    true -> MaterialTheme.colorScheme.primary
    false -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun AccountDevice.statusDisplayName(): String = when (status.trim()) {
    "1" -> "在线"
    "0" -> "离线"
    else -> status
}

private fun AccountDevice.isCurrentDevice(localIpAddress: String): Boolean =
    ipAddress.isNotBlank() && ipAddress.trim() == localIpAddress.trim()

private fun String.toDisplayFlow(): String =
    takeIf { it.isNotBlank() }?.let(::formatFlowMb) ?: "--"

private fun calculateFlowUsageFraction(usedFlow: String, remainingFlow: String): Float? {
    val usedMb = usedFlow.toMegabytes() ?: return null
    val remainingMb = remainingFlow.toMegabytes() ?: return null
    val totalMb = usedMb + remainingMb
    if (totalMb <= 0.0) return null
    return (usedMb / totalMb).toFloat().coerceIn(0f, 1f)
}

private fun String.toMegabytes(): Double? {
    val normalized = trim().uppercase(Locale.ROOT)
    val number = Regex("""\d+(?:\.\d+)?""").find(normalized)?.value?.toDoubleOrNull() ?: return null
    return when {
        normalized.contains("GB") -> number * 1024
        normalized.contains("KB") -> number / 1024
        else -> number
    }
}
