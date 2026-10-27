package com.tiktokstudio.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,      // بعد تطبيق التدوير
    val height: Int,
    val fps: Float?,
    val mime: String?,
    val bitrateBps: Long?,
    val rotation: Int,
    val hasAudioGuess: Boolean = true,
) {
    val isPortrait get() = height > width
    val resolution get() = "${width}×${height}"
    val fpsText get() = fps?.let { String.format(Locale.US, "%.0f fps", it) } ?: "fps غير معروف"
    val sizeText get() = formatSize(sizeBytes)
    val durationText get() = formatDuration(durationMs)
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", b / 1024)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** يقرأ معلومات الفيديو بدون تحميله في الذاكرة. يرجع null لو الملف مش فيديو صالح. */
fun readVideoInfo(ctx: Context, uri: Uri): VideoInfo? {
    var name = "video"
    var size = 0L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
        }
    }
    val ex = MediaExtractor()
    try {
        ex.setDataSource(ctx, uri, null)
        val hasAudio = (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                    .getOrElse { runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull() }
            } else null
            val br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                     else if (durUs > 0 && size > 0) size * 8 * 1_000_000 / durUs else null
            return VideoInfo(uri, name, durUs / 1000, size, w, h, fps, mime, br, rot, hasAudio)
        }
        return null
    } catch (e: Exception) {
        return null
    } finally {
        ex.release()
    }
}
