package top.steins.autologin.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.scanNearbyWifi
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import top.steins.autologin.ui.component.AppearEasing
import top.steins.autologin.ui.component.DismissEasing
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardBorder
import top.steins.autologin.ui.theme.appCardElevation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConfigScreen(
    settingsRepo: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val targetWifis by settingsRepo.targetWifis.collectAsState(initial = settingsRepo.getTargetWifis())
    var newSsid by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val toastState = rememberCapsuleToastState(scope)
    val focusManager = LocalFocusManager.current
    var showScanSheet by remember { mutableStateOf(false) }
    val scanSheetState = rememberModalBottomSheetState()
    var deletingSsids by remember { mutableStateOf(setOf<String>()) }
    var visibleSsidContents by remember { mutableStateOf(setOf<String>()) }
    var hasInitializedSsidContent by remember { mutableStateOf(false) }
    val wifiItemContainerColor = MaterialTheme.colorScheme.surfaceContainer

    LaunchedEffect(targetWifis) {
        val currentSsids = targetWifis.toSet()
        if (!hasInitializedSsidContent) {
            visibleSsidContents = currentSsids
            hasInitializedSsidContent = true
        } else {
            visibleSsidContents = visibleSsidContents.intersect(currentSsids) - deletingSsids
            val pendingSsids = currentSsids - visibleSsidContents - deletingSsids
            if (pendingSsids.isNotEmpty()) {
                delay(180)
                visibleSsidContents = visibleSsidContents + pendingSsids
            }
        }
    }

    fun addNewSsid() {
        val trimmed = newSsid.trim()
        if (trimmed.isNotEmpty()) {
            settingsRepo.addTargetWifi(trimmed)
            newSsid = ""
            focusManager.clearFocus()
        }
    }

    BackHandler(onBack = onNavigateBack)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("目标 WiFi") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    ),
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
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 添加行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newSsid,
                        onValueChange = { newSsid = it },
                        label = { Text("添加 WiFi SSID") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addNewSsid() }),
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                    addNewSsid()
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { addNewSsid() }) {
                        Icon(
                            painter = painterResource(R.drawable.add_circle),
                            contentDescription = "添加",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 扫描附近的 WiFi 按钮
                Button(
                    onClick = { showScanSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding)
                        .height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add_circle),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("扫描附近的 WiFi")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // WiFi 列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = targetWifis.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn(tween(160)),
                        exit = fadeOut(tween(120))
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(
                                    animationSpec = tween(220, easing = AppearEasing)
                                )
                                .padding(
                                    horizontal = ScreenHorizontalPadding,
                                    vertical = 12.dp
                                ),
                            shape = AppCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = wifiItemContainerColor
                            ),
                            elevation = appCardElevation(),
                            border = appCardBorder()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                targetWifis.forEach { ssid ->
                                    androidx.compose.runtime.key(ssid) {
                                        val contentVisible = ssid in visibleSsidContents && ssid !in deletingSsids

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(56.dp)
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            AnimatedVisibility(
                                                visible = contentVisible,
                                                enter = fadeIn(tween(180, easing = AppearEasing)) +
                                                        slideInHorizontally(tween(180, easing = AppearEasing)) { it / 3 },
                                                exit = fadeOut(tween(160, easing = DismissEasing)) +
                                                        slideOutHorizontally(tween(160, easing = DismissEasing)) { -it / 3 },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    ssid,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }

                                            AnimatedVisibility(
                                                visible = contentVisible,
                                                enter = fadeIn(tween(180, easing = AppearEasing)),
                                                exit = fadeOut(tween(160, easing = DismissEasing))
                                            ) {
                                                IconButton(onClick = {
                                                    deletingSsids = deletingSsids + ssid
                                                    visibleSsidContents = visibleSsidContents - ssid
                                                    scope.launch {
                                                        delay(180)
                                                        settingsRepo.removeTargetWifi(ssid)
                                                        deletingSsids = deletingSsids - ssid
                                                    }
                                                }) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.close),
                                                        contentDescription = "删除",
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = targetWifis.isEmpty() && deletingSsids.isEmpty(),
                        modifier = Modifier.align(Alignment.Center),
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(120))
                    ) {
                        Text(
                            "暂无配置的目标 WiFi",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // 扫描结果 BottomSheet
        if (showScanSheet) {
            ModalBottomSheet(
                onDismissRequest = { showScanSheet = false },
                sheetState = scanSheetState
            ) {
                WifiScanSheetContent(
                    configuredSsids = targetWifis.toSet(),
                    onSelectWifi = { ssid ->
                        settingsRepo.addTargetWifi(ssid)
                        showScanSheet = false
                        scope.launch { toastState.show("已添加 $ssid") }
                    }
                )
            }
        }
    }
}

// ==================== WiFi 扫描结果 Sheet ====================

@Composable
private fun WifiScanResultItem(
    ssid: String,
    strength: Int,
    isConnected: Boolean,
    isAlreadyConfigured: Boolean,
    onClick: () -> Unit
) {
    val cardModifier = if (isAlreadyConfigured) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    }

    Card(
        modifier = cardModifier,
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isAlreadyConfigured -> MaterialTheme.colorScheme.surfaceContainerHigh
                isConnected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = appCardElevation(),
        border = appCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val contentColor = if (isAlreadyConfigured) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }

                    Text(
                        text = ssid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor
                    )
                    if (isConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(已连接)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = signalStrengthLabel(strength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isAlreadyConfigured) {
                Text(
                    text = "已加入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WifiScanSheetContent(
    configuredSsids: Set<String>,
    onSelectWifi: (String) -> Unit
) {
    val context = LocalContext.current
    val scanResults = remember { scanNearbyWifi(context) }
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    @Suppress("DEPRECATION")
    val wifiEnabled = remember { wifiManager.isWifiEnabled }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "附近的 WiFi 网络",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(bottom = 16.dp)
        )

        when {
            !hasPermission -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "需要位置权限才能扫描 WiFi\n请在设置中授予位置权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            !wifiEnabled -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "WiFi 未开启",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            scanResults.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未扫描到附近的 WiFi 网络",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = ScreenHorizontalPadding,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scanResults.size) { index ->
                        val result = scanResults[index]
                        WifiScanResultItem(
                            ssid = result.ssid,
                            strength = result.strength,
                            isConnected = result.isConnected,
                            isAlreadyConfigured = result.ssid in configuredSsids,
                            onClick = {
                                if (result.ssid !in configuredSsids) {
                                    onSelectWifi(result.ssid)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}



private fun signalStrengthLabel(strength: Int): String = when {
    strength >= -50 -> "信号: 极强"
    strength >= -60 -> "信号: 强"
    strength >= -70 -> "信号: 一般"
    strength >= -80 -> "信号: 弱"
    else -> "信号: 极弱"
}
