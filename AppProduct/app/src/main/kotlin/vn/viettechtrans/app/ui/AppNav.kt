package vn.viettechtrans.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.conversation.ConversationScreen
import vn.viettechtrans.app.ui.experiments.ExperimentsScreen
import vn.viettechtrans.app.ui.history.HistoryScreen
import vn.viettechtrans.app.ui.manual.ManualScreen
import vn.viettechtrans.app.ui.settings.SettingsScreen
import vn.viettechtrans.app.ui.translate.TranslateScreen

/** Top-level destinations; [route] doubles as the manual section id. */
enum class TopDestination(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    TRANSLATE("translate", R.string.nav_translate, R.drawable.ic_translate),
    CONVERSATION("conversation", R.string.nav_conversation, R.drawable.ic_forum),
    HISTORY("history", R.string.nav_history, R.drawable.ic_history),
    EXPERIMENTS("experiments", R.string.nav_experiments, R.drawable.ic_science),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_settings),
}

private const val MANUAL_ROUTE = "manual/{section}"

private fun NavHostController.openManual(section: String) = navigate("manual/$section")

@Composable
fun VttApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Bottom bar on phones, navigation rail on wider windows (adaptive layout).
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { dest ->
                item(
                    selected = currentRoute == dest.route,
                    onClick = {
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(painterResource(dest.icon), contentDescription = null) },
                    label = { Text(stringResource(dest.label)) },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = TopDestination.TRANSLATE.route) {
            composable(TopDestination.TRANSLATE.route) {
                TranslateScreen(container, onHelp = { navController.openManual(TopDestination.TRANSLATE.route) })
            }
            composable(TopDestination.CONVERSATION.route) {
                ConversationScreen(onHelp = { navController.openManual(TopDestination.CONVERSATION.route) })
            }
            composable(TopDestination.HISTORY.route) {
                HistoryScreen(onHelp = { navController.openManual(TopDestination.HISTORY.route) })
            }
            composable(TopDestination.EXPERIMENTS.route) {
                ExperimentsScreen(onHelp = { navController.openManual(TopDestination.EXPERIMENTS.route) })
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(container, onHelp = { navController.openManual(TopDestination.SETTINGS.route) })
            }
            composable(MANUAL_ROUTE) { entry ->
                ManualScreen(
                    section = entry.arguments?.getString("section"),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
