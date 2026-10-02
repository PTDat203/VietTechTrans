package vn.viettechtrans.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.cardColor
import vn.viettechtrans.app.ui.conversation.ConversationScreen
import vn.viettechtrans.app.ui.experiments.ExperimentsScreen
import vn.viettechtrans.app.ui.history.HistoryScreen
import vn.viettechtrans.app.ui.manual.ManualScreen
import vn.viettechtrans.app.ui.settings.SettingsScreen
import vn.viettechtrans.app.ui.theme.LocalGradients
import vn.viettechtrans.app.ui.translate.TranslateScreen

/** Top-level destinations; [route] doubles as the manual section id. */
enum class TopDestination(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    CONVERSATION("conversation", R.string.nav_conversation, R.drawable.ic_forum),
    TRANSLATE("translate", R.string.nav_translate, R.drawable.ic_translate),
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
    val go: (TopDestination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val host: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(navController = navController, startDestination = TopDestination.CONVERSATION.route, modifier = modifier) {
            composable(TopDestination.TRANSLATE.route) {
                TranslateScreen(container, onHelp = { navController.openManual(TopDestination.TRANSLATE.route) })
            }
            composable(TopDestination.CONVERSATION.route) {
                ConversationScreen(container, onHelp = { navController.openManual(TopDestination.CONVERSATION.route) })
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

    // Surface root so text without an explicit color follows the theme (onBackground in dark mode).
    // Floating pill bar on phones, floating rail on wider windows (≥ 600dp).
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= 600.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PillRail(currentRoute, go)
                    host(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    host(Modifier.weight(1f).fillMaxWidth())
                    PillBar(currentRoute, go)
                }
            }
        }
    }
}

/** Bottom floating pill: the selected tab expands into a gradient pill with its label. */
@Composable
private fun PillBar(currentRoute: String?, onSelect: (TopDestination) -> Unit) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            .fillMaxWidth(),
        shape = CircleShape,
        color = cardColor(),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route
                val weight by animateFloatAsState(if (selected) 2.4f else 1f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "w")
                PillItem(dest, selected, onClick = { onSelect(dest) }, horizontal = true, modifier = Modifier.weight(weight))
            }
        }
    }
}

/** Left floating rail for tablets / landscape: icon in a gradient pill, label below. */
@Composable
private fun PillRail(currentRoute: String?, onSelect: (TopDestination) -> Unit) {
    Surface(
        modifier = Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
            .width(88.dp)
            .fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        color = cardColor(),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopDestination.entries.forEach { dest ->
                PillItem(dest, currentRoute == dest.route, onClick = { onSelect(dest) }, horizontal = false)
            }
        }
    }
}

@Composable
private fun PillItem(
    dest: TopDestination,
    selected: Boolean,
    onClick: () -> Unit,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(dest.label)
    val g = LocalGradients.current
    val tint by animateColorAsState(
        if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tint",
    )
    // One semantics node per tab: TalkBack reads the label once, tests find it by description.
    val itemModifier = modifier
        .clip(CircleShape)
        .selectable(selected = selected, onClick = onClick, role = Role.Tab)
        .semantics { contentDescription = label }
    if (horizontal) {
        Row(
            modifier = itemModifier
                .height(52.dp)
                .then(if (selected) Modifier.background(g.brand, CircleShape) else Modifier)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(dest.icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    } else {
        Column(modifier = itemModifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 34.dp)
                    .clip(CircleShape)
                    .then(if (selected) Modifier.background(g.brand) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(dest.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {},
            )
        }
    }
}
