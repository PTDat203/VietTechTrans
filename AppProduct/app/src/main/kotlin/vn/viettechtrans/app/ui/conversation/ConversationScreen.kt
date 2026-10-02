package vn.viettechtrans.app.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold

/** Placeholder until M6 (two-person conversation, CUT/QUEUE interruption policy). */
@Composable
fun ConversationScreen(onHelp: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.nav_conversation), onHelp = onHelp) {
        NoticeCard(stringResource(R.string.conversation_coming))
    }
}
