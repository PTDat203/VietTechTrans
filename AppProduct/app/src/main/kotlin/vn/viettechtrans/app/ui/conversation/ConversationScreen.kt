package vn.viettechtrans.app.ui.conversation

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.history.ConversationStore
import vn.viettechtrans.app.history.ConversationSummary
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.mt.rules.TextRules
import vn.viettechtrans.app.ui.common.GradientButton
import vn.viettechtrans.app.ui.common.HeaderAction
import vn.viettechtrans.app.ui.common.InputCounter
import vn.viettechtrans.app.ui.common.MismatchDialog
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.VttHeader
import vn.viettechtrans.app.ui.common.cardColor
import vn.viettechtrans.app.ui.translate.roleName

/**
 * Home screen. Two people talk (one language button each); every conversation is saved and
 * listed in the ☰ panel like a chat app, "＋" starts a new one, and a sent sentence can be
 * corrected ("Sửa") and translated again.
 */
@Composable
fun ConversationScreen(
    container: AppContainer,
    onOpenTranslate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenManual: () -> Unit,
) {
    val vm: ConversationViewModel = viewModel { ConversationViewModel(container) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.onShown() }

    // Keep the screen on during a conversation.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    var pendingTap by remember { mutableStateOf<Party?>(null) }
    val permissionText = stringResource(R.string.mic_permission_needed)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingTap
        pendingTap = null
        if (granted && p != null) vm.tap(p) else if (!granted) vm.showNotice(permissionText)
    }
    val onTap: (Party) -> Unit = { party ->
        if (container.mic.hasPermission()) {
            vm.tap(party)
        } else {
            pendingTap = party
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var typing by rememberSaveable { mutableStateOf(false) }
    // Text given back when the R06 question is dismissed: nothing was sent, the sheet opens again with it.
    var typingDraft by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var editDraft by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            HistoryDrawer(
                history = history,
                currentId = ui.conversationId,
                onNew = { closeDrawerThen(vm::newConversation) },
                onOpen = { id -> closeDrawerThen { vm.open(id) } },
                onRename = { renaming = it },
                onDelete = { deleting = it },
                onTranslate = { closeDrawerThen(onOpenTranslate) },
                onManual = { closeDrawerThen(onOpenManual) },
                onSettings = { closeDrawerThen(onOpenSettings) },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VttHeader(
                title = ui.title ?: stringResource(R.string.conv_new_title),
                subtitle = stringResource(R.string.conv_subtitle),
                onBack = { scope.launch { drawer.open() } },
                backDescription = stringResource(R.string.cd_history),
                navIcon = R.drawable.ic_menu,
                compact = true,
            ) {
                HeaderAction(R.drawable.ic_add, stringResource(R.string.conv_new), vm::newConversation)
            }
            ChatLayout(
                ui = ui,
                onTap = onTap,
                onKeyboard = { typingDraft = ""; typing = true },
                onReplay = vm::replay,
                onEdit = { editing = it },
                header = { Notices(ui, vm::dismissNotice) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (typing) {
        TextSheet(
            title = stringResource(R.string.conv_type),
            initial = typingDraft,
            actions = listOf(
                SheetAction(stringResource(R.string.conv_send_vi), partyBrush(Party.A)) { vm.type(Party.A, it) },
                SheetAction(stringResource(R.string.conv_send_en), partyBrush(Party.B)) { vm.type(Party.B, it) },
            ),
            onDismiss = { typing = false },
        )
    }
    editing?.let { m ->
        TextSheet(
            title = stringResource(if (m.party == Party.A) R.string.conv_edit_title_vi else R.string.conv_edit_title_en),
            initial = editDraft ?: m.source,
            hint = stringResource(R.string.conv_edit_hint),
            actions = listOf(SheetAction(stringResource(R.string.conv_edit_send), partyBrush(m.party)) { vm.edit(m, it) }),
            onDismiss = { editing = null; editDraft = null },
        )
    }
    ui.mismatch?.let { m ->
        MismatchDialog(
            chosen = m.party.lang,
            onSwitch = { vm.answerMismatch(switchLanguage = true) },
            onForce = { vm.answerMismatch(switchLanguage = false) },
            onDismiss = {
                val back = vm.dismissMismatch() ?: return@MismatchDialog
                val turn = back.editTurn
                if (turn == null) {
                    typingDraft = back.text
                    typing = true
                } else {
                    ui.messages.firstOrNull { it.turn == turn }?.let { msg -> editDraft = back.text; editing = msg }
                }
            },
        )
    }
    renaming?.let { c ->
        RenameDialog(c.title, onDismiss = { renaming = null }) { title ->
            vm.rename(c.id, title)
            renaming = null
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_text, c.title)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(c.id); deleting = null }) {
                    Text(stringResource(R.string.history_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Notices(ui: ConversationUi, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ui.loading.isNotEmpty()) {
            Text(
                stringResource(R.string.phase_preparing, ui.loading.map { roleName(it) }.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.hasTranslator == false) NoticeCard(stringResource(R.string.banner_no_mt), Modifier.fillMaxWidth())
        ui.notice?.let { notice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoticeCard(notice, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

class SheetAction(val label: String, val brush: Brush, val onClick: (String) -> Unit)

/**
 * Bottom sheet with a text field and one button per action: typing a sentence
 * ("người câm – mồm đểu thì gõ") or correcting a sent one. Policy R02: at most 500 displayed characters after R01;
 * a longer text is kept (never cut) but cannot be sent, and sending nothing shows "Hãy nhập nội dung cần dịch.".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(title: String, initial: String, actions: List<SheetAction>, onDismiss: () -> Unit, hint: String? = null) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var emptyTried by remember { mutableStateOf(false) }
    val tooLong = TextRules.typedLength(text) > TextRules.MAX_CHARS
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Material 3 guidance: animate the sheet out before removing it from composition, so its
    // dialog window is never left behind (an invisible window would swallow every touch).
    var sending by remember { mutableStateOf(false) }
    fun send(action: SheetAction) {
        if (sending || tooLong) return // a second tap during the hide animation must not send twice
        if (TextRules.typedLength(text) == 0) {
            emptyTried = true
            return
        }
        sending = true
        val typed = text
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            action.onClick(typed)
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    emptyTried = false
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.conv_type_hint)) },
                supportingText = { InputCounter(text, emptyTried) },
                isError = tooLong,
                minLines = 3,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = cardColor(),
                    unfocusedContainerColor = cardColor(),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEach { a ->
                    GradientButton(
                        text = a.label,
                        onClick = { send(a) },
                        enabled = !tooLong,
                        icon = R.drawable.ic_send,
                        brush = a.brush,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= ConversationStore.TITLE_MAX_CHARS) title = it },
                singleLine = true,
                supportingText = { Text("${title.length}/${ConversationStore.TITLE_MAX_CHARS}") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
