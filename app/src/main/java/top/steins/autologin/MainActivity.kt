package top.steins.autologin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import top.steins.autologin.ui.theme.AloginTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by lazy {
        ViewModelProvider(this)[AppViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AloginTheme {
                AppRoot(viewModel = appViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appViewModel.onAppForegrounded()
    }
}
