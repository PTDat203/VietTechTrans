package vn.viettechtrans.app.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold
import vn.viettechtrans.app.ui.common.VttCard

/** Manual sections planned for M8 (offline Markdown rendered in the app). */
private val SECTIONS = listOf(
    "Bắt đầu",
    "Chọn chiều dịch",
    "Nói – gõ – nghe",
    "Hội thoại hai người",
    "Chen lời (Cắt / Chờ) và nói chồng",
    "Mẹo thu âm: ồn, nói lẫn Anh–Việt, ấp úng",
    "Lịch sử",
    "Thực nghiệm",
    "Lỗi thường gặp",
    "Offline và quyền riêng tư",
    "Giới hạn",
    "Giấy phép",
)

@Composable
fun ManualScreen(section: String?, onBack: () -> Unit) {
    ScreenScaffold(
        title = stringResource(R.string.nav_manual),
        subtitle = section?.let { "(mở từ màn: $it)" },
        onHelp = null,
        onBack = onBack,
    ) {
        NoticeCard(stringResource(R.string.manual_coming), Modifier.fillMaxWidth())
        VttCard(modifier = Modifier.fillMaxWidth()) {
            SECTIONS.forEachIndexed { index, title ->
                Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
