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
    val navController = rememberNavController()
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
                studentId = studentId,
                usedFlow = usedFlow,
                targetWifis = targetWifis,
                onCheckStatus = { scope.launch { checkStatus() } },
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
