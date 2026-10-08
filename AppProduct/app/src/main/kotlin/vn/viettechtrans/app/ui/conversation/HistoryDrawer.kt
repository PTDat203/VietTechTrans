package vn.viettechtrans.app.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import vn.viettechtrans.app.R
import vn.viettechtrans.app.history.ConversationSummary
import vn.viettechtrans.app.ui.common.GradientButton

/** Chat-app style side panel: new conversation, saved conversations by date, then the other screens. */
@Composable
fun HistoryDrawer(
    history: List<ConversationSummary>,
    currentId: String?,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
    onTranslate: () -> Unit,
    onManual: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
            GradientButton(
                text = stringResource(R.string.conv_new),
                onClick = onNew,
                icon = R.drawable.ic_add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (history.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            val today = LocalDate.now()
            history.groupBy { groupOf(it.updatedAtMs, today) }.toSortedMap().forEach { (group, items) ->
                item(key = "g$group") {
                    Text(
                        stringResource(GROUP_LABELS[group]),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.id }) { c ->
                    HistoryItem(c, selected = c.id == currentId, onOpen = { onOpen(c.id) }, onRename = { onRename(c) }, onDelete = { onDelete(c) })
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            DrawerLink(R.drawable.ic_translate, stringResource(R.string.nav_translate), onTranslate)
            DrawerLink(R.drawable.ic_help, stringResource(R.string.nav_manual), onManual)
            DrawerLink(R.drawable.ic_settings, stringResource(R.string.nav_settings), onSettings)
        }
    }
}

@Composable
private fun HistoryItem(c: ConversationSummary, selected: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.history_item_meta, c.messageCount, TIME.format(Instant.ofEpochMilli(c.updatedAtMs))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.history_item_menu, c.title))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_rename)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun DrawerLink(icon: Int, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM").withZone(ZoneId.systemDefault())

private val GROUP_LABELS = listOf(
    R.string.history_today, R.string.history_yesterday, R.string.history_week, R.string.history_month, R.string.history_older,
)

/** 0 today, 1 yesterday, 2 within 7 days, 3 within 30 days, 4 older. */
private fun groupOf(ms: Long, today: LocalDate): Int {
    val day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> 0
        days == 1L -> 1
        days < 7 -> 2
        days < 30 -> 3
        else -> 4
    }
}
