package vn.viettechtrans.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.Diagnostics
import vn.viettechtrans.app.R
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.ui.common.ScreenScaffold

/** M0: "Giới thiệu" card (build facts + translator status). Settings proper arrive in M7. */
@Composable
fun SettingsScreen(container: AppContainer, onHelp: () -> Unit) {
    val info = container.buildInfo
    var coreMt by remember { mutableStateOf<CoreMt?>(null) }
    LaunchedEffect(Unit) { coreMt = container.coreMt() }

    val translatorText = when (val mt = coreMt) {
        null -> stringResource(R.string.settings_translator_checking)
        CoreMt.NotInstalled -> stringResource(R.string.settings_translator_none)
        is CoreMt.Invalid -> stringResource(R.string.settings_translator_invalid, mt.reasons.joinToString("; "))
        is CoreMt.Available -> stringResource(R.string.settings_translator_ready)
    }

    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var memory by remember { mutableStateOf("…") }
    var lastExit by remember { mutableStateOf<Diagnostics.LastExit?>(null) }
    var lastCrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            memory = Diagnostics.memorySummary(context)
            lastExit = Diagnostics.lastExit(context)
            lastCrash = Diagnostics.lastCrash(context)
        }
    }

    ScreenScaffold(title = stringResource(R.string.nav_settings), onHelp = onHelp) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium)
                InfoRow(stringResource(R.string.diag_memory), memory)
                InfoRow(
                    stringResource(R.string.diag_last_exit),
                    lastExit?.let { e ->
                        "${e.whenText}: ${e.reason}" + (e.description?.let { " ($it)" } ?: "") +
                            " · PSS ${e.pssMb} MB"
                    } ?: stringResource(R.string.diag_none),
                )
                lastCrash?.let { crash ->
                    Text(stringResource(R.string.diag_last_crash), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    SelectionContainer {
                        Text(crash.lines().take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.diag_refresh)) }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                InfoRow(
                    stringResource(R.string.settings_build),
                    stringResource(if (info.offlineBuild) R.string.build_offline else R.string.build_dev) + " · " + info.buildType,
                )
                InfoRow(stringResource(R.string.settings_version), "${info.versionName} (${info.versionCode})")
                InfoRow(
                    stringResource(R.string.settings_git),
                    info.gitSha + if (info.gitDirty) " " + stringResource(R.string.settings_git_dirty) else "",
                )
                InfoRow(stringResource(R.string.settings_translator), translatorText)
                if (!info.offlineBuild) {
                    Text(
                        stringResource(R.string.settings_translator_dev_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
