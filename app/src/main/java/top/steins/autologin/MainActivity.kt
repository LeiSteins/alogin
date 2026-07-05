package top.steins.autologin

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.login
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.theme.AloginTheme
import java.net.Inet4Address
import java.net.NetworkInterface

private enum class Screen { HOME, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AloginTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    AnimatedContent(
        targetState = currentScreen,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            if (targetState == Screen.SETTINGS) {
                // HOME → SETTINGS：设置页从右滑入，主页向左滑出 1/3
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(300)))
                    .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut(animationSpec = tween(300)))
            } else {
                // SETTINGS → HOME：主页从左侧 1/3 滑回，设置页向右滑出
                (slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn(animationSpec = tween(300)))
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(300)))
            }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                settingsRepo = settingsRepo,
                onNavigateToSettings = { currentScreen = Screen.SETTINGS }
            )
            Screen.SETTINGS -> SettingsScreen(
                settingsRepo = settingsRepo,
                onNavigateBack = { currentScreen = Screen.HOME }
            )
        }
    }
}

// ==================== 主页 ====================

@Composable
fun HomeScreen(
    settingsRepo: SettingsRepository,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastState = rememberCapsuleToastState(scope)

    val targetWifi by settingsRepo.targetWifi.collectAsState(initial = settingsRepo.getTargetWifi())
    val username by settingsRepo.username.collectAsState(initial = settingsRepo.getUsername())
    val password by settingsRepo.password.collectAsState(initial = settingsRepo.getPassword())

    var wifiName by remember { mutableStateOf("加载中…") }
    var ipAddress by remember { mutableStateOf("加载中…") }
    var isLoggingIn by remember { mutableStateOf(false) }
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

    // 首次启动请求位置权限
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // 监听网络变化，WiFi 切换时自动刷新名称和 IP
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        // 首次立即获取
        wifiName = getWifiSSID(context)
        ipAddress = getDeviceIP(context)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiName = getWifiSSID(context)
                ipAddress = getDeviceIP(context)
            }

            override fun onLost(network: Network) {
                wifiName = "未连接"
                ipAddress = "无"
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiName = getWifiSSID(context)
                    ipAddress = getDeviceIP(context)
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    // 判断是否连接到目标 WiFi
    val isTargetWifi = wifiName == targetWifi

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 设置按钮 — 右上角
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.add_circle),
                    contentDescription = "设置",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 中间内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // WiFi 名称
                Text(
                    text = "WiFi 名称",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = wifiName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(32.dp))

                // IP 地址
                Text(
                    text = "IP 地址",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = ipAddress,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 按钮 — 常驻显示
                Spacer(modifier = Modifier.height(40.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(0.5f),
                    onClick = {
                        if (isTargetWifi) {
                            if (username.isBlank() || password.isBlank()) {
                                scope.launch { toastState.show("请先在设置中配置账号和密码") }
                            } else {
                                isLoggingIn = true
                                scope.launch {
                                    val result = login(username, password)
                                    isLoggingIn = false
                                    when (result) {
                                        is LoginResult.Success -> toastState.show("登录成功")
                                        is LoginResult.Failure -> toastState.show(result.message)
                                        is LoginResult.NetworkError -> toastState.show(result.message)
                                    }
                                }
                            }
                        } else {
                            scope.launch {
                                toastState.show("正在刷新…")
                                kotlinx.coroutines.delay(100)
                                wifiName = getWifiSSID(context)
                                ipAddress = getDeviceIP(context)
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
                        Text(if (isTargetWifi) "登 录" else "刷 新")
                    }
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

// ==================== 设置页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val targetWifi by settingsRepo.targetWifi.collectAsState(initial = settingsRepo.getTargetWifi())
    val username by settingsRepo.username.collectAsState(initial = settingsRepo.getUsername())
    val password by settingsRepo.password.collectAsState(initial = settingsRepo.getPassword())

    var editWifi by remember(targetWifi) { mutableStateOf(targetWifi) }
    var editUser by remember(username) { mutableStateOf(username) }
    var editPass by remember(password) { mutableStateOf(password) }

    BackHandler(onBack = onNavigateBack)

    val scope = rememberCoroutineScope()
    val toastState = rememberCapsuleToastState(scope)
    val focusManager = LocalFocusManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(painterResource(R.drawable.arrow_back), contentDescription = "返回")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = editWifi,
                    onValueChange = { editWifi = it },
                    label = { Text("目标 WiFi") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = editUser,
                    onValueChange = { editUser = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = editPass,
                    onValueChange = { editPass = it },
                    label = { Text("密码") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        settingsRepo.saveAll(editWifi, editUser, editPass)
                        focusManager.clearFocus()
                        scope.launch {
                            toastState.show("保存成功")
                            kotlinx.coroutines.delay(500)
                            onNavigateBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("保 存")
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

// ==================== 工具函数 ====================

fun getWifiSSID(context: Context): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiManager.getConnectionInfo()
        } else {
            wifiManager.connectionInfo
        }
        var ssid = wifiInfo.ssid ?: "未知"
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        when {
            ssid == "<unknown ssid>" -> "未知"
            ssid.isEmpty() -> "未连接"
            else -> ssid
        }
    } catch (e: Exception) {
        "获取失败"
    }
}

@SuppressLint("DefaultLocale")
fun getDeviceIP(context: Context): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiManager.getConnectionInfo().ipAddress
        } else {
            wifiManager.connectionInfo.ipAddress
        }
        if (ipInt != 0) {
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        }
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val host = address.hostAddress
                    if (host != null && !host.startsWith("127.")) {
                        return host
                    }
                }
            }
        }
        "无"
    } catch (e: Exception) {
        "获取失败"
    }
}
