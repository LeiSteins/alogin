package top.steins.autologin

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.checkLoginStatus
import top.steins.autologin.network.getDeviceIP
import top.steins.autologin.network.getWifiSSID
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

private enum class Screen { HOME, ACCOUNT, SETTINGS, LOG, WIFI_CONFIG }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var previousScreen by remember { mutableStateOf(Screen.HOME) }
    val scope = rememberCoroutineScope()

    // ---- 网络状态（提升至此，避免页面切换时重复检测） ----
    val targetWifis by settingsRepo.targetWifis.collectAsState(initial = settingsRepo.getTargetWifis())
    var wifiName by remember { mutableStateOf("加载中…") }
    var ipAddress by remember { mutableStateOf("加载中…") }
    var isOnline by remember { mutableStateOf(false) }
    var studentId by remember { mutableStateOf("") }
    var usedFlow by remember { mutableStateOf("") }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // 检测登录状态
    suspend fun checkStatus() {
        if (!targetWifis.contains(wifiName)) return
        val status = checkLoginStatus()
        isOnline = status.isLoggedIn
        studentId = status.uid
        usedFlow = status.flow
    }

    fun navigateTo(screen: Screen) {
        if (screen == currentScreen) return
        previousScreen = currentScreen
        currentScreen = screen
    }

    // 网络监听（仅注册一次，跨页面持久）
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        wifiName = getWifiSSID(context)
        ipAddress = getDeviceIP(context)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiName = getWifiSSID(context)
                ipAddress = getDeviceIP(context)
                scope.launch { checkStatus() }
            }

            override fun onLost(network: Network) {
                wifiName = "未连接"
                ipAddress = "无"
                isOnline = false
                studentId = ""
                usedFlow = ""
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiName = getWifiSSID(context)
                    ipAddress = getDeviceIP(context)
                    scope.launch { checkStatus() }
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            if (forward) {
                // 前进：新页面从右滑入，旧页面向左滑出 1/3
                (slideInHorizontally(initialOffsetX = { it }))
                    .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 3 }))
                    .apply { targetContentZIndex = 1f }
            } else {
                // 后退：旧页面从左侧 1/3 滑回，当前页面向右滑出
                (slideInHorizontally(initialOffsetX = { -it / 3 }))
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it }))
                    .apply { targetContentZIndex = -1f }
            }
        },
        label = "screen_transition"
    ) { screen ->
        val topScreen = if (currentScreen.ordinal >= previousScreen.ordinal) {
            currentScreen
        } else {
            previousScreen
        }

        ScreenTransitionSurface(isTopPage = screen == topScreen) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    settingsRepo = settingsRepo,
                    wifiName = wifiName,
                    ipAddress = ipAddress,
                    isOnline = isOnline,
                    studentId = studentId,
                    usedFlow = usedFlow,
                    targetWifis = targetWifis,
                    onCheckStatus = { scope.launch { checkStatus() } },
                    onWifiNameChange = { wifiName = it },
                    onIpAddressChange = { ipAddress = it },
                    onNavigateToAccount = { navigateTo(Screen.ACCOUNT) },
                    onNavigateToSettings = { navigateTo(Screen.SETTINGS) }
                )
                Screen.ACCOUNT -> AccountScreen(
                    settingsRepo = settingsRepo,
                    onNavigateBack = { navigateTo(Screen.HOME) }
                )
                Screen.SETTINGS -> SettingsScreen(
                    settingsRepo = settingsRepo,
                    onNavigateBack = { navigateTo(Screen.HOME) },
                    onNavigateToLog = { navigateTo(Screen.LOG) },
                    onNavigateToWifiConfig = { navigateTo(Screen.WIFI_CONFIG) }
                )
                Screen.LOG -> LogScreen(
                    onNavigateBack = { navigateTo(Screen.SETTINGS) }
                )
                Screen.WIFI_CONFIG -> WifiConfigScreen(
                    settingsRepo = settingsRepo,
                    onNavigateBack = { navigateTo(Screen.SETTINGS) }
                )
            }
        }
    }
}

@Composable
private fun ScreenTransitionSurface(
    isTopPage: Boolean,
    content: @Composable () -> Unit
) {
    val topPageShadow = if (isTopPage) {
        Modifier.shadow(
            elevation = 8.dp,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.18f),
            spotColor = Color.Black.copy(alpha = 0.5f)
        )
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (isTopPage) 1f else 0f)
            .then(topPageShadow),
        color = MaterialTheme.colorScheme.background
    ) {
        content()
    }
}
