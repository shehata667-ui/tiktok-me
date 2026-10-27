package com.tiktokstudio.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.Job as CJob
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.UUID

/** طابور المعالجة: بيتحفظ على القرص، وبيشتغل بالتتابع، وكل التعديلات على الخيط الرئيسي. */
object QueueManager {
    val jobs = mutableStateListOf<ExportJob>()
    var running by mutableStateOf(false); private set
    var interruptedCount by mutableStateOf(0); private set
    var notify: ((String, Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private var inited = false
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: CJob? = null
    private var currentJob: CJob? = null

    private fun file() = File(app.filesDir, "jobs.json")

    fun init(ctx: Context) {
        if (inited) return
        inited = true; app = ctx.applicationContext
        runCatching {
            val f = file()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    var j = ExportJob.fromJson(a.getJSONObject(i))
                    if (j.status == JobStatus.RUNNING) {   // التطبيق اتقفل أثناء التصدير: هتبدأ من الأول
                        j = j.copy(status = JobStatus.QUEUED, progress = 0f, message = "اتقطعت بعد غلق التطبيق وهتبدأ من الأول")
                        interruptedCount++
                    }
                    jobs.add(j)
                }
            }
        }.onFailure { AppLog.e(app, "load jobs failed", it) }
        app.cacheDir.listFiles()?.filter { it.name.startsWith("job_") }?.forEach { it.delete() }  // تنظيف مؤقتات قديمة
        save()
    }

    private fun save() {
        runCatching {
            val a = JSONArray(); jobs.forEach { a.put(it.toJson()) }
            val f = file(); val t = File(f.path + ".tmp"); t.writeText(a.toString()); t.renameTo(f)
        }.onFailure { AppLog.e(app, "save jobs failed", it) }
    }

    private fun upd(id: String, persist: Boolean = false, f: (ExportJob) -> ExportJob) {
        val i = jobs.indexOfFirst { it.id == id }
        if (i >= 0) { jobs[i] = f(jobs[i]); if (persist) save() }
    }

    fun enqueue(v: VideoInfo, s: ExportSettings, wm: Watermark, tree: Uri): ExportJob {
        val j = ExportJob(UUID.randomUUID().toString().take(8), v.uri.toString(), v.name, v.durationMs, v.sizeBytes,
            v.width, v.height, v.fps, v.bitrateBps, v.rotation, v.hasAudioGuess, s, wm, tree.toString())
        jobs.add(j); save(); return j
    }

    fun start() {
        if (running) return
        running = true; interruptedCount = 0
        runJob = scope.launch {
            try {
                while (true) {
                    val j = jobs.firstOrNull { it.status == JobStatus.QUEUED } ?: break
                    runOne(j)
                }
            } finally { running = false; onFinished?.invoke() }
        }
    }

    private suspend fun runOne(j: ExportJob) {
        upd(j.id, true) { it.copy(status = JobStatus.RUNNING, progress = 0f, elapsedMs = 0, etaMs = null, message = null, warning = null) }
        val c = scope.launch {
            val outcome = try {
                Exporter.export(app, j) { p, el, eta, warn ->
                    upd(j.id) { it.copy(progress = p, elapsedMs = el, etaMs = eta, warning = warn, message = if (p >= 1f) "جاري فحص الملف وحفظه..." else null) }
                    val left = jobs.count { it.status == JobStatus.QUEUED }
                    notify?.invoke("${j.name} • ${(p * 100).toInt()}%" + if (left > 0) " • متبقي $left" else "", (p * 100).toInt())
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { AppLog.e(app, "job crashed", e); Outcome.Fail("خطأ غير متوقع: ${e.message}") }
            when (outcome) {
                is Outcome.Success -> {
                    val r = outcome.report
                    val sum = String.format(Locale.US, "%d×%d • %s • %.1f fps • %d إطار • %s (الأصل %s) • الصوت %s",
                        r.width, r.height, formatDuration(r.durationMs), r.avgFps, r.frames, formatSize(outcome.bytes),
                        formatSize(j.sizeBytes), if (r.hasAudio) "موجود" else "غير موجود")
                    upd(j.id, true) { it.copy(status = JobStatus.DONE, progress = 1f, etaMs = null, message = null, warning = null,
                        outUri = outcome.uri.toString(), outName = outcome.name, outBytes = outcome.bytes, summary = sum + (if (j.settings.stripMeta) (if (r.hasLocation) " • الموقع لسه موجود" else " • الموقع ممسوح ✓") else ""), warnings = r.warnings) }
                }
                is Outcome.Fail -> upd(j.id, true) { it.copy(status = JobStatus.FAILED, message = outcome.msg, warning = null, etaMs = null) }
            }
        }
        currentJob = c
        try { c.join() } finally { withContext(NonCancellable) { c.cancelAndJoin() } }
    }

    fun cancel(id: String) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        upd(id, true) { it.copy(status = JobStatus.CANCELLED, message = "اتلغت", warning = null, etaMs = null) }
        if (j.status == JobStatus.RUNNING) currentJob?.cancel()
    }

    fun cancelAll() {
        for (j in jobs.toList()) if (j.status == JobStatus.QUEUED || j.status == JobStatus.RUNNING)
            upd(j.id) { it.copy(status = JobStatus.CANCELLED, message = "اتلغت", warning = null, etaMs = null) }
        save()
        currentJob?.cancel(); runJob?.cancel()
    }

    fun retry(id: String) = upd(id, true) { it.copy(status = JobStatus.QUEUED, progress = 0f, message = null, warning = null, summary = null, warnings = emptyList()) }

    fun remove(id: String) { val i = jobs.indexOfFirst { it.id == id && it.status != JobStatus.RUNNING }; if (i >= 0) { jobs.removeAt(i); save() } }

    fun clearFinished() { jobs.removeAll { it.status == JobStatus.DONE || it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }; save() }
}
