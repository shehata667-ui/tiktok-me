package com.tiktokstudio.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * تحقق بالبكسلات الفعلية من منطق رسم المعاينة والهوك (نفس الكود اللي بينتج صورة المعاينة للمستخدم).
 * بيشتغل بـ Robolectric بوضع رسم حقيقي (NATIVE) فالرسم على الـ Bitmap بيحصل فعلاً.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewRenderTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** كادر مصدر أفقي 1920x1080: النص الشمال أحمر والنص اليمين أزرق (الملء لـ 9:16 بيقص من العرض) */
    private fun twoToneFrame(): Bitmap {
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, 960f, 1080f, p)
        p.color = Color.BLUE; c.drawRect(960f, 0f, 1920f, 1080f, p)
        return b
    }

    private fun base() = ExportSettings(mode = FitMode.FILL, preset = Preset.P1080)

    @Test
    fun composePreview_outputSizeIs360x640() {
        val out = composePreview(ctx, twoToneFrame(), base(), Watermark(enabled = false))
        assertEquals(360, out.width)
        assertEquals(640, out.height)
    }

    @Test
    fun composePreview_cropPosZero_keepsLeftHalf() {
        val out = composePreview(ctx, twoToneFrame(), base().copy(cropPos = 0f), Watermark(enabled = false))
        assertTrue("center should be red at cropPos=0", Color.red(out.getPixel(180, 320)) > 200)
    }

    @Test
    fun composePreview_cropPosOne_keepsRightHalf() {
        val out = composePreview(ctx, twoToneFrame(), base().copy(cropPos = 1f), Watermark(enabled = false))
        assertTrue("center should be blue at cropPos=1", Color.blue(out.getPixel(180, 320)) > 200)
    }

    @Test
    fun composePreview_textWatermark_changesPixelsNearTextPosition() {
        val frame = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888) // أسود
        val plain = composePreview(ctx, frame, base(), Watermark(enabled = false))
        val wm = Watermark(enabled = true, text = "اختبار", textX = 0.5f, textY = 0.14f, textSize = 0.12f)
        val marked = composePreview(ctx, frame, base(), wm)
        // منطقة حوالين موضع النص (y=14% من الارتفاع)
        var diff = 0
        for (x in 40 until 320 step 4) for (y in 40 until 150 step 4) {
            if (plain.getPixel(x, y) != marked.getPixel(x, y)) diff++
        }
        assertTrue("watermark text should change pixels near its position, got $diff", diff > 50)
    }

    @Test
    fun drawHookPreview_drawsTealBoxWithDarkTextInUpperArea() {
        val baseBmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888) // أسود
        val out = drawHookPreview(baseBmp, "هوك تجريبي")
        var teal = 0; var dark = 0
        for (x in 100 until 620 step 6) for (y in 190 until 280 step 6) {
            val p = out.getPixel(x, y)
            if (Color.green(p) > 180 && Color.blue(p) > 150 && Color.red(p) < 120) teal++
            if (Color.red(p) < 60 && Color.green(p) < 60 && Color.blue(p) < 60) dark++
        }
        assertTrue("expected teal hook background pixels, got $teal", teal > 50)
        assertTrue("expected dark hook text pixels, got $dark", dark > 5)
    }
}
