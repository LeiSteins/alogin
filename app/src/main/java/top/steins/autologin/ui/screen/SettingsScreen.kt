package top.steins.autologin.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import top.steins.autologin.BuildConfig
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardBorder
import top.steins.autologin.ui.theme.appCardElevation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    updateState: UpdateState,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToWifiConfig: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit
) {
    val targetWifis by settingsRepo.targetWifis.collectAsState(initial = settingsRepo.getTargetWifis())
    val optionContainerColor = MaterialTheme.colorScheme.surfaceContainer

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("设置") },
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
                    .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 目标 WiFi 入口
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = optionContainerColor
                    ),
                    elevation = appCardElevation(),
                    border = appCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToWifiConfig() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "目标 WiFi",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "${targetWifis.size} 个已配置",
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

                Spacer(modifier = Modifier.height(12.dp))

                // HTTP Log 入口
                val logEntries by HttpLogStorage.logs.collectAsState()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = optionContainerColor
                    ),
                    elevation = appCardElevation(),
                    border = appCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToLog() }
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

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = optionContainerColor
                    ),
                    elevation = appCardElevation(),
                    border = appCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = updateState !is UpdateState.Checking) {
                                if (updateState is UpdateState.Available) {
                                    onDownloadUpdate()
                                } else {
                                    onCheckForUpdates()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when (updateState) {
                                    UpdateState.Idle -> stringResource(
                                        R.string.update_idle,
                                        BuildConfig.VERSION_NAME
                                    )
                                    UpdateState.Checking -> stringResource(R.string.update_checking)
                                    is UpdateState.UpToDate -> stringResource(
                                        R.string.update_up_to_date,
                                        BuildConfig.VERSION_NAME
                                    )
                                    is UpdateState.Available -> stringResource(
                                        R.string.update_available,
                                        updateState.update.version
                                    )
                                    is UpdateState.Error -> stringResource(R.string.update_check_failed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (updateState is UpdateState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
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
}

// ==================== 目标 WiFi 配置页 ====================
