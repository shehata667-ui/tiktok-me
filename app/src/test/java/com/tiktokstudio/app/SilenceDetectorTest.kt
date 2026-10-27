package com.tiktokstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private val frame = SilenceDetector.FRAME_MS

    private fun levels(vararg parts: Pair<Int, Float>): FloatArray =
        parts.flatMap { (ms, db) -> List(ms / frame) { db } }.toFloatArray()

    private fun plan(db: FloatArray) = SilenceDetector.plan(
        db, 0, db.size.toLong() * frame, sensitivity = 0.5f, minSilenceMs = 350, padMs = 90, minSpeechMs = 200)

    @Test fun cutsLongSilenceBetweenSpeech() {
        val s = plan(levels(1000 to -20f, 1000 to -70f, 1000 to -20f))
        assertEquals(2, s.size)
        assertTrue(s[0].endMs in 1050..1130)
        assertTrue(s[1].startMs in 1870..1950)
    }

    @Test fun keepsShortNaturalPauses() {
        val s = plan(levels(1000 to -20f, 200 to -70f, 1000 to -20f))
        assertEquals(1, s.size)
    }

    @Test fun keepsEverythingWhenNoSilenceOrAllSilent() {
        assertEquals(1, plan(levels(2000 to -20f)).size)
        val allSilent = plan(levels(2000 to -70f))
        assertEquals(1, allSilent.size)
        assertEquals(2000L, allSilent[0].durationMs)
    }
}
