package vn.viettechtrans.app.ui.experiments

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.EmptyState
import vn.viettechtrans.app.ui.common.ScreenScaffold

/** Placeholder until M3–M8 ("TN" = Thực nghiệm in the sketches: experiments and measurements). */
@Composable
fun ExperimentsScreen(onHelp: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.nav_experiments), onHelp = onHelp) {
        EmptyState(icon = R.drawable.ic_science, text = stringResource(R.string.experiments_coming))
    }
}
