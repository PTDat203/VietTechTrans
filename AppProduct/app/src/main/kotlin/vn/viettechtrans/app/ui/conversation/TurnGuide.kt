package vn.viettechtrans.app.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.turn.ConvPhase
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.ui.common.cardColor

/** The three steps of one turn, shown as chips while a turn runs. */
enum class GuideStep { LISTEN, TRANSLATE, SPEAK }

/** What the conversation is waiting for. */
enum class GuideKind { START, LISTENING, PROCESSING, SPEAKING, REPLAY, NEXT }

/**
 * Where the conversation is and who should act ([party]): the speaker for LISTENING/PROCESSING/SPEAKING,
 * the person expected to answer for NEXT (the other one of the last message), nobody for START and REPLAY.
 */
data class Guide(val kind: GuideKind, val party: Party?, val step: GuideStep?)

fun guideOf(phase: ConvPhase, messages: List<ChatMessage>): Guide = when (phase) {
    is ConvPhase.Listening -> Guide(GuideKind.LISTENING, phase.party, GuideStep.LISTEN)
    is ConvPhase.Processing -> Guide(GuideKind.PROCESSING, phase.party, GuideStep.TRANSLATE)
    is ConvPhase.Speaking -> phase.party?.let { Guide(GuideKind.SPEAKING, it, GuideStep.SPEAK) } ?: Guide(GuideKind.REPLAY, null, GuideStep.SPEAK)
    ConvPhase.Idle -> messages.maxByOrNull { it.turn }?.let { Guide(GuideKind.NEXT, it.party.other, null) } ?: Guide(GuideKind.START, null, null)
}

/**
 * Two lines for a guide: the first in the language of the person who must act now, the second for the other one.
 * Party A reads Vietnamese, party B English (as the labels of their buttons).
 */
@Composable
private fun guideLines(g: Guide): Pair<String, String> = when (g.kind) {
    GuideKind.START -> stringResource(R.string.guide_start) to stringResource(R.string.guide_start_en)
    GuideKind.REPLAY -> stringResource(R.string.guide_replay) to stringResource(R.string.guide_replay_en)
    GuideKind.LISTENING -> if (g.party == Party.A) {
        stringResource(R.string.guide_listening_vi) to stringResource(R.string.guide_wait_en)
    } else {
        stringResource(R.string.guide_listening_en) to stringResource(R.string.guide_wait_vi)
    }
    GuideKind.PROCESSING -> if (g.party == Party.A) {
        stringResource(R.string.guide_processing_vi) to stringResource(R.string.guide_processing_other_en)
    } else {
        stringResource(R.string.guide_processing_en) to stringResource(R.string.guide_processing_other_vi)
    }
    // The translation is read to the other person: the first line is theirs.
    GuideKind.SPEAKING -> if (g.party == Party.A) {
        stringResource(R.string.guide_speaking_to_en) to stringResource(R.string.guide_speaking_vi)
    } else {
        stringResource(R.string.guide_speaking_to_vi) to stringResource(R.string.guide_speaking_en)
    }
    GuideKind.NEXT -> if (g.party == Party.A) {
        stringResource(R.string.guide_next_vi) to stringResource(R.string.guide_next_vi_other_en)
    } else {
        stringResource(R.string.guide_next_en) to stringResource(R.string.guide_next_en_other_vi)
    }
}

/** The guide above the mic buttons: step chips while a turn runs, then what to do now. */
@Composable
fun TurnGuideBar(guide: Guide, modifier: Modifier = Modifier) {
    val (first, second) = guideLines(guide)
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cardColor(), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            guide.step?.let { active ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepChip(R.drawable.ic_mic, stringResource(R.string.guide_step_listen), GuideStep.LISTEN, active)
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StepChip(R.drawable.ic_translate, stringResource(R.string.guide_step_translate), GuideStep.TRANSLATE, active)
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StepChip(R.drawable.ic_volume_up, stringResource(R.string.guide_step_speak), GuideStep.SPEAK, active)
                }
            }
            Text(first, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepChip(icon: Int, label: String, step: GuideStep, active: GuideStep) {
    val on = step == active
    val done = step.ordinal < active.ordinal
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .alpha(if (on || done) 1f else 0.55f)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
            .border(1.dp, color.copy(alpha = if (on) 0f else 0.4f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

/** The conversation rules, numbered; each with an English line for the English speaker. */
private val RULES = listOf(
    R.string.rule_1 to R.string.rule_1_en,
    R.string.rule_2 to R.string.rule_2_en,
    R.string.rule_3 to R.string.rule_3_en,
    R.string.rule_4 to R.string.rule_4_en,
    R.string.rule_5 to R.string.rule_5_en,
    R.string.rule_6 to R.string.rule_6_en,
)

@Composable
fun ConversationRulesList(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RULES.forEachIndexed { i, (vi, en) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Column {
                    Text(stringResource(vi), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(en), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The rules in the empty conversation, in place of a blank screen. */
@Composable
fun ConversationRulesCard(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cardColor(), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ConversationRulesList()
        }
    }
}

/** "ⓘ" in the conversation header: the same rules at any time. */
@Composable
fun ConversationRulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_title)) },
        text = { ConversationRulesList(Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
