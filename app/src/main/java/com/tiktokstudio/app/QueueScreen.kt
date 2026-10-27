package com.tiktokstudio.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.File

private fun statusText(s: JobStatus) = when (s) {
    JobStatus.QUEUED -> "في الانتظار"; JobStatus.RUNNING -> "جاري المعالجة"; JobStatus.DONE -> "تمت"
    JobStatus.FAILED -> "فشلت"; JobStatus.CANCELLED -> "اتلغت"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: () -> Unit, onCompare: (ExportJob) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val active = QueueManager.jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    val history = QueueManager.jobs.filter { it.status != JobStatus.QUEUED && it.status != JobStatus.RUNNING }.reversed()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("المعالجة والسجل") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
            actions = {
                if (tab == 0 && active.isNotEmpty()) TextButton(onClick = { QueueManager.cancelAll() }) { Text("إلغاء الكل") }
                if (tab == 1 && history.isNotEmpty()) TextButton(onClick = { QueueManager.clearFinished() }) { Text("مسح السجل") }
            })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(tab == 0, { tab = 0 }, text = { Text("الطابور (${active.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("السجل (${history.size})") })
            }
            val list = if (tab == 0) active else history
            LazyColumn(contentPadding = PaddingValues(Sp.lg), verticalArrangement = Arrangement.spacedBy(Sp.md)) {
                if (tab == 0 && QueueManager.interruptedCount > 0 && !QueueManager.running) item {
                    Banner(BannerKind.Warning, "فيه ${QueueManager.interruptedCount} مهمة اتقطعت بعد غلق التطبيق. الاستئناف هيبدأ كل مهمة من الأول.")
                }
                if (tab == 0 && active.any { it.status == JobStatus.QUEUED } && !QueueManager.running) item {
                    Button(onClick = { ExportService.start(ctx) }, Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(if (QueueManager.interruptedCount > 0) "استئناف المهام" else "بدء المعالجة")
                    }
                }
                if (list.isEmpty()) item {
                    EmptyState(Icons.Default.History, if (tab == 0) "الطابور فاضي" else "السجل فاضي",
                        if (tab == 0) "أضف فيديوهات من شاشة التجهيز وهتظهر هنا." else "نتايج المعالجة هتظهر هنا.")
                }
                items(list, key = { it.id }) { JobCard(it, onCompare) }
            }
        }
    }
}

@Composable
private fun StatusPill(s: JobStatus) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (s) {
        JobStatus.QUEUED -> cs.surfaceVariant to cs.onSurfaceVariant
        JobStatus.RUNNING -> cs.primaryContainer to cs.onPrimaryContainer
        JobStatus.DONE -> cs.tertiaryContainer to cs.onTertiaryContainer
        JobStatus.FAILED -> cs.errorContainer to cs.onErrorContainer
        JobStatus.CANCELLED -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Pill(statusText(s), bg, fg)
}

@Composable
fun JobCard(j: ExportJob, onCompare: (ExportJob) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val progress by animateFloatAsState(j.progress, label = "progress")
    AppCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(Sp.lg), verticalArrangement = Arrangement.spacedBy(Sp.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.sm)) {
                Text(j.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(j.status)
            }
            when (j.status) {
                JobStatus.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = cs.surfaceVariant)
                    Text("${(j.progress * 100).toInt()}% • المنقضي ${formatDuration(j.elapsedMs)}" + (j.etaMs?.let { " • المتبقي ~${formatDuration(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    j.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    j.warning?.let { Banner(BannerKind.Warning, it) }
                }
                JobStatus.QUEUED -> j.message?.let { Banner(BannerKind.Info, it) }
                JobStatus.DONE -> {
                    j.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    if (j.warnings.isEmpty()) Banner(BannerKind.Success, "اجتاز الفحص الأساسي (فتح الملف، المدة، الإطارات، الصوت). جرّبه على أكتر من مشغل.")
                    else j.warnings.forEach { Banner(BannerKind.Warning, it) }
                    j.outUri?.let { u ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Sp.sm), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { openVideo(ctx, Uri.parse(u)) }, Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("تشغيل")
                            }
                            OutlinedButton(onClick = { shareVideo(ctx, Uri.parse(u)) }, Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("مشاركة")
                            }
                            TextButton(onClick = { onCompare(j) }) { Text("مقارنة") }
                        }
                    }
                }
                JobStatus.FAILED -> j.message?.let { Banner(BannerKind.Error, it) }
                JobStatus.CANCELLED -> {}
            }
            Row {
                if (j.status == JobStatus.QUEUED || j.status == JobStatus.RUNNING) TextButton(onClick = { QueueManager.cancel(j.id) }) { Text("إلغاء") }
                if (j.status == JobStatus.FAILED || j.status == JobStatus.CANCELLED)
                    TextButton(onClick = { QueueManager.retry(j.id); ExportService.start(ctx) }) { Text("إعادة المحاولة") }
                if (j.status != JobStatus.RUNNING && j.status != JobStatus.QUEUED) TextButton(onClick = { QueueManager.remove(j.id) }) { Text("إزالة من السجل") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tree by remember { mutableStateOf(Prefs.outTree(ctx)) }
    var cleaned by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf(File(ctx.filesDir, "errors.log").takeIf { it.exists() }?.readText()?.takeLast(4000) ?: "") }
    val clip = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Prefs.setOutTree(ctx, u); tree = u
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("الإعدادات") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(Sp.lg), verticalArrangement = Arrangement.spacedBy(Sp.lg)) {
            Panel("مكان حفظ الملفات", Icons.Default.Folder) {
                KV("المجلد الحالي", tree?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: "لم يتم الاختيار")
                Button(onClick = { picker.launch(null) }) { Text("اختيار / تغيير المجلد") }
                Text("الملفات الأصلية مبتتغيّرش ولا بتتحذف أبدًا.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Panel("الملفات المؤقتة", Icons.Default.Delete) {
                OutlinedButton(onClick = {
                    var n = 0L
                    ctx.cacheDir.listFiles()?.filter { it.name.startsWith("job_") }?.forEach { n += it.length(); it.delete() }
                    cleaned = "اتمسح ${formatSize(n)}"
                }, enabled = !QueueManager.running) { Text("مسح الملفات المؤقتة") }
                cleaned?.let { Banner(BannerKind.Success, it) }
            }
            Panel("سجل الأخطاء", Icons.Default.Warning) {
                if (logText.isBlank()) Text("مفيش أخطاء مسجّلة.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                else {
                    Text(logText, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.sm)) {
                        OutlinedButton(onClick = { clip.setText(AnnotatedString(logText)) }) { Text("نسخ") }
                        OutlinedButton(onClick = { File(ctx.filesDir, "errors.log").delete(); logText = "" }) { Text("مسح") }
                    }
                }
            }
            Panel("حدود معروفة", Icons.Default.Info) {
                Bullet("المعالجة بتكمل في الخلفية بخدمة أمامية، لكن لو التطبيق اتقفل بالقوة المهمة الجارية بتبدأ من الأول.")
                Bullet("مفيش تحقق مضمون من جودة التشغيل على كل الأجهزة؛ جرّب الناتج على جهازك.")
                Bullet("الفيديو HDR والـ VFR لسه ملهمش معالجة خاصة.")
                Bullet("4K و60 fps و H.265 بتتوقف على قدرات مشفّر الجهاز؛ لو مش مدعومة بيتنزّل لأعلى اختيار متاح.")
                Bullet("قرار التوصية في TikTok بيرجع للمنصة، ومفيش أداة بتضمنه.")
            }
        }
    }
}
