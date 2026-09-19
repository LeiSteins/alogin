package top.steins.autologin.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * 注册应用页面，并提供只对该页面返回栈条目生效的返回回调。
 *
 * 页面退出后，即使退出动画期间再次触发原回调，也不会继续弹出上一层页面。
 */
fun NavGraphBuilder.appComposable(
    destination: AppDestination,
    navController: NavHostController,
    content: @Composable (onNavigateBack: () -> Unit) -> Unit
) {
    composable(destination.route) { backStackEntry ->
        content {
            if (navController.currentBackStackEntry == backStackEntry) {
                navController.popBackStack()
            }
        }
    }
}
