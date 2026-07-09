package top.steins.autologin.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    settingsRepo: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val username by settingsRepo.username.collectAsState(initial = settingsRepo.getUsername())
    val password by settingsRepo.password.collectAsState(initial = settingsRepo.getPassword())

    var editUser by remember(username) { mutableStateOf(username) }
    var editPass by remember(password) { mutableStateOf(password) }

    val scope = rememberCoroutineScope()
    val toastState = rememberCapsuleToastState(scope)
    val focusManager = LocalFocusManager.current
    val passwordFocusRequester = remember { FocusRequester() }

    fun saveAndExit() {
        settingsRepo.saveCredentials(editUser, editPass)
        focusManager.clearFocus()
        scope.launch {
            toastState.show("保存成功")
            kotlinx.coroutines.delay(500)
            onNavigateBack()
        }
    }

    BackHandler(onBack = onNavigateBack)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("账号管理") },
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
                OutlinedTextField(
                    value = editUser,
                    onValueChange = { editUser = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { passwordFocusRequester.requestFocus() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter) {
                                if (event.type == KeyEventType.KeyUp) {
                                    passwordFocusRequester.requestFocus()
                                }
                                true
                            } else {
                                false
                            }
                        }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = editPass,
                    onValueChange = { editPass = it },
                    label = { Text("密码") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { saveAndExit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter) {
                                if (event.type == KeyEventType.KeyUp) {
                                    saveAndExit()
                                }
                                true
                            } else {
                                false
                            }
                        }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { saveAndExit() },
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
