package vn.viettechtrans.app.ui.translate

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold
import vn.viettechtrans.app.ui.common.TalkButton

@Composable
fun langName(lang: Lang): String = stringResource(if (lang == Lang.VI) R.string.lang_vi else R.string.lang_en)

@Composable
fun roleName(role: EngineRole): String = stringResource(
    when (role) {
        EngineRole.STT_VI -> R.string.role_stt_vi
        EngineRole.STT_EN -> R.string.role_stt_en
        EngineRole.TTS_VI -> R.string.role_tts_vi
        EngineRole.TTS_EN -> R.string.role_tts_en
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(container: AppContainer, onHelp: () -> Unit) {
    val vm: TranslateViewModel = viewModel { TranslateViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val permissionText = stringResource(R.string.mic_permission_needed)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.micTapped() else vm.showNotice(permissionText)
    }
    val onMic = {
        if (container.mic.hasPermission()) vm.micTapped() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val directions = listOf(
        Direction.VI_TO_EN to R.string.dir_vi_to_en,
        Direction.EN_TO_VI to R.string.dir_en_to_vi,
    )

    ScreenScaffold(title = stringResource(R.string.nav_translate), onHelp = onHelp) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            directions.forEachIndexed { index, (direction, label) ->
                SegmentedButton(
                    selected = s.direction == direction,
                    onClick = { vm.setDirection(direction) },
                    enabled = !s.busy,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = directions.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }

        OutlinedTextField(
            value = s.input,
            onValueChange = vm::onInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.translate_input_label, langName(s.direction.source))) },
            supportingText = { Text("${s.input.length}/${TranslateViewModel.MAX_TYPED_CHARS}") },
            minLines = 3,
            enabled = !s.busy,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::translateInput, enabled = !s.busy && s.input.isNotBlank()) {
                Text(stringResource(R.string.action_translate))
            }
            OutlinedButton(onClick = vm::playSource, enabled = !s.busy && s.input.isNotBlank()) {
                Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null)
                Text(stringResource(R.string.action_play_source), modifier = Modifier.padding(start = 4.dp))
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TalkButton(
                listening = s.phase == Phase.LISTENING || s.phase == Phase.LOADING,
                enabled = s.phase != Phase.RECOGNIZING && s.phase != Phase.TRANSLATING,
                level = s.level,
                contentDescription = stringResource(R.string.translate_speak),
                onClick = onMic,
            )
            Text(statusText(s), style = MaterialTheme.typography.bodyMedium)
            if (s.loadedMs.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.models_ready,
                        s.loadedMs.entries.map { (role, ms) -> "${roleName(role)} ${"%.1f".format(ms / 1000f)} s" }.joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (s.partial.isNotBlank()) {
                Text(s.partial, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            }
            if (s.phase == Phase.TRANSLATING) {
                TextButton(onClick = vm::cancelTranslation) { Text(stringResource(R.string.action_cancel)) }
            }
        }

        s.notice?.let { notice ->
            NoticeCard(notice, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.action_close)) }
        }

        if (s.hasTranslator == false) NoticeCard(stringResource(R.string.banner_no_mt))

        if (s.translation != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.result_title, langName(s.direction.target)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SelectionContainer { Text(s.translation!!, style = MaterialTheme.typography.headlineSmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::playTranslation, enabled = !s.busy) {
                            Icon(painterResource(R.drawable.ic_volume_up), contentDescription = stringResource(R.string.action_play_translation))
                        }
                        if (s.phase == Phase.SPEAKING) {
                            TextButton(onClick = vm::stopSpeaking) { Text(stringResource(R.string.action_stop_speaking)) }
                        }
                    }
                    s.timings?.let { Text(it.format(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        } else if (s.phase == Phase.SPEAKING) {
            TextButton(onClick = vm::stopSpeaking) { Text(stringResource(R.string.action_stop_speaking)) }
        }
        if (s.translation == null && s.timings?.sttMs != null) {
            Text(s.timings!!.format(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun statusText(s: TranslateUiState): String = when (s.phase) {
    Phase.IDLE -> if (s.loading.isEmpty()) {
        stringResource(R.string.phase_idle)
    } else {
        stringResource(R.string.phase_preparing, s.loading.map { roleName(it) }.joinToString(", "))
    }
    Phase.LOADING -> if (s.loading.isEmpty()) {
        stringResource(R.string.phase_starting_mic)
    } else {
        stringResource(R.string.phase_loading, s.loading.map { roleName(it) }.joinToString(", "))
    }
    Phase.LISTENING -> stringResource(R.string.phase_listening)
    Phase.RECOGNIZING -> stringResource(R.string.phase_recognizing)
    Phase.TRANSLATING -> stringResource(R.string.phase_translating)
    Phase.SPEAKING -> stringResource(R.string.phase_speaking)
}
