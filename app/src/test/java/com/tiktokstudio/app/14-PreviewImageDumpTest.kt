package com.tiktokstudio.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** يحفظ صور PNG حقيقية من ناتج المعاينة والهوك للمراجعة البصرية (نفس كود التطبيق). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewImageDumpTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val outDir = File("/tmp/tiktok-me/app/build/preview-images")

    private fun twoToneFrame(): Bitmap {
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint()
        p.color = Color.rgb(220, 60, 60); c.drawRect(0f, 0f, 960f, 1080f, p)
        p.color = Color.rgb(60, 90, 220); c.drawRect(960f, 0f, 1920f, 1080f, p)
        // خطوط رفيعة عشان توضح القص والتدوير
        p.color = Color.WHITE
        for (x in 0 until 1920 step 120) c.drawRect(x.toFloat(), 0f, x + 4f, 1080f, p)
        return b
    }

    private fun save(b: Bitmap, name: String) {
        outDir.mkdirs()
        File(outDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun dumpPreviewImages() {
        val s = ExportSettings(mode = FitMode.FILL, preset = Preset.P1080)
        val frame = twoToneFrame()

        save(composePreview(ctx, frame, s.copy(cropPos = 0f), Watermark(enabled = false)), "1-crop-left.png")
        save(composePreview(ctx, frame, s.copy(cropPos = 1f), Watermark(enabled = false)), "2-crop-right.png")
        save(composePreview(ctx, frame, s, Watermark(enabled = true, text = "استنى النهاية", textX = 0.5f, textY = 0.14f, textSize = 0.12f)), "3-watermark.png")
        save(composePreview(ctx, frame, s.copy(rotation = 90), Watermark(enabled = false)), "4-rotated.png")
        val composed = composePreview(ctx, frame, s, Watermark(enabled = false))
        save(drawHookPreview(composed, "ده هيغيّر طريقتك في المونتاج"), "5-hook.png")

        val files = outDir.listFiles()?.filter { it.length() > 0 } ?: emptyList()
        assertTrue("expected 5 non-empty PNGs, got ${files.size}", files.size == 5)
    }
}
