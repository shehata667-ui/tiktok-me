package com.tiktokstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookAnimTest {
    private val total = 3000L

    @Test fun popsInFromSmallAndTransparent() {
        val (a0, s0) = HookAnim.at(0, total)
        assertEquals(0f, a0, 0.001f); assertEquals(0.85f, s0, 0.001f)
        val (a1, s1) = HookAnim.at(250, total)
        assertEquals(1f, a1, 0.001f); assertEquals(1f, s1, 0.001f)
    }

    @Test fun holdsThenFadesOut() {
        assertEquals(1f, HookAnim.at(1500, total).first, 0.001f)
        val fading = HookAnim.at(2900, total).first
        assertTrue(fading in 0.1f..0.9f)
    }

    @Test fun invisibleOutsideWindow() {
        assertEquals(0f, HookAnim.at(3000, total).first, 0f)
        assertEquals(0f, HookAnim.at(-1, total).first, 0f)
    }
}
