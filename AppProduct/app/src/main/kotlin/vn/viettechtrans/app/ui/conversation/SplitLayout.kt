package vn.viettechtrans.app.ui.conversation

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.ui.common.GradientButton
import vn.viettechtrans.app.ui.common.LangBadge
import vn.viettechtrans.app.ui.theme.LocalGradients

/**
 * Face-to-face layout (Papago / Microsoft Translator / Samsung Interpreter style):
 * B's half (English) on top, rotated 180° for the person opposite; A's half below.
 * Each half shows, large, the translation of what the OTHER person just said.
 * Landscape: halves side by side, not rotated.
 */
@Composable
fun SplitLayout(
    ui: ConversationUi,
    onTap: (Party) -> Unit,
    onKeyboard: () -> Unit,
    onStopSpeaking: () -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val center: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            header()
            if (ui.speaking) {
                GradientButton(
                    text = stringResource(R.string.action_stop_speaking),
                    onClick = onStopSpeaking,
                    icon = R.drawable.ic_stop_circle,
                    brush = LocalGradients.current.listening,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
    if (landscape) {
        Column(modifier = modifier.fillMaxSize()) {
            center()
            Row(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Half(Party.A, ui, onTap, onKeyboard, Modifier.weight(1f))
                Half(Party.B, ui, onTap, onKeyboard, Modifier.weight(1f))
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Half(
                Party.B, ui, onTap, onKeyboard,
                Modifier.weight(1f).graphicsLayer { rotationZ = if (ui.rotateTop) 180f else 0f },
            )
            center()
            Half(Party.A, ui, onTap, onKeyboard, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Half(party: Party, ui: ConversationUi, onTap: (Party) -> Unit, onKeyboard: () -> Unit, modifier: Modifier) {
    // What this person should read: the latest message from the other person, translated into their language.
    val fromOther = ui.messages.lastOrNull { it.party != party }
    val mine = ui.messages.lastOrNull { it.party == party }
    val tint = if (party == Party.A) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val onTint = if (party == Party.A) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = modifier.fillMaxSize().padding(4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = tint.copy(alpha = 0.55f),
        contentColor = onTint,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LangBadge(langCode(party), partyBrush(party))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (fromOther != null) {
                    Text(
                        fromOther.translation ?: fromOther.source,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    if (fromOther.translation != null) {
                        Text(
                            fromOther.source,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onTint.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (ui.listening == party && ui.partial.isNotBlank()) {
                    Text(ui.partial, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
                } else if (mine != null) {
                    Text(
                        "“${mine.source}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = onTint.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PartyMicButton(party, ui, onTap = { onTap(party) }, size = 64.dp)
                KeyboardButton(onKeyboard)
            }
        }
    }
}
