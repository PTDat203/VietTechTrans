package vn.viettechtrans.app.ui.translate

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.ui.common.GradientButton
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold
import vn.viettechtrans.app.ui.common.TalkButton
import vn.viettechtrans.app.ui.common.cardColor
import vn.viettechtrans.app.ui.theme.LocalGradients

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
    LaunchedEffect(Unit) { vm.onShown() }

    ScreenScaffold(
        title = stringResource(R.string.nav_translate),
        subtitle = stringResource(R.string.translate_subtitle),
        onHelp = onHelp,
    ) {
        LanguageBar(
            source = s.direction.source,
            target = s.direction.target,
            enabled = !s.busy && s.phase != Phase.LISTENING,
            onSwap = vm::swap,
        )

        InputCard(s, onInput = vm::onInput, onTranslate = vm::translateInput, onPlaySource = vm::playSource)

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TalkButton(
                listening = s.phase == Phase.LISTENING || s.phase == Phase.LOADING,
                enabled = s.phase != Phase.RECOGNIZING && s.phase != Phase.TRANSLATING,
                level = s.level,
                contentDescription = stringResource(R.string.translate_speak),
                onClick = onMic,
                size = 80.dp,
            )
            Text(
                statusText(s),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (s.loadedMs.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.models_ready,
                        s.loadedMs.entries.map { (role, ms) -> "${roleName(role)} ${"%.1f".format(ms / 1000f)} s" }.joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (s.partial.isNotBlank()) {
                Text(
                    s.partial,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (s.phase == Phase.TRANSLATING) {
                TextButton(onClick = vm::cancelTranslation) { Text(stringResource(R.string.action_cancel)) }
            }
        }

        s.notice?.let { notice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoticeCard(notice, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.action_close)) }
            }
        }

        if (s.hasTranslator == false) NoticeCard(stringResource(R.string.banner_no_mt), Modifier.fillMaxWidth())

        AnimatedVisibility(
            visible = s.translation != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            ResultCard(
                target = s.direction.target,
                translation = s.translation.orEmpty(),
                timings = s.timings?.format(),
                speaking = s.phase == Phase.SPEAKING,
                enabled = !s.busy,
                onPlay = vm::playTranslation,
                onStop = vm::stopSpeaking,
            )
        }
        if (s.translation == null && s.phase == Phase.SPEAKING) {
            TextButton(onClick = vm::stopSpeaking) { Text(stringResource(R.string.action_stop_speaking)) }
        }
        if (s.translation == null && s.timings?.sttMs != null) {
            Text(s.timings!!.format(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
    }
}

/** "[source] ⇄ [target]" pill (Google Translate style); the swap button spins on each tap. */
@Composable
private fun LanguageBar(source: Lang, target: Lang, enabled: Boolean, onSwap: () -> Unit) {
    var turns by remember { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(turns * 180f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "swap")
    Surface(modifier = Modifier.fillMaxWidth(), shape = CircleShape, color = cardColor(), shadowElevation = 3.dp) {
        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LangLabel(source, Modifier.weight(1f))
            val swapText = stringResource(R.string.translate_swap)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(if (enabled) 1f else 0.45f)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(LocalGradients.current.brand)
                    .clickable(enabled = enabled, role = Role.Button) {
                        turns++
                        onSwap()
                    }
                    .semantics { contentDescription = swapText },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_swap_horiz),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.rotate(rotation),
                )
            }
            LangLabel(target, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LangLabel(lang: Lang, modifier: Modifier) {
    AnimatedContent(
        targetState = lang,
        transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() },
        modifier = modifier,
        label = "lang",
    ) { l ->
        Text(
            langName(l),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InputCard(s: TranslateUiState, onInput: (String) -> Unit, onTranslate: () -> Unit, onPlaySource: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cardColor(), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            TextField(
                value = s.input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.translate_input_label, langName(s.direction.source))) },
                textStyle = MaterialTheme.typography.titleMedium,
                minLines = 3,
                enabled = !s.busy,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${s.input.length}/${TranslateViewModel.MAX_TYPED_CHARS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = onPlaySource,
                    enabled = !s.busy && s.input.isNotBlank(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = stringResource(R.string.action_play_source))
                }
                GradientButton(
                    text = stringResource(R.string.action_translate),
                    onClick = onTranslate,
                    enabled = !s.busy && s.input.isNotBlank(),
                    icon = R.drawable.ic_translate,
                )
            }
        }
    }
}

/** Result on the brand gradient: the translation is the hero of the screen. */
@Composable
private fun ResultCard(
    target: Lang,
    translation: String,
    timings: String?,
    speaking: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(LocalGradients.current.brand)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.result_title, langName(target)),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            SelectionContainer {
                Text(translation, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPlay,
                    enabled = enabled,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_volume_up),
                        contentDescription = stringResource(R.string.action_play_translation),
                        tint = Color.White,
                    )
                }
                if (speaking) {
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.action_stop_speaking), color = Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
                timings?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
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
