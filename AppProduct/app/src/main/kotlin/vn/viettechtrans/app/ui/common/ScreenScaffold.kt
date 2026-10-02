package vn.viettechtrans.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R

/**
 * Common screen frame: large gradient header with a "?" button (opens the manual
 * section) and scrollable content capped at 720dp wide so large screens stay readable.
 * The floating navigation bar below handles the system navigation insets.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onHelp: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        VttHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            backDescription = stringResource(R.string.cd_back),
        ) {
            if (onHelp != null) HeaderAction(R.drawable.ic_help, stringResource(R.string.cd_help), onHelp)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}
