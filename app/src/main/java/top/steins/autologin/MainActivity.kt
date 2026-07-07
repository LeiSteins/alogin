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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.HttpLogEntry
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.checkLoginStatus
import top.steins.autologin.network.login
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.ScaleFadeBox
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.theme.AloginTheme
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, ACCOUNT, SETTINGS, LOG }

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
    val scope = rememberCoroutineScope()

    // ---- 网络状态（提升至此，避免页面切换时重复检测） ----
    val targetWifi by settingsRepo.targetWifi.collectAsState(initial = settingsRepo.getTargetWifi())
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
        if (wifiName != targetWifi) return
        val status = checkLoginStatus()
        isOnline = status.isLoggedIn
        studentId = status.uid
        usedFlow = status.flow
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
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(300)))
                    .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut(animationSpec = tween(300)))
            } else {
                // 后退：旧页面从左侧 1/3 滑回，当前页面向右滑出
                (slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn(animationSpec = tween(300)))
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(300)))
            }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                settingsRepo = settingsRepo,
                wifiName = wifiName,
                ipAddress = ipAddress,
                isOnline = isOnline,
                studentId = studentId,
                usedFlow = usedFlow,
                targetWifi = targetWifi,
                onCheckStatus = { scope.launch { checkStatus() } },
                onWifiNameChange = { wifiName = it },
                onIpAddressChange = { ipAddress = it },
                onNavigateToAccount = { currentScreen = Screen.ACCOUNT },
                onNavigateToSettings = { currentScreen = Screen.SETTINGS }
            )
            Screen.ACCOUNT -> AccountScreen(
                settingsRepo = settingsRepo,
                onNavigateBack = { currentScreen = Screen.HOME }
            )
            Screen.SETTINGS -> SettingsScreen(
                onNavigateBack = { currentScreen = Screen.HOME },
                onNavigateToLog = { currentScreen = Screen.LOG }
            )
            Screen.LOG -> LogScreen(
                onNavigateBack = { currentScreen = Screen.SETTINGS }
            )
        }
    }
}

// ==================== 主页 ====================

@Composable
fun HomeScreen(
    settingsRepo: SettingsRepository,
    wifiName: String,
    ipAddress: String,
    isOnline: Boolean,
    studentId: String,
    usedFlow: String,
    targetWifi: String,
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
    val isTargetWifi = wifiName == targetWifi

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
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 卡片 1: 网络信息
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    elevation = CardDefaults.elevatedCardElevation()
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
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        elevation = CardDefaults.elevatedCardElevation()
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
                    .padding(start = 24.dp, end = 24.dp, bottom = 40.dp)
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
                                val result = login(username, password)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
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
                    title = { Text("账号管理") },
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

// ==================== 设置页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

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
                // HTTP Log 入口
                val logEntries by HttpLogStorage.logs.collectAsState()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLog() },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "HTTP Log",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "${logEntries.size} entries",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ==================== HTTP 日志页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: () -> Unit
) {
    val entries by HttpLogStorage.logs.collectAsState()
    var selectedEntry by remember { mutableStateOf<HttpLogEntry?>(null) }
    val sheetState = rememberModalBottomSheetState()

    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HTTP Log") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无请求记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onClick = { selectedEntry = entry }
                    )
                }
            }
        }
    }

    // 详情底部弹窗
    selectedEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { selectedEntry = null },
            sheetState = sheetState
        ) {
            LogEntryDetail(entry = entry)
        }
    }
}

@Composable
private fun LogEntryCard(entry: HttpLogEntry, onClick: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.elevatedCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 第一行：方法徽章 + 状态码 + 时间戳
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 方法徽章
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (entry.method) {
                        "GET" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        "POST" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = entry.method,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = when (entry.method) {
                            "GET" -> MaterialTheme.colorScheme.primary
                            "POST" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 状态码
                Text(
                    text = if (entry.statusCode > 0) "${entry.statusCode}" else "ERR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        entry.statusCode in 200..299 -> MaterialTheme.colorScheme.primary
                        entry.statusCode in 400..599 -> MaterialTheme.colorScheme.error
                        entry.statusCode > 0 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 时间戳
                Text(
                    text = timeFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // URL
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 请求体
            if (entry.requestBody.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Request:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.requestBody,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 响应体
            if (entry.responseBody.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Response:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.responseBody,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 错误信息
            if (!entry.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LogEntryDetail(entry: HttpLogEntry) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(6.dp),
                color = when (entry.method) {
                    "GET" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    "POST" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = entry.method,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (entry.method) {
                        "GET" -> MaterialTheme.colorScheme.primary
                        "POST" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = if (entry.statusCode > 0) "${entry.statusCode}" else "ERR",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    entry.statusCode in 200..299 -> MaterialTheme.colorScheme.primary
                    entry.statusCode in 400..599 -> MaterialTheme.colorScheme.error
                    entry.statusCode > 0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // URL
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "URL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = entry.url,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 请求体
        if (entry.requestBody.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Request Body",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = entry.requestBody,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 响应体
        if (entry.responseBody.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Response Body",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = entry.responseBody,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 错误
        if (!entry.error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Error",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = entry.error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 底部间距，确保内容不被系统导航栏遮挡
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ==================== 工具函数 ====================

fun formatFlow(flowKb: String): String {
    val kb = flowKb.trim().toLongOrNull() ?: return flowKb
    return when {
        kb >= 1_048_576 -> "${"%.1f".format(kb / 1_048_576.0)} GB"
        kb >= 1024 -> "${"%.1f".format(kb / 1024.0)} MB"
        else -> "$kb KB"
    }
}

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
