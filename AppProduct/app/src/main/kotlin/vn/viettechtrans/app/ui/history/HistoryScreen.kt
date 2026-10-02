package vn.viettechtrans.app.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold

/** Placeholder until M7 (text history, same ID for source and translation, view + replay only). */
@Composable
fun HistoryScreen(onHelp: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.nav_history), onHelp = onHelp) {
        NoticeCard(stringResource(R.string.history_coming))
    }
}
