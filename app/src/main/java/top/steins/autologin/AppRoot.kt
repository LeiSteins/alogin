package top.steins.autologin

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.navigation.AppDestination
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.SelfServiceRepository
import top.steins.autologin.network.checkLoginStatus
import top.steins.autologin.network.getDeviceIP
import top.steins.autologin.network.getWifiSSID
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val selfServiceRepository = remember { SelfServiceRepository() }
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // ---- 网络状态（提升至此，避免页面切换时重复检测） ----
    val targetWifis by settingsRepo.targetWifis.collectAsState(initial = settingsRepo.getTargetWifis())
    val username by settingsRepo.username.collectAsState(initial = settingsRepo.getUsername())
    var wifiName by remember { mutableStateOf("加载中…") }
    var ipAddress by remember { mutableStateOf("加载中…") }
    var isOnline by remember { mutableStateOf(false) }
    var accountOverview by remember { mutableStateOf<AccountOverview?>(null) }
    var isAccountInfoLoading by remember { mutableStateOf(false) }
    var accountInfoError by remember { mutableStateOf("") }
    var observedUsername by remember { mutableStateOf(username) }

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

    fun clearAccountInfo() {
        accountOverview = null
        accountInfoError = ""
        isAccountInfoLoading = false
        selfServiceRepository.clearSession()
    }

    suspend fun refreshAccountInfo() {
        if (username.isBlank()) {
            accountInfoError = "请先在账号管理中配置账号"
            return
        }

        isAccountInfoLoading = true
        accountInfoError = ""
        when (val result = selfServiceRepository.loadAccountOverview(username, ipAddress)) {
            is AccountOverviewResult.Success -> accountOverview = result.overview
            is AccountOverviewResult.Failure -> accountInfoError = result.message
        }
        isAccountInfoLoading = false
    }

    // 旧网关页面只用于判断当前设备是否已认证；账号详情改由自助服务获取。
    suspend fun checkStatus() {
        if (!targetWifis.contains(wifiName)) {
            isOnline = false
            clearAccountInfo()
            return
        }

        val status = checkLoginStatus()
        isOnline = status.isLoggedIn
        if (status.isLoggedIn) {
            refreshAccountInfo()
        } else {
            clearAccountInfo()
        }
    }

    LaunchedEffect(username) {
        if (username == observedUsername) return@LaunchedEffect
        observedUsername = username
        selfServiceRepository.clearSession()
        accountOverview = null
        accountInfoError = ""
        if (isOnline && targetWifis.contains(wifiName) && username.isNotBlank()) {
            refreshAccountInfo()
        }
    }

    fun navigateTo(destination: AppDestination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
        }
    }

    // 网络监听（仅注册一次，跨页面持久）
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        wifiName = getWifiSSID(context)
        ipAddress = getDeviceIP(context)
        scope.launch { checkStatus() }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    wifiName = getWifiSSID(context)
                    ipAddress = getDeviceIP(context)
                    checkStatus()
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    wifiName = "未连接"
                    ipAddress = "无"
                    isOnline = false
                    clearAccountInfo()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    scope.launch {
                        wifiName = getWifiSSID(context)
                        ipAddress = getDeviceIP(context)
                        checkStatus()
                    }
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it })
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it / 3 })
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it / 3 })
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it })
        }
    ) {
        composable(AppDestination.Home.route) {
            HomeScreen(
                settingsRepo = settingsRepo,
                wifiName = wifiName,
                ipAddress = ipAddress,
                isOnline = isOnline,
                accountOverview = accountOverview,
                isAccountInfoLoading = isAccountInfoLoading,
                accountInfoError = accountInfoError,
                targetWifis = targetWifis,
                onCheckStatus = { scope.launch { checkStatus() } },
                onLogoutDevice = { macAddress ->
                    selfServiceRepository.logoutDevice(macAddress)
                },
                onWifiNameChange = { wifiName = it },
                onIpAddressChange = { ipAddress = it },
                onNavigateToAccount = { navigateTo(AppDestination.Account) },
                onNavigateToSettings = { navigateTo(AppDestination.Settings) }
            )
        }

        composable(AppDestination.Account.route) {
            AccountScreen(
                settingsRepo = settingsRepo,
                onNavigateBack = navController::popBackStack
            )
        }

        composable(AppDestination.Settings.route) {
            SettingsScreen(
                settingsRepo = settingsRepo,
                onNavigateBack = navController::popBackStack,
                onNavigateToLog = { navigateTo(AppDestination.Log) },
                onNavigateToWifiConfig = { navigateTo(AppDestination.WifiConfig) }
            )
        }

        composable(AppDestination.Log.route) {
            LogScreen(onNavigateBack = navController::popBackStack)
        }

        composable(AppDestination.WifiConfig.route) {
            WifiConfigScreen(
                settingsRepo = settingsRepo,
                onNavigateBack = navController::popBackStack
            )
        }
    }
}
