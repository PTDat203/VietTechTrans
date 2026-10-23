package vn.viettechtrans.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Lang

class SttPostProcessorTest {
    @Test
    fun vietnameseUppercaseBecomesSentenceCase() {
        val r = SttPostProcessor.process("TÔI MUỐN CÀI ĐẶT GÓI NÀY", Lang.VI, SttPostProcessor.STYLE_UPPER_NO_PUNCT)
        assertEquals("Tôi muốn cài đặt gói này", r.text)
        assertTrue(r.meaningful)
    }

    @Test
    fun fillersAndStutterAreRemoved() {
        assertEquals("Tôi muốn hỏi", SttPostProcessor.process("ờ tôi tôi tôi muốn ừm hỏi", Lang.VI, "cased_punct").text)
        assertEquals("Tôi muốn cài", SttPostProcessor.process("tôi muốn tôi muốn cài", Lang.VI, "cased_punct").text)
        assertEquals("I want to, ask", SttPostProcessor.process("uh I want to, um ask", Lang.EN, "cased_punct").text)
    }

    @Test
    fun legitimateRepeatsAndAnswersAreKept() {
        assertEquals("Đi đi", SttPostProcessor.process("đi đi", Lang.VI, "cased_punct").text)
        assertEquals("Từ từ thôi", SttPostProcessor.process("từ từ thôi", Lang.VI, "cased_punct").text)
        assertEquals("Ừ", SttPostProcessor.process("ừ", Lang.VI, "cased_punct").text)
    }

    @Test
    fun cleanupCanBeDisabled() {
        assertEquals("Ờ tôi tôi tôi", SttPostProcessor.process("ờ tôi tôi tôi", Lang.VI, "cased_punct", cleanup = false).text)
    }

    @Test
    fun fillerOnlyIsNotMeaningful() {
        assertFalse(SttPostProcessor.process("ờ ừm", Lang.VI, SttPostProcessor.STYLE_UPPER_NO_PUNCT).meaningful)
        assertFalse(SttPostProcessor.process("  ", Lang.EN, "cased_punct").meaningful)
        assertFalse(SttPostProcessor.process("...", Lang.EN, "cased_punct").meaningful)
    }
}

class TtsTextNormalizerTest {
    private val n = TtsTextNormalizer(
        TtsTextNormalizer.parseLexicon("# c\nAPI\tây pi ai\ndocker\tđốc cơ\nbug\tbấc\n\nbad-line-without-tab\n"),
    )

    @Test
    fun lexiconParsingSkipsCommentsAndBadLines() {
        assertEquals(setOf("api", "docker", "bug"), TtsTextNormalizer.parseLexicon("# x\nAPI\ta\ndocker\tb\nbug\tc\nbad\n").keys)
    }

    @Test
    fun vietnameseUsesLexiconAndSpellsAcronyms() {
        assertEquals("Gọi ây pi ai của đốc cơ", n.normalize("Gọi API của Docker", Lang.VI))
        assertEquals("Lỗi bấc ở ét ét đi", n.normalize("Lỗi bug ở SSD", Lang.VI))
        assertEquals("Python 3 chấm 12", n.normalize("Python 3.12", Lang.VI))
        assertEquals("src main kotlin", n.normalize("src/main_kotlin", Lang.VI))
        // Latin letters inside a Vietnamese word are not acronyms.
        assertEquals("VIỆT", n.normalize("VIỆT", Lang.VI))
    }

    @Test
    fun lexiconCanBeDisabled() {
        assertEquals("Gọi API", n.normalize("Gọi API", Lang.VI, useLexicon = false))
    }

    @Test
    fun englishSplitsIdentifiersAndVersions() {
        assertEquals("Update to version 3 point 12", n.normalize("Update to version 3.12", Lang.EN))
        assertEquals("call get User Name now", n.normalize("call getUserName now", Lang.EN))
    }

    @Test
    fun chunksSplitSentencesAndLongRuns() {
        assertEquals(listOf("Một.", "Hai!", "Ba"), n.chunks("Một. Hai! Ba"))
        val long = (1..60).joinToString(" ") { "w$it" }
        val chunks = n.chunks(long)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.split(' ').size <= TtsTextNormalizer.MAX_CHUNK_WORDS })
    }
}
