package com.tiktokstudio.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Segment(val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
}

object SilenceDetector {
    const val FRAME_MS = 20

    /** فك الصوت وتحليله ثم تحديد المقاطع اللي فيها كلام. sensitivity: 0 (يقص أقل) .. 1 (يقص أكتر). */
    fun detect(
        context: Context, uri: Uri, startMs: Long, endMs: Long,
        sensitivity: Float = 0.5f, minSilenceMs: Long = 350, padMs: Long = 90, minSpeechMs: Long = 200,
        stillActive: () -> Boolean = { true },
    ): List<Segment> {
        val db = decodeLevels(context, uri, startMs, endMs, stillActive)
            ?: return listOf(Segment(startMs, endMs))
        return plan(db, startMs, endMs, sensitivity, minSilenceMs, padMs, minSpeechMs)
    }

    /** دالة نقية (بدون أندرويد) عشان تتختبر: تحوّل مستويات الصوت (dB لكل 20ms) إلى مقاطع. */
    fun plan(
        db: FloatArray, startMs: Long, endMs: Long, sensitivity: Float,
        minSilenceMs: Long, padMs: Long, minSpeechMs: Long,
    ): List<Segment> {
        val whole = listOf(Segment(startMs, endMs))
        if (db.isEmpty()) return whole

        // عتبة تتأقلم مع الفيديو: أرضية الضوضاء + هامش، وماتعديش مستوى الكلام
        val sorted = db.sortedArray()
        val floor = sorted[(sorted.size * 0.10).toInt()]
        val speech = sorted[min(sorted.size - 1, (sorted.size * 0.90).toInt())]
        val margin = 6f + 14f * sensitivity.coerceIn(0f, 1f)
        val threshold = min(floor + margin, speech - 8f)

        // مقاطع الكلام الخام (فهارس فريمات)
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < db.size) {
            if (db[i] > threshold) {
                var j = i
                while (j + 1 < db.size && db[j + 1] > threshold) j++
                runs.add(intArrayOf(i, j)); i = j + 1
            } else i++
        }
        if (runs.isEmpty()) return whole

        // دمج المقاطع اللي بينها سكتة أقصر من minSilence (فواصل الكلام الطبيعية)
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && (r[0] - last[1] - 1) * FRAME_MS < minSilenceMs) last[1] = r[1]
            else merged.add(r.copyOf())
        }

        // تحويل لميلي ثانية + حشو حوالين الكلام + شيل المقاطع القصيرة + دمج المتداخل
        val out = ArrayList<Segment>()
        for (r in merged) {
            val s = max(startMs, startMs + r[0] * FRAME_MS - padMs)
            val e = min(endMs, startMs + (r[1] + 1) * FRAME_MS + padMs)
            if (e - s < minSpeechMs) continue
            val last = out.lastOrNull()
            if (last != null && s <= last.endMs) out[out.size - 1] = Segment(last.startMs, e)
            else out.add(Segment(s, e))
        }
        return out.ifEmpty { whole }
    }

    /** يفك الصوت لـ PCM ويرجّع مستوى كل 20ms بالـ dB (نسبة لـ full-scale). */
    private fun decodeLevels(
        context: Context, uri: Uri, startMs: Long, endMs: Long, stillActive: () -> Boolean,
    ): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0); start()
            }
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val n = ((endMs - startMs) / FRAME_MS).toInt() + 2
            val sum = DoubleArray(n)
            val cnt = IntArray(n)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!stillActive()) return null
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (size < 0 || extractor.sampleTime > endMs * 1000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    oi >= 0 -> {
                        val out = codec.getOutputBuffer(oi)!!
                        out.position(info.offset).limit(info.offset + info.size)
                        val pcm = out.order(ByteOrder.nativeOrder()).asShortBuffer() // PCM 16-bit
                        val frames = pcm.remaining() / channels
                        for (f in 0 until frames) {
                            val tMs = (info.presentationTimeUs + f * 1_000_000L / sampleRate) / 1000
                            if (tMs < startMs) continue
                            if (tMs >= endMs) break
                            var m = 0.0
                            for (c in 0 until channels) m += pcm.get(f * channels + c)
                            m /= channels
                            val idx = ((tMs - startMs) / FRAME_MS).toInt()
                            sum[idx] += m * m; cnt[idx]++
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return FloatArray(n) { k ->
                if (cnt[k] == 0) -100f
                else (20 * log10(sqrt(sum[k] / cnt[k]) / 32768.0 + 1e-9)).toFloat()
            }
        } catch (e: Exception) {
            return null // أي مشكلة في الفك → نكمل من غير قص سكتات
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
