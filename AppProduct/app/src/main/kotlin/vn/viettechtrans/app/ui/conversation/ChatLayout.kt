package vn.viettechtrans.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.ui.common.EmptyState
import vn.viettechtrans.app.ui.common.cardColor

/** Chat layout (Google Translate / Apple Translate style): phone between the two people. */
@Composable
fun ChatLayout(
    ui: ConversationUi,
    onTap: (Party) -> Unit,
    onKeyboard: () -> Unit,
    onReplay: (ChatMessage) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val extra = if (ui.listening != null) 1 else 0
    LaunchedEffect(ui.messages.size, extra, ui.partial.length / 20) {
        val last = ui.messages.size + extra - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    Column(modifier = modifier.fillMaxSize()) {
        header()
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.messages.isEmpty() && ui.listening == null) {
                item(key = "empty") {
                    EmptyState(
                        icon = R.drawable.ic_forum,
                        title = stringResource(R.string.conv_empty_title),
                        text = stringResource(R.string.conv_empty),
                    )
                }
            }
            items(ui.messages, key = { it.turn }) { m ->
                MessageBubble(m, onReplay = { onReplay(m) }, modifier = Modifier.animateItem())
            }
            ui.listening?.let { party ->
                item(key = "partial") { ListeningBubble(party, ui.partial, Modifier.animateItem()) }
            }
        }
        // Floating control dock: one mic per language with the keyboard between them.
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = cardColor(),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PartyMicButton(Party.A, ui, onTap = { onTap(Party.A) })
                KeyboardButton(onKeyboard)
                PartyMicButton(Party.B, ui, onTap = { onTap(Party.B) })
            }
        }
    }
}
