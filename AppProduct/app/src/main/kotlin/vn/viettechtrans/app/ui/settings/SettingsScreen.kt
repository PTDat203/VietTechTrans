package vn.viettechtrans.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.Diagnostics
import vn.viettechtrans.app.R
import vn.viettechtrans.app.diagnostics.DeviceCheck
import vn.viettechtrans.app.diagnostics.RuleCaseRunner
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.mt.rules.RuleCases
import vn.viettechtrans.app.ui.common.GradientButton
import vn.viettechtrans.app.ui.common.ScreenScaffold
import vn.viettechtrans.app.ui.common.VttCard

/** Device check, the rule case set and log of the text-rule policy, history storage, diagnostics and build facts. */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onHelp: () -> Unit) {
    val info = container.buildInfo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var coreMt by remember { mutableStateOf<CoreMt?>(null) }
    LaunchedEffect(Unit) { coreMt = container.coreMt() }

    val translatorText = when (val mt = coreMt) {
        null -> stringResource(R.string.settings_translator_checking)
        CoreMt.NotInstalled -> stringResource(R.string.settings_translator_none)
        is CoreMt.Invalid -> stringResource(R.string.settings_translator_invalid, mt.reasons.joinToString("; "))
        is CoreMt.Available -> stringResource(R.string.settings_translator_ready, mt.manifest.packageId)
    }

    var refresh by remember { mutableIntStateOf(0) }
    var memory by remember { mutableStateOf("…") }
    var lastExit by remember { mutableStateOf<Diagnostics.LastExit?>(null) }
    var lastCrash by remember { mutableStateOf<String?>(null) }
    var historyBytes by remember { mutableLongStateOf(0L) }
    var ruleLogCount by remember { mutableIntStateOf(0) }
    var caseCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        caseCount = withContext(Dispatchers.IO) {
            runCatching { RuleCases.parse(Json.parseToJsonElement(context.assets.open(RuleCases.ASSET).use { it.readBytes().decodeToString() })).size }.getOrDefault(0)
        }
    }
    val history by container.conversations.summaries.collectAsStateWithLifecycle()
    LaunchedEffect(refresh, history.size) {
        withContext(Dispatchers.IO) {
            memory = Diagnostics.memorySummary(context)
            lastExit = Diagnostics.lastExit(context)
            lastCrash = Diagnostics.lastCrash(context)
        }
        container.conversations.refresh()
        historyBytes = container.conversations.sizeBytes()
        ruleLogCount = container.ruleLog.count()
    }

    var checking by remember { mutableStateOf(false) }
    var checkText by rememberSaveable { mutableStateOf<String?>(null) }
    var casesRunning by remember { mutableStateOf(false) }
    var casesText by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VietTechTrans", text))
    }

    ScreenScaffold(title = stringResource(R.string.nav_settings), onHelp = onHelp, onBack = onBack) {
        VttCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.check_title), icon = R.drawable.ic_graphic_eq) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.check_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientButton(
                    text = stringResource(if (checking) R.string.check_running else R.string.check_start),
                    enabled = !checking && !casesRunning,
                    onClick = {
                        checking = true
                        checkText = ""
                        scope.launch {
                            try {
                                DeviceCheck(container, context).run { progress -> checkText = progress }
                            } catch (e: Exception) {
                                checkText = (checkText ?: "") + "\nLỗi: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                checking = false
                            }
                        }
                    },
                )
                checkText?.takeIf { it.isNotBlank() }?.let { text ->
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                    if (!checking) {
                        TextButton(onClick = { copy(text) }) { Text(stringResource(R.string.check_copy)) }
                    }
                }
            }
        }
        VttCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.rules_cases_title), icon = R.drawable.ic_translate) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.rules_cases_text, caseCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientButton(
                    text = stringResource(if (casesRunning) R.string.rules_cases_running else R.string.rules_cases_start),
                    enabled = !casesRunning && !checking,
                    onClick = {
                        casesRunning = true
                        casesText = ""
                        scope.launch {
                            try {
                                RuleCaseRunner(container, context).run { progress -> casesText = progress }
                            } catch (e: Exception) {
                                casesText = (casesText ?: "") + "\nLỗi: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                casesRunning = false
                            }
                        }
                    },
                )
                casesText?.takeIf { it.isNotBlank() }?.let { text ->
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                    if (!casesRunning) TextButton(onClick = { copy(text) }) { Text(stringResource(R.string.rules_cases_copy)) }
                }
            }
        }
        VttCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.rules_log_title), icon = R.drawable.ic_info) {
            Column {
                Text(stringResource(R.string.rules_log_text, ruleLogCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    // The clipboard goes through Binder (about 1 MB per transaction): copy the newest entries that fit.
                    TextButton(onClick = {
                        scope.launch {
                            val log = container.ruleLog.text()
                            copy(if (log.length <= MAX_COPY_CHARS) log else log.takeLast(MAX_COPY_CHARS).substringAfter('\n'))
                        }
                    }, enabled = ruleLogCount > 0) {
                        Text(stringResource(R.string.rules_log_copy))
                    }
                    TextButton(onClick = { scope.launch { container.ruleLog.clear(); refresh++ } }, enabled = ruleLogCount > 0) {
                        Text(stringResource(R.string.rules_log_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        VttCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.settings_history), icon = R.drawable.ic_history) {
            InfoRow(
                stringResource(R.string.settings_history_saved),
                stringResource(R.string.settings_history_size, history.size, String.format(Locale.ROOT, "%.1f", historyBytes / 1024.0)),
            )
            TextButton(onClick = { confirmDeleteAll = true }, enabled = history.isNotEmpty()) {
                Text(stringResource(R.string.settings_history_delete_all), color = MaterialTheme.colorScheme.error)
            }
        }
        VttCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.diag_title), icon = R.drawable.ic_info) {
            Column {
                InfoRow(stringResource(R.string.diag_memory), memory)
                InfoRow(
                    stringResource(R.string.diag_last_exit),
                    lastExit?.let { e ->
                        "${e.whenText}: ${e.reason}" + (e.description?.let { " ($it)" } ?: "") + " · PSS ${e.pssMb} MB"
                    } ?: stringResource(R.string.diag_none),
                )
                lastCrash?.let { crash ->
                    Text(stringResource(R.string.diag_last_crash), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    SelectionContainer { Text(crash.lines().take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.diag_refresh)) }
            }
        }
        VttCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.settings_about), icon = R.drawable.ic_settings) {
            Column {
                InfoRow(stringResource(R.string.settings_build), stringResource(R.string.build_offline) + " · " + info.buildType)
                InfoRow(stringResource(R.string.settings_version), "${info.versionName} (${info.versionCode})")
                InfoRow(
                    stringResource(R.string.settings_git),
                    info.gitSha + if (info.gitDirty) " " + stringResource(R.string.settings_git_dirty) else "",
                )
                InfoRow(stringResource(R.string.settings_translator), translatorText)
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.settings_history_delete_all)) },
            text = { Text(stringResource(R.string.settings_history_delete_all_text, history.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    // The rule log holds typed sentences too.
                    scope.launch { container.conversations.deleteAll(); container.ruleLog.clear(); refresh++ }
                }) { Text(stringResource(R.string.history_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Text copied from the rule log at most (UTF-16 chars; Binder limits a transaction to about 1 MB). */
private const val MAX_COPY_CHARS = 200_000
