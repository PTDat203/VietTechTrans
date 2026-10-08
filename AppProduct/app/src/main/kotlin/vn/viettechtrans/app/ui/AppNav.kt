package vn.viettechtrans.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.ui.conversation.ConversationScreen
import vn.viettechtrans.app.ui.manual.ManualScreen
import vn.viettechtrans.app.ui.settings.SettingsScreen
import vn.viettechtrans.app.ui.translate.TranslateScreen

/**
 * Screens. Like a chat app, the conversation is the home screen; the ☰ panel lists saved
 * conversations and opens the other screens, which have a back arrow (no bottom bar, DR-09).
 * [route] of a screen doubles as its section id in the manual.
 */
enum class Screen(val route: String) {
    CONVERSATION("conversation"),
    TRANSLATE("translate"),
    SETTINGS("settings"),
    MANUAL("manual/{section}"),
}

private fun NavHostController.openManual(section: String) = navigate("manual/$section")

@Composable
fun VttApp(container: AppContainer) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    // Surface root so text without an explicit color follows the theme (onBackground in dark mode).
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavHost(navController = nav, startDestination = Screen.CONVERSATION.route) {
            composable(Screen.CONVERSATION.route) {
                ConversationScreen(
                    container,
                    onOpenTranslate = { nav.navigate(Screen.TRANSLATE.route) },
                    onOpenSettings = { nav.navigate(Screen.SETTINGS.route) },
                    // From the ☰ panel the manual opens at its beginning.
                    onOpenManual = { nav.openManual("top") },
                )
            }
            composable(Screen.TRANSLATE.route) {
                TranslateScreen(container, onBack = back, onHelp = { nav.openManual(Screen.TRANSLATE.route) })
            }
            composable(Screen.SETTINGS.route) {
                SettingsScreen(container, onBack = back, onHelp = { nav.openManual(Screen.SETTINGS.route) })
            }
            composable(Screen.MANUAL.route) { entry ->
                ManualScreen(section = entry.arguments?.getString("section"), onBack = back)
            }
        }
    }
}
