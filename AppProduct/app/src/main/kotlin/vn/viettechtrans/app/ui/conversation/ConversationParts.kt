package vn.viettechtrans.app.ui.conversation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.turn.MessageStatus
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.ui.common.LangBadge
import vn.viettechtrans.app.ui.common.TalkButton
import vn.viettechtrans.app.ui.common.cardColor
import vn.viettechtrans.app.ui.common.onCardControlColor
import vn.viettechtrans.app.ui.theme.LocalGradients

/** UI strings of one person's controls, in that person's language (B sees English). */
data class PartyStrings(val button: String, val speak: String, val listening: String, val waiting: String, val processing: String)

@Composable
fun partyStrings(party: Party): PartyStrings = if (party == Party.A) {
    PartyStrings(
        stringResource(R.string.party_vi_button), stringResource(R.string.party_vi_speak),
        stringResource(R.string.party_vi_listening), stringResource(R.string.party_vi_waiting),
        stringResource(R.string.party_vi_processing),
    )
} else {
    PartyStrings(
        stringResource(R.string.party_en_button), stringResource(R.string.party_en_speak),
        stringResource(R.string.party_en_listening), stringResource(R.string.party_en_waiting),
        stringResource(R.string.party_en_processing),
    )
}

/** A (Vietnamese) is indigo→violet, B (English) is teal→cyan everywhere in the conversation. */
@Composable
fun partyBrush(party: Party): Brush = if (party == Party.A) LocalGradients.current.partyA else LocalGradients.current.partyB

fun langCode(party: Party): String = if (party == Party.A) "VI" else "EN"

@Composable
private fun bubbleColor(party: Party): Color =
    if (party == Party.A) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

@Composable
private fun onBubbleColor(party: Party): Color =
    if (party == Party.A) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

/** Chat bubbles: the corner nearest the speaker is sharp, like a speech tail. */
private fun bubbleShape(party: Party) = if (party == Party.A) {
    RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
} else {
    RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
}

/** Language mic button of one person, with its name and current status under it. */
@Composable
fun PartyMicButton(party: Party, ui: ConversationUi, onTap: () -> Unit, modifier: Modifier = Modifier, size: Dp = 68.dp) {
    val s = partyStrings(party)
    val listening = ui.listening == party
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TalkButton(
            listening = listening,
            enabled = true,
            level = if (listening) ui.level else 0f,
            contentDescription = s.speak,
            onClick = onTap,
            size = size,
            brush = partyBrush(party),
        )
        Text(s.button, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val status = when {
            listening -> s.listening
            ui.engine.pending == party -> s.waiting
            ui.processing == party -> s.processing
            else -> null
        }
        // Always reserve the line so the buttons do not jump when a status appears.
        Text(
            status ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** Round tonal keyboard button between the two mics. */
@Composable
fun KeyboardButton(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = onCardControlColor()),
    ) {
        Icon(painterResource(R.drawable.ic_keyboard), contentDescription = stringResource(R.string.conv_type))
    }
}

@Composable
fun statusLabel(m: ChatMessage): String? = when {
    m.interrupted -> stringResource(R.string.conv_status_interrupted)
    m.status == MessageStatus.TRANSLATING -> stringResource(R.string.conv_status_translating)
    m.status == MessageStatus.NOT_PLAYED -> stringResource(R.string.conv_status_not_played)
    m.status == MessageStatus.NO_TRANSLATOR -> stringResource(R.string.conv_status_no_mt)
    m.status == MessageStatus.FAILED -> stringResource(R.string.conv_status_failed)
    else -> null
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** One chat bubble: speaker's language badge, original (small), translation (large), replay. */
@Composable
fun MessageBubble(m: ChatMessage, onReplay: () -> Unit, modifier: Modifier = Modifier) {
    val left = m.party == Party.A
    val on = onBubbleColor(m.party)
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (left) Alignment.TopStart else Alignment.TopEnd,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f).wrapToContent(left),
            shape = bubbleShape(m.party),
            color = bubbleColor(m.party),
            contentColor = on,
        ) {
            Column(modifier = Modifier.widthIn(min = 160.dp, max = 520.dp).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LangBadge(langCode(m.party), partyBrush(m.party))
                    Text(partyStrings(m.party).button, style = MaterialTheme.typography.labelMedium, color = on.copy(alpha = 0.7f))
                    statusLabel(m)?.let {
                        StatusPill(it, if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else on)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    m.source,
                    style = MaterialTheme.typography.bodyMedium,
                    color = on.copy(alpha = if (m.translation != null) 0.72f else 1f),
                    modifier = Modifier.padding(end = 8.dp),
                )
                m.translation?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.timings.totalMs != null && m.translation != null) {
                        Text(m.timings.format(), style = MaterialTheme.typography.labelSmall, color = on.copy(alpha = 0.55f))
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalIconButton(
                        onClick = onReplay,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cardColor().copy(alpha = 0.7f)),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_volume_up),
                            contentDescription = stringResource(R.string.conv_replay),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Bubble shown while someone is talking: live partial text and three bouncing dots. */
@Composable
fun ListeningBubble(party: Party, partial: String, modifier: Modifier = Modifier) {
    val left = party == Party.A
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = if (left) Alignment.TopStart else Alignment.TopEnd) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .wrapToContent(left)
                .border(1.5.dp, partyBrush(party), bubbleShape(party)),
            shape = bubbleShape(party),
            color = cardColor(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LangBadge(langCode(party), partyBrush(party))
                    TypingDots()
                }
                if (partial.isNotBlank()) {
                    Text(
                        partial,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                label = "d$i",
            )
            Box(Modifier.size(7.dp).alpha(a).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/** Lets a bubble shrink to its text while keeping the 86% max width and its side. */
private fun Modifier.wrapToContent(left: Boolean): Modifier =
    this.wrapContentWidth(if (left) Alignment.Start else Alignment.End)
