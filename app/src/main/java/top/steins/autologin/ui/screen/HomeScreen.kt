package top.steins.autologin.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.formatFlow
import top.steins.autologin.network.getDeviceIP
import top.steins.autologin.network.getWifiSSID
import top.steins.autologin.network.login
import top.steins.autologin.ui.component.AppearEasing
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.DismissEasing
import top.steins.autologin.ui.component.ScaleFadeBox
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardBorder
import top.steins.autologin.ui.theme.appCardElevation

// ==================== 主页 ====================

@Composable
fun HomeScreen(
    settingsRepo: SettingsRepository,
    wifiName: String,
    ipAddress: String,
    isOnline: Boolean,
    studentId: String,
    usedFlow: String,
    targetWifis: List<String>,
    onCheckStatus: () -> Unit,
    onWifiNameChange: (String) -> Unit,
    onIpAddressChange: (String) -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastState = rememberCapsuleToastState(scope)

    val username by settingsRepo.username.collectAsState(initial = settingsRepo.getUsername())
    val password by settingsRepo.password.collectAsState(initial = settingsRepo.getPassword())

    var isLoggingIn by remember { mutableStateOf(false) }

    // 判断是否连接到目标 WiFi
    val isTargetWifi = targetWifis.contains(wifiName)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 右上角图标：账号管理 + 设置
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
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

            // 中间内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = ScreenHorizontalPadding,
                        end = ScreenHorizontalPadding,
                        bottom = 24.dp,
                        top = 72.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 卡片 1: 网络信息
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
                        Text(
                            text = "WiFi 名称",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = wifiName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "IP 地址",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = ipAddress,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // 卡片 2: 登录信息（缩放淡入淡出动画）
                Spacer(modifier = Modifier.height(16.dp))
                ScaleFadeBox(visible = isOnline) {
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
                            Text(
                                text = "已登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )

                            if (studentId.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "学号",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = studentId,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (usedFlow.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "已用流量",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = formatFlow(usedFlow),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

            }

            // 按钮 — 右下角常驻
            Button(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        start = ScreenHorizontalPadding,
                        end = ScreenHorizontalPadding,
                        bottom = 40.dp
                    )
                    .fillMaxWidth(0.4f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                onClick = {
                    if (isOnline) {
                        // 已在线，仅刷新状态
                        scope.launch {
                            onCheckStatus()
                            toastState.show("已刷新")
                        }
                    } else if (isTargetWifi) {
                        if (username.isBlank() || password.isBlank()) {
                            scope.launch { toastState.show("请先在设置中配置账号和密码") }
                        } else {
                            isLoggingIn = true
                            scope.launch {
                                val result = login(username, password, ipAddress)
                                isLoggingIn = false
                                when (result) {
                                    is LoginResult.Success -> {
                                        toastState.show("登录成功")
                                        onCheckStatus()
                                    }
                                    is LoginResult.Failure -> toastState.show(result.message)
                                    is LoginResult.NetworkError -> toastState.show(result.message)
                                }
                            }
                        }
                    } else {
                        scope.launch {
                            toastState.show("正在刷新…")
                            kotlinx.coroutines.delay(100)
                            onWifiNameChange(getWifiSSID(context))
                            onIpAddressChange(getDeviceIP(context))
                            onCheckStatus()
                        }
                    }
                },
                enabled = !isLoggingIn
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        when {
                            isOnline -> "已在线"
                            isTargetWifi -> "登 录"
                            else -> "刷 新"
                        }
                    )
                }
            }
            }
        }

        // 顶部胶囊提示
        CapsuleToast(
            state = toastState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

// ==================== 账号管理页 ====================
