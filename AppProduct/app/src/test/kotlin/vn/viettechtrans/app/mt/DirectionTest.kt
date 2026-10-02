package vn.viettechtrans.app.mt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectionTest {
    @Test
    fun idsMatchCore() {
        // Core src/core_mt/contracts.py: EN_TO_VI = "en_to_vi", VI_TO_EN = "vi_to_en"
        assertEquals("en_to_vi", Direction.EN_TO_VI.id)
        assertEquals("vi_to_en", Direction.VI_TO_EN.id)
        assertEquals(Lang.EN, Direction.EN_TO_VI.source)
        assertEquals(Lang.VI, Direction.EN_TO_VI.target)
    }

    @Test
    fun lookupAndReverse() {
        for (d in Direction.entries) {
            assertEquals(d, Direction.fromId(d.id))
            assertEquals(d, Direction.fromSource(d.source))
            assertEquals(d, d.reverse.reverse)
        }
        assertThrows(IllegalArgumentException::class.java) { Direction.fromId("vi-en") }
    }
}
