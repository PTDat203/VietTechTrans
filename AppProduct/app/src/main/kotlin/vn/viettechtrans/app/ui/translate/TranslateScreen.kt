package vn.viettechtrans.app.ui.translate

import android.Manifest
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.R
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.rules.RuleNote
import vn.viettechtrans.app.mt.rules.TextRules
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.ui.common.GradientButton
import vn.viettechtrans.app.ui.common.ImageActions
import vn.viettechtrans.app.ui.common.InputCounter
import vn.viettechtrans.app.ui.common.MismatchDialog
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold
import vn.viettechtrans.app.ui.common.ShowImageDialog
import vn.viettechtrans.app.ui.common.ShowTextDialog
import vn.viettechtrans.app.ui.common.TalkButton
import vn.viettechtrans.app.ui.common.TextActions
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
fun TranslateScreen(container: AppContainer, onBack: () -> Unit, onHelp: () -> Unit) {
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.receiveIncoming() } }

    // Dịch ảnh (DR-10): a picture from the gallery (Android photo picker, no permission) or a new photo
    // taken by the camera app into the app cache (FileProvider, no camera permission).
    val context = LocalContext.current
    val noCameraText = stringResource(R.string.image_no_camera)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::onImage) }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) photoUri?.let(vm::onImage)
    }
    val onGallery = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val onCamera = {
        val uri = container.imageText.newPhotoUri()
        photoUri = uri
        try {
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            vm.showNotice(noCameraText)
        }
    }
    // Launcher shortcut "Dịch ảnh".
    LaunchedEffect(s.pickImageRequest) {
        if (s.pickImageRequest) {
            vm.pickImageHandled()
            onGallery()
        }
    }
    var showing by rememberSaveable { mutableStateOf<String?>(null) }
    var showingPicture by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sharePicture = { s.overlay?.let { picture -> scope.launch { ImageActions.share(context, picture) } } }

    ScreenScaffold(
        title = stringResource(R.string.nav_translate),
        subtitle = stringResource(R.string.translate_subtitle),
        onHelp = onHelp,
        onBack = onBack,
    ) {
        LanguageBar(
            source = s.direction.source,
            target = s.direction.target,
            enabled = !s.busy && s.phase != Phase.LISTENING,
            onSwap = vm::swap,
        )

        InputCard(
            s,
            onInput = vm::onInput,
            onTranslate = { vm.translateInput() },
            onPlaySource = vm::playSource,
            onPaste = { TextActions.paste(context)?.let(vm::onInput) },
            onClearImage = vm::clearImage,
        )

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Three ways to give the source: camera, voice (centre, the main one), picture from the gallery.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ImageSourceButton(R.drawable.ic_photo_camera, stringResource(R.string.image_camera), enabled = !s.busy, onClick = onCamera)
                TalkButton(
                    listening = s.phase == Phase.LISTENING || s.phase == Phase.LOADING,
                    enabled = !s.busy,
                    level = s.level,
                    contentDescription = stringResource(R.string.translate_speak),
                    onClick = onMic,
                    size = 80.dp,
                )
                ImageSourceButton(R.drawable.ic_image, stringResource(R.string.image_gallery), enabled = !s.busy, onClick = onGallery)
            }
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

        // Dịch trên ảnh: the translation drawn over the picture, block by block.
        s.overlay?.let { overlay ->
            PictureResultCard(overlay, original = s.imagePage, onOpen = { showingPicture = true }, onShare = { sharePicture() })
        }
        if (s.overlayUnmatched && s.translation != null) {
            Text(
                stringResource(R.string.image_overlay_unmatched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = s.translation != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            ResultCard(
                target = s.direction.target,
                translation = s.translation.orEmpty(),
                // R13: typed text shows only "MT … ms" (nothing when the model was not called).
                timings = if (s.typedResult) s.timings?.formatTextOnly() else s.timings?.format(),
                notes = s.notes,
                checkTerms = s.checkTerms,
                speaking = s.phase == Phase.SPEAKING,
                enabled = !s.busy,
                onPlay = vm::playTranslation,
                onStop = vm::stopSpeaking,
                onCopy = { TextActions.copy(context, s.translation.orEmpty()) },
                onShare = { TextActions.share(context, s.translation.orEmpty()) },
                onShow = { showing = s.translation },
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
    showing?.let { ShowTextDialog(it, onDismiss = { showing = null }) }
    val overlay = s.overlay
    if (showingPicture && overlay != null) {
        ShowImageDialog(overlay, original = s.imagePage, onShare = { sharePicture() }, onDismiss = { showingPicture = false })
    }
    if (s.mismatch != null) {
        MismatchDialog(
            chosen = s.direction.source,
            onSwitch = { vm.answerMismatch(switchLanguage = true) },
            onForce = { vm.answerMismatch(switchLanguage = false) },
            onDismiss = vm::dismissMismatch,
        )
    }
}

/** Dịch trên ảnh: the translated picture, with the original one beside it (chips), full screen and share. */
@Composable
private fun PictureResultCard(overlay: Bitmap, original: Bitmap?, onOpen: () -> Unit, onShare: () -> Unit) {
    var showOriginal by remember(overlay) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cardColor(), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.image_overlay_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = if (showOriginal && original != null) original else overlay
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(if (shown === overlay) R.string.image_overlay_title else R.string.image_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onOpen),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (original != null) {
                    FilterChip(selected = !showOriginal, onClick = { showOriginal = false }, label = { Text(stringResource(R.string.image_overlay_translated)) })
                    FilterChip(selected = showOriginal, onClick = { showOriginal = true }, label = { Text(stringResource(R.string.image_overlay_original)) })
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpen) {
                    Icon(painterResource(R.drawable.ic_open_in_full), contentDescription = stringResource(R.string.image_overlay_open))
                }
                IconButton(onClick = onShare) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.image_overlay_share))
                }
            }
        }
    }
}

/** A round tonal button with its label under it (camera or gallery next to the mic). */
@Composable
private fun ImageSourceButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(painterResource(icon), contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun InputCard(
    s: TranslateUiState,
    onInput: (String) -> Unit,
    onTranslate: () -> Unit,
    onPlaySource: () -> Unit,
    onPaste: () -> Unit,
    onClearImage: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cardColor(), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            s.image?.let { bitmap ->
                // Dịch ảnh: the picture the text in the box was read from, to compare with it.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.image_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = 120.dp).weight(1f, fill = false).clip(MaterialTheme.shapes.medium),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (s.phase == Phase.READING_IMAGE) {
                                stringResource(R.string.phase_reading_image)
                            } else {
                                stringResource(R.string.image_read_info, s.imageLines, s.imageMs ?: 0L)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.phase != Phase.READING_IMAGE) {
                            Text(
                                stringResource(R.string.image_check_text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onClearImage, enabled = !s.busy) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.image_remove))
                    }
                }
            }
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
            val tooLong = TextRules.typedLength(s.input) > TextRules.MAX_CHARS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { InputCounter(s.input, s.emptyTried) }
                IconButton(onClick = onPaste, enabled = !s.busy) {
                    Icon(painterResource(R.drawable.ic_content_paste), contentDescription = stringResource(R.string.action_paste))
                }
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
                // R02: an empty box shows "Hãy nhập nội dung cần dịch." when tapped; more than 500 characters locks it.
                GradientButton(
                    text = stringResource(R.string.action_translate),
                    onClick = onTranslate,
                    enabled = !s.busy && !tooLong,
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
    notes: List<RuleNote>,
    checkTerms: List<String>,
    speaking: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShow: () -> Unit,
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
            for (n in notes) {
                val info = n == RuleNote.KEPT_LITERAL || n == RuleNote.REPEATS_COLLAPSED
                val text = if (n == RuleNote.CHECK_TERMS && checkTerms.isNotEmpty()) stringResource(R.string.rule_terms, n.text, checkTerms.joinToString(", ")) else n.text
                Text((if (info) "ⓘ " else "⚠ ") + text, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = if (info) 0.8f else 1f))
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
                } else {
                    ResultAction(R.drawable.ic_copy, stringResource(R.string.action_copy), onCopy)
                    ResultAction(R.drawable.ic_share, stringResource(R.string.action_share), onShare)
                    ResultAction(R.drawable.ic_open_in_full, stringResource(R.string.action_show), onShow)
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
private fun ResultAction(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White)
    }
}

@Composable
private fun statusText(s: TranslateUiState): String = when (s.phase) {
    Phase.IDLE -> if (s.loading.isEmpty()) {
        stringResource(R.string.phase_idle_translate)
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
    Phase.READING_IMAGE -> stringResource(R.string.phase_reading_image)
    Phase.TRANSLATING -> stringResource(R.string.phase_translating)
    Phase.SPEAKING -> stringResource(R.string.phase_speaking)
}
