package com.tiktokstudio.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MidEllipsisTest {

    @Test
    fun shortName_unchanged() {
        assertEquals("video.mp4", midEllipsis("video.mp4"))
    }

    @Test
    fun exactLimit_unchanged() {
        val name = "a".repeat(36)
        assertEquals(name, midEllipsis(name))
    }

    @Test
    fun longName_truncatedWithEllipsisInMiddle() {
        val name = "video_2024_very_long_file_name_from_camera_roll_export.mp4"
        val out = midEllipsis(name)
        assertEquals(36, out.length)
        // البداية والنهاية محفوظين والاختصار في النص
        assertEquals(true, out.startsWith(name.take(10)))
        assertEquals(true, out.endsWith(name.takeLast(10)))
        assertEquals(true, out.contains("…"))
    }

    @Test
    fun customLimit_respected() {
        val out = midEllipsis("abcdefghij".repeat(5), 20)
        assertEquals(20, out.length)
    }
}
