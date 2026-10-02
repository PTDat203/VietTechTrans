package vn.viettechtrans.app.ui.conversation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.turn.ConvPhase
import vn.viettechtrans.app.turn.InterruptPolicy
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.ui.common.GradientButton
import vn.viettechtrans.app.ui.common.HeaderAction
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.VttHeader
import vn.viettechtrans.app.ui.common.cardColor
import vn.viettechtrans.app.ui.translate.roleName

@Composable
fun ConversationScreen(container: AppContainer, onHelp: () -> Unit) {
    val context = LocalContext.current
    val vm: ConversationViewModel = viewModel { ConversationViewModel(container, context) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.onShown() }

    // Keep the screen on during a conversation.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

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

    val header: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillChip(
                    selected = ui.autoAlternate,
                    onClick = { vm.setAutoAlternate(!ui.autoAlternate) },
                    text = stringResource(R.string.conv_auto),
                    icon = R.drawable.ic_autorenew,
                )
                PillChip(
                    selected = ui.policy == InterruptPolicy.QUEUE,
                    onClick = { vm.setPolicy(if (ui.policy == InterruptPolicy.CUT) InterruptPolicy.QUEUE else InterruptPolicy.CUT) },
                    text = stringResource(if (ui.policy == InterruptPolicy.CUT) R.string.conv_policy_cut else R.string.conv_policy_queue),
                    icon = R.drawable.ic_hourglass_top,
                )
                if (ui.layout == ConvLayout.SPLIT) {
                    PillChip(
                        selected = ui.rotateTop,
                        onClick = { vm.setRotateTop(!ui.rotateTop) },
                        text = stringResource(R.string.conv_rotate_top),
                        icon = R.drawable.ic_screen_rotation,
                    )
                }
                if (ui.messages.isNotEmpty() && ui.engine.phase == ConvPhase.Idle) {
                    TextButton(onClick = vm::clear) { Text(stringResource(R.string.conv_clear)) }
                }
            }
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
                    TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val split = ui.layout == ConvLayout.SPLIT
        VttHeader(title = stringResource(R.string.nav_conversation), subtitle = stringResource(R.string.conv_subtitle)) {
            HeaderAction(
                if (split) R.drawable.ic_chat else R.drawable.ic_splitscreen,
                stringResource(if (split) R.string.conv_layout_chat else R.string.conv_layout_split),
            ) { vm.setLayout(if (split) ConvLayout.CHAT else ConvLayout.SPLIT) }
            HeaderAction(R.drawable.ic_help, stringResource(R.string.cd_help), onHelp)
        }
        val modifier = Modifier.weight(1f)
        when (ui.layout) {
            ConvLayout.CHAT -> ChatLayout(ui, onTap, onKeyboard = { typing = true }, onReplay = vm::replay, header = header, modifier = modifier)
            ConvLayout.SPLIT -> SplitLayout(ui, onTap, onKeyboard = { typing = true }, onStopSpeaking = vm::stopSpeaking, header = header, modifier = modifier)
        }
    }

    if (typing) {
        TypeSheet(
            onDismiss = { typing = false },
            onSend = { party, text ->
                vm.type(party, text)
                typing = false
            },
        )
    }
}

/** Rounded toggle chip used for the conversation options. */
@Composable
private fun PillChip(selected: Boolean, onClick: () -> Unit, text: String, icon: Int) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = cardColor(),
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
    )
}

/** Typing fallback ("người câm – mồm đểu thì gõ"): one send button per language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSheet(onDismiss: () -> Unit, onSend: (Party, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.conv_type), style = MaterialTheme.typography.titleLarge)
            TextField(
                value = text,
                onValueChange = { if (it.length <= 1000) text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.conv_type_hint)) },
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
                GradientButton(
                    text = stringResource(R.string.conv_send_vi),
                    onClick = { onSend(Party.A, text) },
                    enabled = text.isNotBlank(),
                    icon = R.drawable.ic_send,
                    brush = partyBrush(Party.A),
                    modifier = Modifier.weight(1f),
                )
                GradientButton(
                    text = stringResource(R.string.conv_send_en),
                    onClick = { onSend(Party.B, text) },
                    enabled = text.isNotBlank(),
                    icon = R.drawable.ic_send,
                    brush = partyBrush(Party.B),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
