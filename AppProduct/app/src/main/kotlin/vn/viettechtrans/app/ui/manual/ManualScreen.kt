package vn.viettechtrans.app.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.common.VttCard
import vn.viettechtrans.app.ui.common.VttHeader
import vn.viettechtrans.app.ui.common.cardColor

/** The user manual, read offline from assets/manual/huong_dan_vi.md (the same file the report quotes). */
@Composable
fun ManualScreen(section: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks by produceState(emptyList<ManualBlock>()) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(ASSET).bufferedReader().use { ManualMarkdown.parse(it.readText()) }
        }
    }
    val list = rememberLazyListState()
    val target = SECTION_OF_SCREEN[section] ?: section
    LaunchedEffect(blocks, target) {
        val i = blocks.indexOfFirst { it is ManualBlock.Heading && it.id == target }
        if (i > 0) list.scrollToItem(i)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        VttHeader(title = stringResource(R.string.nav_manual), onBack = onBack, backDescription = stringResource(R.string.cd_back))
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(blocks) { _, b -> Block(b) }
        }
    }
}

@Composable
private fun Block(b: ManualBlock) {
    when (b) {
        is ManualBlock.Heading -> Text(
            inline(b.text),
            style = if (b.level == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            color = if (b.level == 1) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (b.level == 1) 0.dp else 16.dp),
        )
        is ManualBlock.Paragraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
        is ManualBlock.Item -> Row {
            Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
            Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        is ManualBlock.Table -> VttCard(modifier = Modifier.fillMaxWidth()) {
            b.rows.forEachIndexed { r, cells ->
                if (r > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (r == 0) Modifier.background(cardColor()) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cells.forEach { cell ->
                        Text(
                            inline(cell),
                            style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** **bold** spans; everything else is plain text. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    text.split("**").forEach { part ->
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        bold = !bold
    }
}

private const val ASSET = "manual/huong_dan_vi.md"

/** "?" of a screen opens the manual at that screen's section. */
private val SECTION_OF_SCREEN = mapOf("conversation" to "conversation", "translate" to "translate", "settings" to "settings")

sealed interface ManualBlock {
    data class Heading(val level: Int, val text: String, val id: String?) : ManualBlock
    data class Paragraph(val text: String) : ManualBlock
    data class Item(val marker: String, val text: String) : ManualBlock
    data class Table(val rows: List<List<String>>) : ManualBlock
}

/** The small Markdown subset the manual uses: #/## headings with {#id}, paragraphs, - and 1. items, tables, **bold**. */
object ManualMarkdown {
    private val HEADING = Regex("""^(#{1,2})\s+(.*?)(?:\s+\{#([\w-]+)\})?\s*$""")
    private val NUMBERED = Regex("""^(\d+)\.\s+(.*)$""")
    private val TABLE_RULE = Regex("""^\|?\s*:?-{3,}.*$""")

    fun parse(text: String): List<ManualBlock> {
        val out = mutableListOf<ManualBlock>()
        val paragraph = StringBuilder()
        var table: MutableList<List<String>>? = null
        fun flush() {
            if (paragraph.isNotBlank()) out += ManualBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
            table?.let { if (it.isNotEmpty()) out += ManualBlock.Table(it) }
            table = null
        }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.startsWith("|") -> {
                    if (paragraph.isNotBlank()) flush()
                    if (!TABLE_RULE.matches(line)) {
                        val rows = table ?: mutableListOf<List<String>>().also { table = it }
                        rows += line.trim().trim('|').split('|').map { it.trim() }
                    }
                }
                HEADING.matches(line) -> {
                    flush()
                    val m = HEADING.matchEntire(line)!!
                    out += ManualBlock.Heading(m.groupValues[1].length, m.groupValues[2], m.groupValues[3].ifEmpty { null })
                }
                line.startsWith("- ") -> {
                    flush()
                    out += ManualBlock.Item("•", line.removePrefix("- ").trim())
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.matchEntire(line)!!
                    out += ManualBlock.Item("${m.groupValues[1]}.", m.groupValues[2])
                }
                else -> paragraph.append(if (paragraph.isEmpty()) line.trim() else " " + line.trim())
            }
        }
        flush()
        return out
    }
}
