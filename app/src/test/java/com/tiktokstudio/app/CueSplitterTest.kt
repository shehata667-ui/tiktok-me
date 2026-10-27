package com.tiktokstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSplitterTest {
    @Test fun splitsLongTextIntoShortContiguousCues() {
        val text = (1..12).joinToString(" ") { "كلمة$it" }
        val cues = CueSplitter.split(1000, 7000, text, maxWords = 5)
        assertEquals(3, cues.size)
        assertTrue(cues.all { it.text.split(" ").size <= 5 })
        assertEquals(1000L, cues.first().startMs)
        assertEquals(7000L, cues.last().endMs)
        cues.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test fun blankTextGivesNoCues() = assertEquals(0, CueSplitter.split(0, 1000, "   ").size)
}
