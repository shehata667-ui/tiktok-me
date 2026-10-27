package com.tiktokstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.resume

sealed interface Outcome {
    data class Success(val uri: Uri, val name: String, val bytes: Long, val report: VerifyReport) : Outcome
    data class Fail(val msg: String) : Outcome
}

/** تصدير فيديو واحد. بيتنفّذ على الخيط الرئيسي (Transformer لازم كده) وبيتلغي بإلغاء الـ coroutine. */
@OptIn(UnstableApi::class)
object Exporter {
    suspend fun export(ctx: Context, job: ExportJob, onProgress: (Float, Long, Long?, String?) -> Unit): Outcome = coroutineScope {
        val info = job.toInfo(); val s = job.settings; val wm = job.wm
        val plan = makePlan(info, s)
        val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(job.outTree))
        if (dir == null || !dir.canWrite()) return@coroutineScope Outcome.Fail("مجلد الحفظ مش متاح. اختار مجلد تاني من الإعدادات.")
        val needed = (plan.estBytes * 1.3).toLong() + 50L * 1024 * 1024
        if (ctx.cacheDir.usableSpace < needed) return@coroutineScope Outcome.Fail("المساحة غير كافية. محتاج حوالي ${formatSize(needed)} فاضية.")
        val out = File(ctx.cacheDir, "job_${job.id}.mp4")
        var poll: Job? = null
        try {
            val overlay = if (wm.hasContent) renderOverlay(ctx, plan.outW, plan.outH, wm) else null
            // القص الذكي والكابشن (لو متفعّلين). المقاطع بتتحسب جوه المدى اللي القص الزمني حدده.
            val smart = SmartEdit.prepare(ctx, info, s) { onProgress(0f, 0L, null, it) }
            val items = smart.segments.map { seg ->
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(seg.startMs).apply {
                    if (seg.endMs < info.durationMs - 100) setEndPositionMs(seg.endMs)
                }.build()
                EditedMediaItem.Builder(MediaItem.Builder().setUri(info.uri).setClippingConfiguration(clip).build())
                    .setRemoveAudio(s.mute)
                    .setEffects(Effects(emptyList(), buildVideoEffects(info, s, plan, overlay)))
                    .apply { if (plan.applyFps > 0) setFrameRate(plan.applyFps) }
                    .build()
            }
            // الكابشن والهوك على مستوى الـ Composition، فتوقيتهم على الفيديو النهائي بعد القص
            val textOverlays = ImmutableList.builder<TextureOverlay>().apply {
                if (smart.cues.isNotEmpty()) add(CaptionOverlay(smart.cues))
                if (s.hookText.isNotBlank()) add(HookOverlay(HookSpec(s.hookText.trim(), s.hookSec)))
            }.build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(*items.toTypedArray()).build()).apply {
                if (textOverlays.isNotEmpty()) setEffects(Effects(emptyList(), listOf(OverlayEffect(textOverlays))))
            }.build()
            val enc = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBps).build())
                .setEnableFallback(true).build()
            val t0 = SystemClock.elapsedRealtime()
            val err = suspendCancellableCoroutine<ExportException?> { cont ->
                val t = Transformer.Builder(ctx)
                    .setVideoMimeType(plan.mime).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(enc)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(null) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(exportException) }
                    }).build()
                cont.invokeOnCancellation { t.cancel() }
                t.start(composition, out.absolutePath)
                poll = launch {
                    val h = ProgressHolder(); var last = 0f
                    while (isActive) {
                        val el = SystemClock.elapsedRealtime() - t0
                        if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) last = h.progress / 100f
                        onProgress(last, el, if (last > 0.03f) (el * (1 - last) / last).toLong() else null, warning(ctx))
                        delay(400)
                    }
                }
            }
            poll?.cancel()
            if (err != null) {
                AppLog.e(ctx, "export failed: ${err.errorCodeName}", err)
                return@coroutineScope Outcome.Fail(describe(err))
            }
            onProgress(1f, SystemClock.elapsedRealtime() - t0, 0, "جاري فحص الملف وحفظه...")
            if (s.stripMeta) withContext(Dispatchers.IO) { runCatching { stripLocation(out.absolutePath) }.onFailure { AppLog.e(ctx, "strip location failed", it) } }
            val expFps: Float? = plan.outFps
            val report = withContext(Dispatchers.IO) {
                verifyOutput(out.absolutePath, smart.totalMs, !s.mute && info.hasAudioGuess, plan.outW, plan.outH, expFps, s.stripMeta)
            }
            val doc = withContext(Dispatchers.IO) {
                val d = dir.createFile("video/mp4", info.name.substringBeforeLast('.') + "_tiktok.mp4")
                    ?: throw IOException("تعذّر إنشاء الملف في المجلد")
                try {
                    ctx.contentResolver.openOutputStream(d.uri)!!.use { o ->
                        FileInputStream(out).use { i ->
                            val buf = ByteArray(1 shl 20)
                            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); ensureActive() }
                        }
                    }
                } catch (e: Exception) { d.delete(); throw e }
                d
            }
            Outcome.Success(doc.uri, doc.name ?: "video.mp4", out.length(), report.copy(warnings = report.warnings + smart.notes))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            AppLog.e(ctx, "export exception", e)
            Outcome.Fail("فشل: ${e.message} (ممكن يكون السبب نقص مساحة التخزين)")
        } finally { poll?.cancel(); out.delete() }
    }

    private fun warning(ctx: Context): String? {
        if (Build.VERSION.SDK_INT >= 29 &&
            ctx.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            return "حرارة الجهاز مرتفعة؛ ممكن المعالجة تبطأ أو تتقطع. سيبه يبرد لو أمكن."
        if (ctx.cacheDir.usableSpace < 200L * 1024 * 1024) return "المساحة الفاضية قاربت تخلص."
        return null
    }

    private fun describe(e: ExportException): String {
        val n = e.errorCodeName
        return when {
            n.contains("ENCODER") || n.contains("ENCODING") -> "فشل ترميز الفيديو على الجهاز (الإعدادات مش مدعومة). جرّب مقاس أو جودة أقل. ($n)"
            n.contains("DECOD") -> "الجهاز مش قادر يفك ترميز الفيديو المصدر. ($n)"
            n.contains("IO") -> "مشكلة في قراءة أو كتابة الملف (المساحة أو الصلاحيات). ($n)"
            else -> "فشل التصدير ($n)"
        }
    }
}
