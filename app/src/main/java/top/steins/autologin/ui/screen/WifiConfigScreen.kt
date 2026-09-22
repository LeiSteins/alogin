package top.steins.autologin.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.ui.component.AppearEasing
import top.steins.autologin.ui.component.DismissEasing
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardElevation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConfigScreen(
    targetWifis: List<String>,
    onAddTargetWifi: (String) -> Unit,
    onRemoveTargetWifi: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var newSsid by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
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
            onAddTargetWifi(trimmed)
            newSsid = ""
            focusManager.clearFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NavigationTopBar(
                    title = stringResource(R.string.wifi_config_title),
                    onNavigateBack = onNavigateBack
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
                        label = { Text(stringResource(R.string.wifi_add_ssid_label)) },
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
                            contentDescription = stringResource(R.string.action_add),
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
                    Text(stringResource(R.string.wifi_scan_button))
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
                                                        onRemoveTargetWifi(ssid)
                                                        deletingSsids = deletingSsids - ssid
                                                    }
                                                }) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.close),
                                                        contentDescription = stringResource(R.string.action_delete),
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
                            stringResource(R.string.wifi_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 扫描结果 BottomSheet
        if (showScanSheet) {
            ModalBottomSheet(
                onDismissRequest = { showScanSheet = false },
                sheetState = scanSheetState
            ) {
                WifiScanSheetContent(
                    configuredSsids = targetWifis.toSet(),
                    onSelectWifi = { ssid ->
                        onAddTargetWifi(ssid)
                        showScanSheet = false
                        scope.launch {
                            onShowToast(resources.getString(R.string.wifi_added, ssid))
                        }
                    }
                )
            }
        }
    }
}
