package top.steins.autologin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.ui.theme.AloginTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by lazy {
        ViewModelProvider(this)[AppViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearanceMode by appViewModel.settingsRepository.appearanceMode
                .collectAsStateWithLifecycle()
            val darkTheme = when (appearanceMode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            AloginTheme(darkTheme = darkTheme) {
                AppRoot(viewModel = appViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appViewModel.onAppForegrounded()
    }
}
