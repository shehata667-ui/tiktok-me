package com.tiktokstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueBuilderTest {
    private fun words(n: Int, stepMs: Long = 400, from: Long = 0) =
        (0 until n).map { Word(from + it * stepMs, from + it * stepMs + 350, "ك$it") }

    @Test fun usesRealWordTimingsAndChunksByFive() {
        val cues = CueBuilder.build(listOf(Seg(0, 3000, "x")), words(7))
        assertEquals(2, cues.size)
        assertEquals(5, cues[0].words.size)
        assertEquals(2, cues[1].words.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals("ك0 ك1 ك2 ك3 ك4", cues[0].text)
    }

    @Test fun neverUsesAWordTwiceAcrossSegments() {
        val w = words(6)
        val cues = CueBuilder.build(listOf(Seg(0, 1200, "a"), Seg(1200, 2800, "b")), w)
        assertEquals(6, cues.sumOf { it.words.size })
    }

    @Test fun closesSmallGapsButNotLongOnes() {
        val ws = words(1) + words(1, from = 3000)
        val cues = CueBuilder.build(listOf(Seg(0, 500, "a"), Seg(3000, 3500, "b")), ws)
        assertTrue(cues[0].endMs <= cues[0].startMs + 350 + 400)
        assertTrue(cues[0].endMs < cues[1].startMs)
    }

    @Test fun fallsBackToProportionalSplitWithoutWords() {
        val cues = CueBuilder.build(listOf(Seg(0, 2000, "واحد اثنين ثلاثة اربعة خمسة ستة")), emptyList())
        assertEquals(2, cues.size)
        assertTrue(cues.all { it.words.isEmpty() })
    }
}
