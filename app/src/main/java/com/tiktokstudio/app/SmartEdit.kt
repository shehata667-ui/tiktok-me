package com.tiktokstudio.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** نتيجة القص الذكي: المقاطع اللي هتتحفظ + الكابشن (بتوقيت الفيديو النهائي) + ملاحظات للمستخدم. */
data class SmartPlan(val segments: List<Segment>, val cues: List<Cue>, val notes: List<String>) {
    val totalMs: Long get() = segments.sumOf { it.durationMs }
}

object SmartEdit {

    /** لازم يتنادى على الـ Main thread (Transformer بيحتاج كده)، والشغل التقيل بينتقل لخيوط تانية. */
    suspend fun prepare(ctx: Context, info: VideoInfo, s: ExportSettings, onStage: (String) -> Unit): SmartPlan {
        val start = s.trimStartMs
        val end = if (s.trimEndMs <= 0) info.durationMs else minOf(s.trimEndMs, info.durationMs)
        val notes = ArrayList<String>()
        val audioOk = !s.mute && info.hasAudioGuess
        var segs = listOf(Segment(start, end))

        if ((s.cutSilence || s.captions) && !audioOk)
            notes += "القص الذكي والكابشن اتخطوا: الفيديو من غير صوت أو الصوت مكتوم."

        if (s.cutSilence && audioOk) {
            onStage("جاري تحليل الصوت لقص السكتات...")
            segs = withContext(Dispatchers.Default) {
                SilenceDetector.detect(ctx, info.uri, start, end, s.silenceSens, stillActive = { isActive })
            }
            if (segs.size == 1 && segs[0].startMs == start && segs[0].endMs == end)
                notes += "ماتلقاش سكتات تتقص (أو ماقدرش يحلل الصوت)، فالمدة زي ما هي."
        }

        var cues = emptyList<Cue>()
        if (s.captions && audioOk) {
            val key = Prefs.openAiKey(ctx)
            if (key.isBlank()) notes += "الكابشن اتخطى: مفيش مفتاح OpenAI (أضفه من شاشة تجهيز الفيديو)."
            else {
                onStage("جاري كتابة الكابشن...")
                try {
                    val audio = extractAudio(ctx, info.uri, segs)
                    try {
                        cues = withContext(Dispatchers.IO) { CaptionApi.transcribe(audio, key) }
                        if (!s.highlight) cues = cues.map { it.copy(words = emptyList()) }
                        if (cues.isEmpty()) notes += "الكابشن: ماتعرفش على كلام في الفيديو."
                    } finally { audio.delete() }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    AppLog.e(ctx, "captions failed", e)
                    notes += "الكابشن اتخطى: ${e.message}"
                }
            }
        }
        return SmartPlan(segs, cues, notes)
    }

    /** صوت فقط بنفس المقاطع، فتوقيت الكابشن يطلع مطابق للفيديو النهائي. */
    private suspend fun extractAudio(ctx: Context, uri: Uri, segs: List<Segment>): File =
        suspendCancellableCoroutine { cont ->
            val out = File(ctx.cacheDir, "job_audio_${System.nanoTime()}.m4a")  // بادئة job_ عشان تتنضف عند الإقلاع
            val items = segs.map { seg ->
                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.startMs).setEndPositionMs(seg.endMs).build()
                EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build())
                    .setRemoveVideo(true).build()
            }
            val t = Transformer.Builder(ctx).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(out) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete(); if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }).build()
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { runCatching { t.cancel() }; out.delete() } }
            t.start(Composition.Builder(EditedMediaItemSequence.Builder(*items.toTypedArray()).build()).build(), out.absolutePath)
        }
}
