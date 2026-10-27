package com.tiktokstudio.app

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class FontChoice(val label: String, val tf: Typeface) {
    NORMAL("عادي", Typeface.SANS_SERIF),
    BOLD("عريض", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)),
    ITALIC("مائل", Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)),
    SERIF("Serif", Typeface.SERIF),
    MONO("Mono", Typeface.MONOSPACE),
}

val TEXT_COLORS = listOf(
    "أبيض" to Color.WHITE, "أسود" to Color.BLACK, "أصفر" to Color.YELLOW,
    "أحمر" to Color.RED, "سماوي" to Color.CYAN, "أخضر" to Color.GREEN,
)

data class Watermark(
    val enabled: Boolean = true,
    val text: String = "",
    val textEnabled: Boolean = true,
    val textSize: Float = 0.06f,      // نسبة من عرض الفيديو
    val textOpacity: Float = 1f,
    val textColor: Int = Color.WHITE,
    val font: FontChoice = FontChoice.BOLD,
    val shadow: Boolean = true,
    val textX: Float = 0.5f, val textY: Float = 0.14f,   // مركز العنصر (0..1)
    val logoUri: String? = null,
    val logoSize: Float = 0.2f,       // نسبة من عرض الفيديو
    val logoOpacity: Float = 1f,
    val logoX: Float = 0.15f, val logoY: Float = 0.07f,
) {
    val hasContent get() = enabled && ((textEnabled && text.isNotBlank()) || logoUri != null)

    fun toJson() = JSONObject().apply {
        put("enabled", enabled); put("text", text); put("textEnabled", textEnabled)
        put("textSize", textSize.toDouble()); put("textOpacity", textOpacity.toDouble()); put("textColor", textColor)
        put("font", font.name); put("shadow", shadow)
        put("textX", textX.toDouble()); put("textY", textY.toDouble())
        put("logoUri", logoUri ?: JSONObject.NULL)
        put("logoSize", logoSize.toDouble()); put("logoOpacity", logoOpacity.toDouble())
        put("logoX", logoX.toDouble()); put("logoY", logoY.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): Watermark {
            val d = Watermark()
            return Watermark(
                o.optBoolean("enabled", d.enabled), o.optString("text", ""), o.optBoolean("textEnabled", true),
                o.optDouble("textSize", d.textSize.toDouble()).toFloat(),
                o.optDouble("textOpacity", 1.0).toFloat(), o.optInt("textColor", d.textColor),
                runCatching { FontChoice.valueOf(o.optString("font")) }.getOrDefault(d.font),
                o.optBoolean("shadow", true),
                o.optDouble("textX", d.textX.toDouble()).toFloat(), o.optDouble("textY", d.textY.toDouble()).toFloat(),
                if (o.isNull("logoUri")) null else o.optString("logoUri"),
                o.optDouble("logoSize", d.logoSize.toDouble()).toFloat(), o.optDouble("logoOpacity", 1.0).toFloat(),
                o.optDouble("logoX", d.logoX.toDouble()).toFloat(), o.optDouble("logoY", d.logoY.toDouble()).toFloat(),
            )
        }
    }
}

object WmStore {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun last(c: Context): Watermark = runCatching { Watermark.fromJson(JSONObject(sp(c).getString("wm_last", null)!!)) }.getOrDefault(Watermark())
    fun saveLast(c: Context, w: Watermark) = sp(c).edit().putString("wm_last", w.toJson().toString()).apply()
    private fun tplObj(c: Context) = runCatching { JSONObject(sp(c).getString("wm_tpl", "{}")!!) }.getOrDefault(JSONObject())
    fun templates(c: Context): Map<String, Watermark> {
        val o = tplObj(c)
        return o.keys().asSequence().associateWith { Watermark.fromJson(o.getJSONObject(it)) }
    }
    fun saveTemplate(c: Context, name: String, w: Watermark) {
        val o = tplObj(c); o.put(name, w.toJson()); sp(c).edit().putString("wm_tpl", o.toString()).apply()
    }
    fun deleteTemplate(c: Context, name: String) {
        val o = tplObj(c); o.remove(name); sp(c).edit().putString("wm_tpl", o.toString()).apply()
    }
}

object LogoCache {
    private var key: String? = null
    private var bmp: Bitmap? = null
    @Synchronized fun get(ctx: Context, u: Uri): Bitmap? {
        if (key == u.toString() && bmp != null) return bmp
        val b = decode(ctx, u); key = u.toString(); bmp = b; return b
    }
    private fun decode(ctx: Context, u: Uri): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return@runCatching null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }.getOrNull()
}

/**
 * بيرسم طبقة شفافة بنفس مقاس الفيديو الناتج بالظبط (نص عربي + شعار PNG).
 * نفس الدالة بتستخدم في المعاينة (بمقاس صغير) وفي التصدير (بالمقاس الحقيقي)،
 * فالمعاينة = الناتج. كل الأحجام نسبة من عرض الإطار.
 */
fun renderOverlay(ctx: Context, w: Int, h: Int, wm: Watermark): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    wm.logoUri?.let { u ->
        LogoCache.get(ctx, Uri.parse(u))?.let { lb ->
            var lw = w * wm.logoSize
            var lh = lw * lb.height / lb.width
            if (lh > h * 0.9f) { lw *= h * 0.9f / lh; lh = h * 0.9f }
            val left = (wm.logoX * w - lw / 2).coerceIn(0f, max(0f, w - lw))
            val top = (wm.logoY * h - lh / 2).coerceIn(0f, max(0f, h - lh))
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = (wm.logoOpacity * 255).toInt() }
            c.drawBitmap(lb, null, RectF(left, top, left + lw, top + lh), p)
        }
    }
    if (wm.textEnabled && wm.text.isNotBlank()) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = w * wm.textSize
            typeface = wm.font.tf
            color = wm.textColor
            alpha = (wm.textOpacity * 255).toInt()
            if (wm.shadow) setShadowLayer(textSize * 0.08f, textSize * 0.04f, textSize * 0.04f,
                Color.argb((wm.textOpacity * 200).toInt(), 0, 0, 0))
        }
        val maxW = (w * 0.95f).toInt()
        val boxW = min(ceil(StaticLayout.getDesiredWidth(wm.text, paint)).toInt().coerceAtLeast(1), maxW)
        val layout = StaticLayout.Builder.obtain(wm.text, 0, wm.text.length, paint, boxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val left = (wm.textX * w - boxW / 2f).coerceIn(0f, max(0f, (w - boxW).toFloat()))
        val top = (wm.textY * h - layout.height / 2f).coerceIn(0f, max(0f, (h - layout.height).toFloat()))
        c.save(); c.translate(left, top); layout.draw(c); c.restore()
    }
    return bmp
}

/** معاينة 9:16: إطار من الفيديو بنفس منطق القص/الاحتواء/التدوير + الطبقة. */
fun composePreview(ctx: Context, frame: Bitmap, s: ExportSettings, wm: Watermark): Bitmap {
    val tw = 360; val th = 640
    val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    val c = Canvas(out); c.drawColor(Color.BLACK)
    var f = frame
    if (s.rotation != 0) {
        val m = Matrix().apply { postRotate(-s.rotation.toFloat()) }   // نفس اتجاه Media3 (عكس عقارب الساعة)
        f = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
    }
    val fw = f.width.toFloat(); val fh = f.height.toFloat()
    val fill = s.mode == FitMode.FILL
    val sc = if (fill) max(tw / fw, th / fh) else min(tw / fw, th / fh)
    val dw = fw * sc; val dh = fh * sc
    val dx = if (fill) (tw - dw) * s.cropPos else (tw - dw) / 2
    val dy = if (fill) (th - dh) * s.cropPos else (th - dh) / 2
    c.drawBitmap(f, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
    if (wm.hasContent) {
        val ov = renderOverlay(ctx, tw, th, wm)
        c.drawBitmap(ov, 0f, 0f, null); ov.recycle()
    }
    return out
}
