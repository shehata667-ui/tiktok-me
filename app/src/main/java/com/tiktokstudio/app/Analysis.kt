package com.tiktokstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Level { OK, INFO, WARN }
enum class Fix(val label: String) {
    FIXABLE("قابل للإصلاح تقنيًا"), EDITORIAL("إجراء تحريري اختياري"),
    UNFIXABLE("مش قابل للتحسين آليًا"), AUTHENTICITY("أصالة/حقوق: مش بتتحل آليًا"), NONE("")
}
data class Finding(val title: String, val detail: String, val level: Level, val fix: Fix, val action: String)
data class TimingStats(
    val frames: Int, val avgFps: Float, val medianFps: Float, val minIntervalMs: Float, val maxIntervalMs: Float,
    val irregularPct: Float, val isVfr: Boolean, val hasGaps: Boolean,
)
data class RegionBox(val label: String, val l: Float, val t: Float, val r: Float, val b: Float)
data class Bars(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val any get() = max(max(left, right), max(top, bottom)) >= 0.03f
}
data class Analysis(
    val info: VideoInfo, val codec: String, val hdr: Boolean, val timing: TimingStats?, val bars: Bars?,
    val overlays: List<RegionBox>, val qr: List<RegionBox>?, val meta: List<Pair<String, String>>,
    val hasLocation: Boolean, val frame: Bitmap?, val findings: List<Finding>,
)

object AnalysisCache {
    private val m = HashMap<String, Analysis>()
    fun get(u: Uri): Analysis? = m[u.toString()]
    fun put(u: Uri, a: Analysis) { m[u.toString()] = a }
}

fun codecName(mime: String?) = when (mime) {
    "video/avc" -> "H.264"; "video/hevc" -> "H.265 (HEVC)"; "video/x-vnd.on2.vp9" -> "VP9"; "video/av01" -> "AV1"
    null -> "غير معروف"; else -> mime
}

/** قياس انتظام التوقيت الفعلي للإطارات من المسار نفسه (من غير فك ترميز). */
fun timingStats(ctx: Context, uri: Uri): TimingStats? {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(ctx, uri, null)
        val vi = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
        ex.selectTrack(vi)
        val ts = ArrayList<Long>()
        while (true) { val t = ex.sampleTime; if (t < 0) break; ts.add(t); if (!ex.advance()) break }
        if (ts.size < 3) return null
        ts.sort()
        val d = LongArray(ts.size - 1) { ts[it + 1] - ts[it] }
        val sorted = d.sortedArray()
        val med = sorted[sorted.size / 2].coerceAtLeast(1)
        val irregular = d.count { abs(it - med) > 0.25 * med }
        val span = (ts.last() - ts.first()).coerceAtLeast(1)
        val avg = (ts.size - 1) * 1e6f / span
        val medFps = 1e6f / med
        val pct = irregular * 100f / d.size
        val vfr = pct > 5f || abs(avg - medFps) > 0.1f * medFps
        return TimingStats(ts.size, avg, medFps, sorted.first() / 1000f, sorted.last() / 1000f, pct, vfr, sorted.last() > 2.5 * med)
    } catch (e: Exception) { return null } finally { ex.release() }
}

private fun isHdr(ctx: Context, uri: Uri): Boolean {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(ctx, uri, null)
        (0 until ex.trackCount).any {
            val f = ex.getTrackFormat(it)
            f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                f.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let { c -> c == MediaFormat.COLOR_TRANSFER_ST2084 || c == MediaFormat.COLOR_TRANSFER_HLG }
        }
    } catch (e: Exception) { false } finally { ex.release() }
}

private fun sampleFrames(ctx: Context, info: VideoInfo, count: Int, w: Int, option: Int): List<Bitmap> {
    val r = MediaMetadataRetriever(); val out = ArrayList<Bitmap>()
    try {
        r.setDataSource(ctx, info.uri)
        val h = (w * info.height.toFloat() / info.width).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val tUs = info.durationMs * 1000L * (2 * i + 1) / (2 * count)
            runCatching { r.getScaledFrameAtTime(tUs, option, w, h) }.getOrNull()?.let { out.add(it) }
        }
    } catch (_: Exception) {} finally { r.release() }
    return out
}

private fun gray(b: Bitmap): IntArray {
    val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    for (i in px.indices) { val c = px[i]; px[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
    return px
}

private fun detectBars(g: List<IntArray>, w: Int, h: Int): Bars? {
    fun rowBlack(y: Int) = g.all { p -> var s = 0L; for (x in 0 until w) s += p[y * w + x]; s / w < 18 }
    fun colBlack(x: Int) = g.all { p -> var s = 0L; for (y in 0 until h) s += p[y * w + x]; s / h < 18 }
    var t = 0; while (t < h / 2 && rowBlack(t)) t++
    var b = 0; while (b < h / 2 && rowBlack(h - 1 - b)) b++
    var l = 0; while (l < w / 2 && colBlack(l)) l++
    var r = 0; while (r < w / 2 && colBlack(w - 1 - r)) r++
    if (t >= h / 2 - 1 || l >= w / 2 - 1) return null   // فيديو غامق بالكامل: النتيجة مش موثوقة
    return Bars(l.toFloat() / w, t.toFloat() / h, r.toFloat() / w, b.toFloat() / h)
}

/** كشف تقريبي لعناصر ثابتة في الأركان (شعار/علامة): منطقة ثابتة زمنيًا لكن فيها تفاصيل، والباقي بيتحرك. */
private fun detectOverlays(g: List<IntArray>, w: Int, h: Int): List<RegionBox> {
    if (g.size < 4) return emptyList()
    val n = w * h
    val mean = FloatArray(n); val std = FloatArray(n)
    for (p in g) for (i in 0 until n) mean[i] += p[i]
    for (i in 0 until n) mean[i] /= g.size
    for (p in g) for (i in 0 until n) { val d = p[i] - mean[i]; std[i] += d * d }
    for (i in 0 until n) std[i] = sqrt(std[i] / g.size)
    val globalStd = std.average().toFloat()
    if (globalStd < 10f) return emptyList()
    val rw = (w * 0.28f).toInt(); val rh = (h * 0.12f).toInt()
    val regions = listOf("أعلى اليسار" to (0 to 0), "أعلى اليمين" to (w - rw to 0), "أسفل اليسار" to (0 to h - rh), "أسفل اليمين" to (w - rw to h - rh))
    val res = ArrayList<RegionBox>()
    for ((name, o) in regions) {
        var sStd = 0f; var edge = 0f; var cnt = 0; var ec = 0
        for (y in o.second until o.second + rh) for (x in o.first until o.first + rw) {
            sStd += std[y * w + x]; cnt++
            if (x + 1 < o.first + rw) { edge += abs(mean[y * w + x + 1] - mean[y * w + x]); ec++ }
        }
        if (cnt == 0 || ec == 0) continue
        if (sStd / cnt < 0.35f * globalStd && edge / ec >= 6f)
            res.add(RegionBox(name, o.first.toFloat() / w, o.second.toFloat() / h, (o.first + rw).toFloat() / w, (o.second + rh).toFloat() / h))
    }
    return res
}

private fun detectQr(frames: List<Bitmap>): List<RegionBox>? = try {
    val sc = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    val res = ArrayList<RegionBox>()
    try {
        for (f in frames) for (c in Tasks.await(sc.process(InputImage.fromBitmap(f, 0)))) c.boundingBox?.let { bb ->
            if (res.size < 4) res.add(RegionBox("QR", bb.left.toFloat() / f.width, bb.top.toFloat() / f.height, bb.right.toFloat() / f.width, bb.bottom.toFloat() / f.height))
        }
    } finally { sc.close() }
    res
} catch (e: Exception) { null }

private fun readMeta(ctx: Context, uri: Uri): Pair<List<Pair<String, String>>, Boolean> {
    val keys = listOf(
        MediaMetadataRetriever.METADATA_KEY_TITLE to "العنوان", MediaMetadataRetriever.METADATA_KEY_ARTIST to "الفنان",
        MediaMetadataRetriever.METADATA_KEY_AUTHOR to "المؤلف", MediaMetadataRetriever.METADATA_KEY_WRITER to "الكاتب",
        MediaMetadataRetriever.METADATA_KEY_ALBUM to "الألبوم", MediaMetadataRetriever.METADATA_KEY_COMPOSER to "الملحّن",
        MediaMetadataRetriever.METADATA_KEY_GENRE to "النوع", MediaMetadataRetriever.METADATA_KEY_DATE to "تاريخ الإنشاء",
        MediaMetadataRetriever.METADATA_KEY_LOCATION to "الموقع الجغرافي", MediaMetadataRetriever.METADATA_KEY_MIMETYPE to "نوع الملف",
        MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE to "معدل التصوير", MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS to "عدد المسارات",
    )
    val r = MediaMetadataRetriever(); val out = ArrayList<Pair<String, String>>(); var loc = false
    try {
        r.setDataSource(ctx, uri)
        for ((k, label) in keys) r.extractMetadata(k)?.takeIf { it.isNotBlank() }?.let {
            out.add(label to it); if (k == MediaMetadataRetriever.METADATA_KEY_LOCATION) loc = true
        }
    } catch (_: Exception) {} finally { r.release() }
    return out to loc
}

fun analyzeVideo(ctx: Context, info: VideoInfo): Analysis {
    val timing = runCatching { timingStats(ctx, info.uri) }.getOrNull()
    val hdr = isHdr(ctx, info.uri)
    val frames = sampleFrames(ctx, info, 8, 160, MediaMetadataRetriever.OPTION_CLOSEST)
    val w = frames.firstOrNull()?.width ?: 0; val h = frames.firstOrNull()?.height ?: 0
    val g = frames.map { gray(it) }
    val bars = if (frames.size >= 2) runCatching { detectBars(g, w, h) }.getOrNull() else null
    val overlays = if (frames.size >= 4) runCatching { detectOverlays(g, w, h) }.getOrDefault(emptyList()) else emptyList()
    val big = sampleFrames(ctx, info, 5, 640, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    val qr = if (big.isNotEmpty()) detectQr(big) else null
    val (meta, loc) = readMeta(ctx, info.uri)
    val f = findings(info, timing, hdr, bars, overlays, qr, loc)
    return Analysis(info, codecName(info.mime), hdr, timing, bars, overlays, qr, meta, loc, big.getOrNull(big.size / 2), f)
}

private fun pct(x: Float) = String.format(Locale.US, "%.0f%%", x * 100)

private fun findings(info: VideoInfo, t: TimingStats?, hdr: Boolean, bars: Bars?, ov: List<RegionBox>, qr: List<RegionBox>?, hasLoc: Boolean): List<Finding> {
    val f = ArrayList<Finding>()
    if (min(info.width, info.height) < 720) f += Finding("دقة منخفضة", "${info.resolution}؛ أقل من 720 في الجهة الأقصر.", Level.WARN, Fix.UNFIXABLE,
        "التطبيق مش بيكبّر الفيديو بشكل يوحي بتحسن التفاصيل (بيخفّض المقاس المطلوب تلقائيًا). الأفضل استخدام نسخة أعلى دقة لو متاحة.")
    if (!info.isPortrait) f += Finding("الفيديو أفقي", "${info.resolution} مش مناسب مباشرة لعرض TikTok الرأسي.", Level.INFO, Fix.EDITORIAL,
        "«ملء الشاشة (قص)» بيفقدك جوانب من الصورة، و«احتواء» بيحافظ على الصورة كاملة بأشرطة سودا. اختار حسب المحتوى.")
    else if (abs(info.width.toFloat() / info.height - 9f / 16f) > 0.02f) f += Finding("النسبة مش 9:16", "النسبة الحالية ${info.resolution}.", Level.INFO, Fix.EDITORIAL,
        "استخدم القص أو الاحتواء من شاشة التجهيز.")
    if (info.rotation != 0) f += Finding("تدوير في البيانات", "الفيديو متسجل بتدوير ${info.rotation}° في الملف.", Level.INFO, Fix.FIXABLE,
        "التطبيق بيراعيه تلقائيًا. لو لسه الاتجاه غلط استخدم زرار التدوير.")
    if (t != null) {
        if (t.isVfr) f += Finding("معدل إطارات متغير (VFR)", String.format(Locale.US, "المتوسط %.1f fps والوسيط %.1f fps، و%.0f%% من الفواصل غير منتظمة.", t.avgFps, t.medianFps, t.irregularPct),
            Level.WARN, Fix.FIXABLE, "فعّل «تثبيت معدل الإطارات CFR» في التجهيز. بيسقط إطارات للوصول للمعدل (مش بيكررها) وممكن يغيّر الحجم شوية. اتأكد من التقرير بعد التصدير.")
        if (t.hasGaps) f += Finding("فجوات زمنية بين الإطارات", String.format(Locale.US, "أكبر فجوة %.0f ms.", t.maxIntervalMs),
            Level.WARN, Fix.UNFIXABLE, "الفجوات موجودة في المصدر نفسه وممكن تظهر كتقطيع. المعالجة مش هتصلحها.")
        if (info.fps != null && abs(info.fps - t.avgFps) > 0.1f * t.avgFps) f += Finding("المعدل المعلن مختلف عن المقاس",
            String.format(Locale.US, "المعلن %.0f fps والمقاس %.1f fps.", info.fps, t.avgFps), Level.INFO, Fix.NONE, "بنعتمد على القياس الفعلي في الفحص.")
        if (t.avgFps > 61f) f += Finding("معدل إطارات عالي", String.format(Locale.US, "%.0f fps.", t.avgFps), Level.INFO, Fix.EDITORIAL, "ممكن تخفضه لـ 30 أو 60 لتقليل الحجم.")
    }
    if (hdr) f += Finding("فيديو HDR", "الملف بيستخدم نقل لوني HDR.", Level.WARN, Fix.UNFIXABLE,
        "التصدير H.264 SDR، والتطبيق لسه ملوش تحويل ألوان HDR→SDR، فالألوان ممكن تطلع باهتة. جرّب الناتج بالعين.")
    if (bars != null && bars.any) f += Finding("أشرطة سودا محتملة", "يمين ${pct(bars.right)} • يسار ${pct(bars.left)} • أعلى ${pct(bars.top)} • أسفل ${pct(bars.bottom)} (تقديري).",
        Level.WARN, Fix.EDITORIAL, "«ملء الشاشة (قص)» بيشيلها لكن بيقص جزء من الصورة؛ شوف المعاينة قبل ما تقرر.")
    if (ov.isNotEmpty()) f += Finding("عناصر ثابتة محتملة (شعار/علامة)", "في: " + ov.joinToString("، ") { it.label } + " (فحص تقديري ممكن يغلط).",
        Level.WARN, Fix.AUTHENTICITY, "لو العناصر دي تخص مصدر أو منصة تانية، التطبيق مش بيشيلها. القص لأسباب تحريرية متاح مع المعاينة، لكن مفيش ضمان إن ده يغيّر أهلية التوصية.")
    if (qr == null) f += Finding("فحص QR", "تعذّر تشغيل فاحص الـ QR على الجهاز.", Level.INFO, Fix.NONE, "مفيش إجراء.")
    else if (qr.isNotEmpty()) f += Finding("رمز QR ظاهر", "اتكشف ${qr.size} رمز QR في إطارات العيّنة.", Level.WARN, Fix.AUTHENTICITY,
        "رموز QR ممكن تأثر على التوصية. التطبيق مش بيشيلها تلقائيًا؛ لو بتاعتك والقص مناسب تحريريًا استخدمه مع المعاينة.")
    if (hasLoc) f += Finding("بيانات الموقع موجودة", "الملف فيه إحداثيات جغرافية.", Level.WARN, Fix.FIXABLE,
        "خيار «مسح بيانات الموقع» في التجهيز بيمسحها من النسخة الناتجة، والتقرير بعد التصدير بيتأكد إنها اتمسحت. ده مش بيخلي الفيديو «أصلي».")
    if (!info.hasAudioGuess) f += Finding("مفيش مسار صوت", "الملف من غير صوت.", Level.INFO, Fix.NONE, "مفيش إجراء مطلوب.")
    if (f.none { it.level == Level.WARN }) f.add(0, Finding("مفيش مشاكل فنية واضحة", "الفحص التقديري ما لقاش مشاكل ظاهرة.", Level.OK, Fix.NONE,
        "ده مش ضمان لأهلية النشر أو التوصية."))
    return f
}

/** بيرسم مستطيلات المواضع المحتملة على الإطار: أحمر للشعارات، أزرق لـ QR. */
fun drawBoxes(src: Bitmap, ov: List<RegionBox>, qr: List<RegionBox>?): Bitmap {
    val b = src.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(b)
    val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = max(2f, b.width / 120f) }
    p.color = Color.RED; ov.forEach { c.drawRect(it.l * b.width, it.t * b.height, it.r * b.width, it.b * b.height, p) }
    p.color = Color.rgb(33, 150, 243); qr?.forEach { c.drawRect(it.l * b.width, it.t * b.height, it.r * b.width, it.b * b.height, p) }
    return b
}
