package com.tiktokstudio.app

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Preset(val w: Int, val h: Int, val label: String) {
    BEST(0, 0, "الأعلى المتاح (حتى 4K)"),
    P2160(2160, 3840, "4K · 2160×3840"),
    P1080(1080, 1920, "1080×1920"),
    P720(720, 1280, "720×1280"),
}
enum class FitMode(val label: String) { FILL("ملء الشاشة (قص)"), FIT("احتواء (أشرطة سوداء)") }
enum class Codec(val label: String) { AUTO("تلقائي (الأصغر حجمًا)"), HEVC("H.265 (أصغر حجمًا)"), H264("H.264 (أوسع توافقًا)") }
enum class Quality(val label: String) {
    TIKTOK("مناسب لـ TikTok"), EFFICIENT("أقل حجم بجودة عالية"), HIGH("مرتفعة"), MEDIUM("متوسطة"), LOW("منخفضة"), CUSTOM("مخصصة"), TARGET("حجم مستهدف")
}

data class ExportSettings(
    val preset: Preset = Preset.P1080,
    val mode: FitMode = FitMode.FILL,
    val cropPos: Float = 0.5f,
    val rotation: Int = 0,
    val quality: Quality = Quality.TIKTOK,
    val codec: Codec = Codec.H264,
    val customMbps: Float = 8f,
    val targetMb: Int = 30,
    val fpsCap: Int = 0,            // 0 = نفس معدل المصدر (بحد أقصى 60)
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val mute: Boolean = false,
    val cfrFps: Int = 0,             // > 0 = تثبيت معدل الإطارات على القيمة دي
    val stripMeta: Boolean = true,   // مسح بيانات الموقع من الناتج
    val cutSilence: Boolean = false, // قص السكتات تلقائيًا
    val silenceSens: Float = 0.5f,   // 0 يقص أقل .. 1 يقص أكتر
    val captions: Boolean = false,   // كابشن عربي تلقائي (محتاج مفتاح OpenAI)
    val highlight: Boolean = true,   // تمييز الكلمة الحالية
    val hookText: String = "",       // نص الهوك في أول ثواني (فاضي = من غير هوك)
    val hookSec: Float = 3f,
)

data class Plan(
    val outW: Int, val outH: Int, val videoBps: Int, val audioBps: Int,
    val estBytes: Long, val durationMs: Long, val notes: List<String>,
    val mime: String = "video/avc", val codecLabel: String = "H.264",
    val outFps: Float = 30f, val applyFps: Int = 0,
)

/** قدرات مشفّر الفيديو الهاردوير على الجهاز (للتأكد قبل ما نطلب 4K أو 60fps أو H.265). */
object DeviceCaps {
    private val cache = HashMap<String, Boolean>()
    private fun isHw(ci: MediaCodecInfo) =
        if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
        else !(ci.name.startsWith("OMX.google.") || ci.name.startsWith("c2.android."))

    @Synchronized
    fun supports(mime: String, w: Int, h: Int, fps: Float): Boolean {
        val key = "$mime/$w/$h/${fps.toInt()}"
        return cache.getOrPut(key) {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                    ci.isEncoder && isHw(ci) && ci.supportedTypes.any { it.equals(mime, true) } && run {
                        val vc = ci.getCapabilitiesForType(mime).videoCapabilities
                        vc.areSizeAndRateSupported(w, h, fps.toDouble()) || vc.areSizeAndRateSupported(h, w, fps.toDouble())
                    }
                }
            }.getOrDefault(false)
        }
    }
}

fun effSize(info: VideoInfo, rot: Int) = if (rot == 90 || rot == 270) info.height to info.width else info.width to info.height

fun makePlan(info: VideoInfo, s: ExportSettings): Plan {
    val (sw, sh) = effSize(info, s.rotation)
    val notes = mutableListOf<String>()
    fun up(p: Preset): Double {
        val a = p.w.toDouble() / sw; val b = p.h.toDouble() / sh
        return if (s.mode == FitMode.FILL) max(a, b) else min(a, b)
    }

    // ---- معدل الإطارات: بنحافظ على الأصلي (بحد أقصى 60)، ومبنزودش إطارات مصطنعة ----
    val srcFps = info.fps ?: 30f
    val reqFps = when {
        s.cfrFps > 0 -> s.cfrFps.toFloat()
        s.fpsCap > 0 -> min(s.fpsCap.toFloat(), srcFps)
        else -> srcFps
    }
    val outFps = min(reqFps, 60f).coerceAtLeast(1f)
    val applyFps = when {
        s.cfrFps > 0 -> s.cfrFps
        s.fpsCap > 0 && srcFps > s.fpsCap + 1 -> s.fpsCap
        srcFps > 60.5f -> 60
        else -> 0
    }
    if (s.cfrFps == 0 && s.fpsCap == 0 && srcFps > 60.5f) notes += "المصدر ${srcFps.toInt()} fps، هيتخفض لـ 60 fps (الحد الأقصى المناسب)."

    // ---- الدقة والترميز: أعلى اختيار مدعوم من الجهاز ومن غير تكبير ----
    val presets = when (s.preset) {
        Preset.BEST, Preset.P2160 -> listOf(Preset.P2160, Preset.P1080, Preset.P720)
        Preset.P1080 -> listOf(Preset.P1080, Preset.P720)
        Preset.P720 -> listOf(Preset.P720)
    }
    val codecs = when (s.codec) {
        Codec.H264 -> listOf("video/avc" to "H.264")
        else -> listOf("video/hevc" to "H.265", "video/avc" to "H.264")
    }
    val noUpscale = presets.filter { up(it) <= 1.001 }.ifEmpty { listOf(presets.last()) }
    if (noUpscale.first() != presets.first()) notes += "دقة الفيديو الأصلي أقل من ${presets.first().label}، فاستخدمنا ${noUpscale.first().label} لتجنّب التكبير."
    if (up(noUpscale.last()) > 1.001 && noUpscale.size == 1) notes += "الفيديو هيتكبّر لمقاس أكبر من الأصلي، وده مش بيحسّن التفاصيل."

    var chosen: Triple<Preset, String, String>? = null
    outer@ for (p in noUpscale) for ((mime, label) in codecs) {
        if (DeviceCaps.supports(mime, p.w, p.h, outFps)) { chosen = Triple(p, mime, label); break@outer }
    }
    val pick = chosen ?: Triple(noUpscale.first(), codecs.last().first, codecs.last().second).also {
        notes += "تعذّر التأكد من دعم الجهاز لـ ${noUpscale.first().label} بـ ${outFps.toInt()} fps؛ هنجرّب التصدير والمشفّر ممكن يعدّل الإعدادات."
    }
    val (p, mime, codecLabel) = pick
    if (chosen != null && p != noUpscale.first())
        notes += "الجهاز مش بيدعم ${noUpscale.first().label} بـ ${outFps.toInt()} fps، فاستخدمنا ${p.label}."
    if (s.codec != Codec.H264 && mime == "video/avc" && chosen != null)
        notes += "الجهاز مش بيدعم ترميز H.265 بالمقاس ده، فاستخدمنا H.264 (الحجم هيبقى أكبر شوية)."
    if (s.codec == Codec.AUTO && mime == "video/hevc")
        notes += "H.265: نفس الجودة تقريبًا بحجم أصغر. لو مشغّل قديم مش بيشغّله، اختار H.264."
    if (p.w.toLong() * p.h * outFps > 3840L * 2160 * 30 * 1.01)
        notes += "جودة بالحجم ده تقيلة: التصدير ممكن ياخد وقت طويل ويسخّن الجهاز."

    // ---- معدل البت ----
    val dur = (s.trimEndMs - s.trimStartMs).coerceAtLeast(1)
    val audio = if (s.mute) 0 else 128_000
    val isHevc = mime == "video/hevc"
    val px = p.w.toDouble() * p.h
    val bppHigh = if (isHevc) 0.065 else 0.10   // بت لكل بكسل لكل إطار للجودة العالية
    var v = when (s.quality) {
        Quality.TIKTOK -> px * 30 * 0.13 * Math.pow((outFps / 30f).toDouble(), 0.75)   // ~8 Mbps لـ 1080p@30، ~13 لـ 60fps
        Quality.EFFICIENT -> px * outFps * bppHigh * 0.8
        Quality.HIGH -> px * outFps * bppHigh
        Quality.MEDIUM -> px * outFps * bppHigh * 0.7
        Quality.LOW -> px * outFps * bppHigh * 0.45
        Quality.CUSTOM -> s.customMbps * 1e6
        Quality.TARGET -> s.targetMb * 8.0 * 1024 * 1024 / (dur / 1000.0) - audio
    }
    // مفيش داعي نعدّي معدل بت المصدر (إلا لو المستخدم حدد يدويًا). مع H.265 من مصدر H.264 بنقلل أكتر.
    if (s.quality != Quality.CUSTOM && s.quality != Quality.TARGET) {
        info.bitrateBps?.takeIf { it > 0 }?.let { src ->
            val cap = if (s.quality == Quality.EFFICIENT && isHevc && info.mime == "video/avc") src * 0.75 else src.toDouble()
            v = min(v, cap)
        }
    }
    v = v.coerceIn(300_000.0, 60e6)
    val est = ((v + audio) * dur / 1000.0 / 8).toLong()
    if (p == Preset.P2160) notes += "TikTok بيوصي بـ 1080×1920 وبيعيد ضغط الفيديو عند الرفع، فـ 4K غالبًا مش هيبان فرقه وبيكبّر الحجم كتير."
    if (est > 287L * 1024 * 1024) notes += "الحجم المتوقع أكبر من حد الرفع المعروف في تطبيق TikTok (حوالي 287 MB، والحدود بتختلف حسب الإصدار والحساب). قلّل الجودة أو قصّ الفيديو."
    return Plan(p.w, p.h, v.toInt(), audio, est, dur, notes, mime, codecLabel, outFps, applyFps)
}

@UnstableApi
fun buildVideoEffects(info: VideoInfo, s: ExportSettings, plan: Plan, overlay: Bitmap? = null): List<Effect> {
    val list = mutableListOf<Effect>()
    if (s.rotation != 0) list += ScaleAndRotateTransformation.Builder().setRotationDegrees(s.rotation.toFloat()).build()
    val (sw, sh) = effSize(info, s.rotation)
    if (s.mode == FitMode.FILL) {
        val src = sw.toFloat() / sh
        val tgt = plan.outW.toFloat() / plan.outH
        if (abs(src - tgt) > 0.001f) {
            if (src > tgt) {            // أعرض من 9:16: قص أفقي
                val f = tgt / src
                val l = -1f + 2f * (1f - f) * s.cropPos
                list += Crop(l, l + 2f * f, -1f, 1f)
            } else {                    // أطول من 9:16: قص رأسي
                val f = src / tgt
                val t = 1f - 2f * (1f - f) * s.cropPos
                list += Crop(-1f, 1f, t - 2f * f, t)
            }
        }
    }
    list += Presentation.createForWidthAndHeight(plan.outW, plan.outH, Presentation.LAYOUT_SCALE_TO_FIT)
    // الطبقة بنفس مقاس الإطار الناتج بالظبط، وبتتحط بعد التحجيم عشان تتدمج في الفيديو نفسه
    if (overlay != null) list += OverlayEffect(ImmutableList.of<TextureOverlay>(
        BitmapOverlay.createStaticBitmapOverlay(overlay, OverlaySettings.Builder().build())))
    return list
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun outTree(c: Context): Uri? = sp(c).getString("out_tree", null)?.let(Uri::parse)
    fun setOutTree(c: Context, u: Uri) = sp(c).edit().putString("out_tree", u.toString()).apply()
    // مفتاح OpenAI بتاع المستخدم: بيتحفظ على الجهاز بس (النسخ الاحتياطي مقفول في الـ Manifest)
    fun openAiKey(c: Context): String = sp(c).getString("openai_key", "").orEmpty()
    fun setOpenAiKey(c: Context, k: String) = sp(c).edit().putString("openai_key", k.trim()).apply()
}

object AppLog {
    fun e(c: Context, msg: String, t: Throwable? = null) {
        runCatching { File(c.filesDir, "errors.log").appendText("${Date()} $msg ${t?.stackTraceToString() ?: ""}\n") }
    }
}
