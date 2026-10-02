package vn.viettechtrans.app.ui.manual

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.NoticeCard
import vn.viettechtrans.app.ui.common.ScreenScaffold

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
    ScreenScaffold(title = stringResource(R.string.nav_manual), onHelp = null, onBack = onBack) {
        NoticeCard(stringResource(R.string.manual_coming))
        SECTIONS.forEachIndexed { index, title ->
            Text("${index + 1}. $title", style = MaterialTheme.typography.bodyLarge)
        }
        if (section != null) {
            Text("(mở từ màn: $section)", style = MaterialTheme.typography.bodySmall)
        }
    }
}
