package com.tiktokstudio.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class ExportJob(
    val id: String, val srcUri: String, val name: String, val durationMs: Long, val sizeBytes: Long,
    val width: Int, val height: Int, val fps: Float?, val bitrateBps: Long?, val rotation: Int, val hasAudio: Boolean,
    val settings: ExportSettings, val wm: Watermark, val outTree: String,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f, val elapsedMs: Long = 0, val etaMs: Long? = null,
    val message: String? = null, val warning: String? = null,
    val outUri: String? = null, val outName: String? = null, val outBytes: Long = 0,
    val summary: String? = null, val warnings: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toInfo() = VideoInfo(Uri.parse(srcUri), name, durationMs, sizeBytes, width, height, fps, null, bitrateBps, rotation, hasAudio)

    fun toJson() = JSONObject().apply {
        put("id", id); put("srcUri", srcUri); put("name", name); put("durationMs", durationMs); put("sizeBytes", sizeBytes)
        put("width", width); put("height", height); put("fps", fps?.toDouble() ?: JSONObject.NULL)
        put("bitrateBps", bitrateBps ?: JSONObject.NULL); put("rotation", rotation); put("hasAudio", hasAudio)
        put("settings", settings.toJson()); put("wm", wm.toJson()); put("outTree", outTree)
        put("status", status.name); put("message", message ?: JSONObject.NULL)
        put("outUri", outUri ?: JSONObject.NULL); put("outName", outName ?: JSONObject.NULL); put("outBytes", outBytes)
        put("summary", summary ?: JSONObject.NULL); put("warnings", JSONArray(warnings)); put("createdAt", createdAt)
    }

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
        fun fromJson(o: JSONObject) = ExportJob(
            o.getString("id"), o.getString("srcUri"), o.getString("name"), o.getLong("durationMs"), o.getLong("sizeBytes"),
            o.getInt("width"), o.getInt("height"),
            if (o.isNull("fps")) null else o.getDouble("fps").toFloat(),
            if (o.isNull("bitrateBps")) null else o.getLong("bitrateBps"),
            o.getInt("rotation"), o.getBoolean("hasAudio"),
            settingsFromJson(o.getJSONObject("settings")), Watermark.fromJson(o.getJSONObject("wm")), o.getString("outTree"),
            JobStatus.valueOf(o.getString("status")), message = o.str("message"),
            outUri = o.str("outUri"), outName = o.str("outName"), outBytes = o.optLong("outBytes"),
            summary = o.str("summary"),
            warnings = o.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            createdAt = o.optLong("createdAt"),
        )
    }
}

fun ExportSettings.toJson() = JSONObject().apply {
    put("preset", preset.name); put("mode", mode.name); put("cropPos", cropPos.toDouble()); put("rotation", rotation)
    put("quality", quality.name); put("codec", codec.name); put("customMbps", customMbps.toDouble()); put("targetMb", targetMb)
    put("fpsCap", fpsCap); put("cfrFps", cfrFps); put("stripMeta", stripMeta); put("trimStartMs", trimStartMs); put("trimEndMs", trimEndMs); put("mute", mute)
    put("cutSilence", cutSilence); put("silenceSens", silenceSens.toDouble()); put("captions", captions)
    put("highlight", highlight); put("hookText", hookText); put("hookSec", hookSec.toDouble())
}

fun settingsFromJson(o: JSONObject) = ExportSettings(
    preset = runCatching { Preset.valueOf(o.getString("preset")) }.getOrDefault(Preset.P1080),
    mode = runCatching { FitMode.valueOf(o.getString("mode")) }.getOrDefault(FitMode.FILL),
    cropPos = o.optDouble("cropPos", 0.5).toFloat(), rotation = o.optInt("rotation"),
    quality = runCatching { Quality.valueOf(o.getString("quality")) }.getOrDefault(Quality.HIGH),
    codec = runCatching { Codec.valueOf(o.getString("codec")) }.getOrDefault(Codec.AUTO),
    customMbps = o.optDouble("customMbps", 8.0).toFloat(), targetMb = o.optInt("targetMb", 30),
    fpsCap = o.optInt("fpsCap"), cfrFps = o.optInt("cfrFps"), stripMeta = o.optBoolean("stripMeta", true), trimStartMs = o.optLong("trimStartMs"), trimEndMs = o.optLong("trimEndMs"),
    mute = o.optBoolean("mute"),
    cutSilence = o.optBoolean("cutSilence"), silenceSens = o.optDouble("silenceSens", 0.5).toFloat(),
    captions = o.optBoolean("captions"), highlight = o.optBoolean("highlight", true),
    hookText = o.optString("hookText", ""), hookSec = o.optDouble("hookSec", 3.0).toFloat(),
)
