package com.tiktokstudio.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class VerifyReport(
    val durationMs: Long, val width: Int, val height: Int, val frames: Int, val avgFps: Float,
    val hasAudio: Boolean, val playable: Boolean, val warnings: List<String>,
    val hasLocation: Boolean = false,
)

/** يفتح الملف الناتج فعليًا ويفحص: المدة، الأبعاد، عدد الإطارات، انتظام التوقيت، الصوت، وفك إطار للتأكد من التشغيل. */
fun verifyOutput(path: String, expectedMs: Long, expectAudio: Boolean, expW: Int, expH: Int, expFps: Float?, expectNoLocation: Boolean = false): VerifyReport {
    val warns = mutableListOf<String>()
    var w = 0; var h = 0; var durUs = 0L; var audio = false
    var frames = 0; var maxGapUs = 0L; var lastT = -1L
    val ex = MediaExtractor()
    try {
        ex.setDataSource(path)
        var vIdx = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/") && vIdx < 0) {
                vIdx = i; w = f.getInteger(MediaFormat.KEY_WIDTH); h = f.getInteger(MediaFormat.KEY_HEIGHT)
                if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = f.getLong(MediaFormat.KEY_DURATION)
            } else if (m.startsWith("audio/")) audio = true
        }
        if (vIdx < 0) return VerifyReport(0, 0, 0, 0, 0f, audio, false, listOf("الملف الناتج مفيهوش مسار فيديو."))
        ex.selectTrack(vIdx)
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            if (lastT >= 0) maxGapUs = max(maxGapUs, abs(t - lastT))
            lastT = t; frames++
            if (!ex.advance()) break
        }
    } catch (e: Exception) {
        return VerifyReport(0, 0, 0, 0, 0f, false, false, listOf("تعذّر فتح الملف الناتج: ${e.message}"))
    } finally { ex.release() }

    val durMs = durUs / 1000
    val avgFps = if (durUs > 0) frames * 1_000_000f / durUs else 0f

    var playable = false
    var hasLoc = false
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        val bmp = r.getFrameAtTime(durUs / 2, MediaMetadataRetriever.OPTION_CLOSEST)
        playable = bmp != null; bmp?.recycle()
        hasLoc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null
    } catch (_: Exception) {} finally { r.release() }

    if (!playable) warns += "الملف اتعمل بس فشل فك إطار منه؛ ممكن يكون فيه مشكلة تشغيل."
    if (abs(durMs - expectedMs) > max(500.0, expectedMs * 0.03)) warns += "مدة الناتج (${formatDuration(durMs)}) مختلفة عن المتوقعة (${formatDuration(expectedMs)})."
    if (expectAudio && !audio) warns += "الصوت مش موجود في الناتج رغم إنه كان في المصدر."
    if (w != expW || h != expH) warns += "الأبعاد الناتجة ${w}×${h} مختلفة عن المطلوبة ${expW}×${expH} (المشفّر عدّلها تلقائيًا)."
    if (expFps != null && expFps > 0 && (avgFps < expFps * 0.9f || avgFps > expFps * 1.1f))
        warns += String.format(Locale.US, "معدل الإطارات الناتج %.1f مختلف عن المتوقع %.0f؛ ممكن إطارات اتسقطت أو اتكررت.", avgFps, expFps)
    if (avgFps > 0 && maxGapUs > 4_000_000f / avgFps) warns += "فيه فجوة زمنية بين إطارات (أكبر فجوة ${maxGapUs / 1000}ms)؛ ممكن تظهر كتقطيع."
    if (expectNoLocation && hasLoc) warns += "بيانات الموقع لسه موجودة في الناتج (تعذّر مسحها بالكامل)."
    return VerifyReport(durMs, w, h, frames, avgFps, audio, playable, warns, hasLoc)
}
