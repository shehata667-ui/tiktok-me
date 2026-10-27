package com.tiktokstudio.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Word(val startMs: Long, val endMs: Long, val text: String)

/** كابشن واحد، الأوقات على timeline الفيديو النهائي (بعد القص). words فاضية = من غير تمييز كلمات. */
data class Cue(val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList())

data class Seg(val startMs: Long, val endMs: Long, val text: String)

/** تقسيم الجمل الطويلة لعبارات قصيرة (≤ maxWords) بتوزيع الوقت حسب عدد الحروف. بديل لو مفيش توقيت كلمات. */
object CueSplitter {
    fun split(startMs: Long, endMs: Long, text: String, maxWords: Int = 5): List<Cue> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val chunks = words.chunked(maxWords).map { it.joinToString(" ") }
        val total = chunks.sumOf { it.length }.toDouble()
        var cursor = startMs
        var acc = 0
        return chunks.mapIndexed { i, c ->
            acc += c.length
            val end = if (i == chunks.lastIndex) endMs else startMs + ((endMs - startMs) * acc / total).toLong()
            Cue(cursor, end, c).also { cursor = end }
        }
    }
}

/** بيبني الكابشنات من جمل Whisper + توقيت كل كلمة. دالة نقية. */
object CueBuilder {
    fun build(segs: List<Seg>, words: List<Word>, maxWords: Int = 5): List<Cue> {
        val out = ArrayList<Cue>()
        var p = 0
        for (s in segs) {
            // كل كلمة بتتوزع على جملة واحدة بس (مؤشر يتحرك للأمام)، فمفيش تكرار
            val taken = ArrayList<Word>()
            while (p < words.size && words[p].startMs < s.endMs) {
                if (words[p].text.isNotBlank()) taken += words[p]
                p++
            }
            if (taken.isEmpty()) { out += CueSplitter.split(s.startMs, s.endMs, s.text, maxWords); continue }
            taken.chunked(maxWords).forEach { ch ->
                out += Cue(ch.first().startMs, ch.last().endMs, ch.joinToString(" ") { it.text }, ch)
            }
        }
        // مدّ نهاية كل كابشن لحد بداية اللي بعده (بحد أقصى 400ms) عشان مايبقاش في وميض فاضي بين العبارات
        return out.mapIndexed { i, c ->
            val next = out.getOrNull(i + 1)?.startMs
            if (next != null && next > c.endMs) c.copy(endMs = minOf(next, c.endMs + 400)) else c
        }
    }
}

/** بيكلّم OpenAI مباشرة بمفتاح المستخدم (من غير سيرفر وسيط). الصوت المقصوص بس هو اللي بيتبعت. */
object CaptionApi {
    fun transcribe(audio: File, apiKey: String, lang: String = "ar"): List<Cue> {
        if (apiKey.isBlank()) error("أضف مفتاح OpenAI الأول (من إعدادات الكابشن في الشاشة الرئيسية)")
        val boundary = "----TikEdit${System.nanoTime()}"
        val conn = URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(0)
            conn.outputStream.use { out ->
                fun field(name: String, value: String) =
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                field("model", "whisper-1")
                field("response_format", "verbose_json")
                field("language", lang)
                field("timestamp_granularities[]", "word")     // لتمييز الكلمة الحالية
                field("timestamp_granularities[]", "segment")
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\n" +
                    "Content-Type: audio/mp4\r\n\r\n").toByteArray())
                audio.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401 -> error("المفتاح غلط أو منتهي")
                429 -> error("الرصيد خلص أو الطلبات كتير")
                else -> error("الخدمة رجّعت $code")
            }
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            val sa = root.getJSONArray("segments")
            val segs = (0 until sa.length()).map {
                val o = sa.getJSONObject(it)
                Seg((o.getDouble("start") * 1000).toLong(), (o.getDouble("end") * 1000).toLong(), o.getString("text"))
            }
            val wa = root.optJSONArray("words")
            val words = if (wa == null) emptyList() else (0 until wa.length()).map {
                val o = wa.getJSONObject(it)
                Word((o.getDouble("start") * 1000).toLong(), (o.getDouble("end") * 1000).toLong(), o.getString("word").trim())
            }
            return CueBuilder.build(segs, words)
        } finally { conn.disconnect() }
    }
}

/** ظل أسود ناعم حوالين الحروف (بديل الخلفية) عشان الكلام يفضل مقروء على الفيديوهات الفاتحة. */
class ShadowSpan : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) { tp.setShadowLayer(9f, 0f, 2f, Color.BLACK) }
}

/** بيحرق الكابشن: أبيض عريض من غير خلفية بظل غامق، في الثلث السفلي، والكلمة الحالية بلون مميز. */
class CaptionOverlay(private val cues: List<Cue>) : TextOverlay() {
    private val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.35f).build()
    private val blank = SpannableString(" ")
    // نفس النص لنفس الحالة بيرجع نفس الـ instance، فالـ Bitmap مابيتعاد رسمه كل فريم
    private val cache = HashMap<Int, SpannableString>()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val tMs = presentationTimeUs / 1000
        val ci = cues.indexOfFirst { tMs >= it.startMs && tMs < it.endMs }
        if (ci < 0) return blank
        val cue = cues[ci]
        val active = if (cue.words.isEmpty()) -1 else cue.words.indexOfLast { it.startMs <= tMs }.coerceAtLeast(0)
        return cache.getOrPut(ci * 64 + active + 1) { render(cue, active) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun render(cue: Cue, active: Int): SpannableString {
        val wrapped = wrapText(cue.text, 22)
        val s = SpannableString(wrapped)
        val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(AbsoluteSizeSpan(58), 0, s.length, f)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, f)
        s.setSpan(ShadowSpan(), 0, s.length, f) // من غير خلفية: ظل غامق حوالين الحروف عشان يتقري على أي فيديو

        // مدى الكلمة الحالية جوه النص بعد التفاف الأسطر
        var hs = -1; var he = -1
        if (active >= 0) {
            var from = 0
            cue.words.forEachIndexed { i, w ->
                val at = wrapped.indexOf(w.text, from)
                if (at >= 0) { if (i == active) { hs = at; he = at + w.text.length }; from = at + w.text.length }
            }
        }
        if (hs < 0) s.setSpan(ForegroundColorSpan(Color.WHITE), 0, s.length, f)
        else {
            if (hs > 0) s.setSpan(ForegroundColorSpan(Color.WHITE), 0, hs, f)
            s.setSpan(ForegroundColorSpan(0xFF2DE2C4.toInt()), hs, he, f)
            if (he < s.length) s.setSpan(ForegroundColorSpan(Color.WHITE), he, s.length, f)
        }
        return s
    }
}

fun wrapText(text: String, maxChars: Int): String {
    val sb = StringBuilder(); var line = 0
    for (w in text.split(" ")) {
        if (line > 0 && line + 1 + w.length > maxChars) { sb.append('\n'); line = 0 }
        if (line > 0) { sb.append(' '); line++ }
        sb.append(w); line += w.length
    }
    return sb.toString()
}

data class HookSpec(val text: String, val seconds: Float)

/** منحنى ظهور الهوك: pop-in (تكبير من 85% لـ100% + fade) في أول 250ms، وfade-out في آخر 250ms. دالة نقية. */
object HookAnim {
    private const val EDGE_MS = 250f
    /** يرجّع (alpha, scale) عند الوقت tMs من إجمالي totalMs. */
    fun at(tMs: Long, totalMs: Long): Pair<Float, Float> {
        if (tMs < 0 || tMs >= totalMs) return 0f to 1f
        val inP = (tMs / EDGE_MS).coerceIn(0f, 1f)
        val outP = ((totalMs - tMs) / EDGE_MS).coerceIn(0f, 1f)
        return minOf(inP, outP) to (0.85f + 0.15f * inP)
    }
}

/** نص الهوك في أول ثواني الفيديو النهائي: خط كبير غامق على خلفية بلون التطبيق في الثلث العلوي. */
class HookOverlay(private val spec: HookSpec) : TextOverlay() {
    private val totalMs = (spec.seconds * 1000).toLong()
    private val blank = SpannableString(" ")
    private val shown: SpannableString by lazy {
        SpannableString(wrapText(spec.text, 16)).also {
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            it.setSpan(AbsoluteSizeSpan(74), 0, it.length, f)
            it.setSpan(ForegroundColorSpan(0xFF00201A.toInt()), 0, it.length, f)
            it.setSpan(StyleSpan(Typeface.BOLD), 0, it.length, f)
            it.setSpan(BackgroundColorSpan(0xFF2DE2C4.toInt()), 0, it.length, f)
        }
    }

    override fun getText(presentationTimeUs: Long): SpannableString =
        if (presentationTimeUs / 1000 >= totalMs) blank else shown

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val (alpha, scale) = HookAnim.at(presentationTimeUs / 1000, totalMs)
        return OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.35f)
            .setAlphaScale(alpha).setScale(scale, scale).build()
    }
}
