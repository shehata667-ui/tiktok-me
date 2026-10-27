package com.tiktokstudio.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(info: VideoInfo, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var failed by remember { mutableStateOf(false) }
    val a by produceState<Analysis?>(AnalysisCache.get(info.uri), info.uri) {
        if (value == null) {
            val res = withContext(Dispatchers.IO) { runCatching { analyzeVideo(ctx, info) }.onFailure { AppLog.e(ctx, "analyze failed", it) }.getOrNull() }
            value = res
            if (res != null) AnalysisCache.put(info.uri, res) else failed = true
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("فحص الفيديو") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        val r = a
        if (r == null) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (failed) EmptyState(Icons.Default.Warning, "تعذّر فحص الفيديو", "شوف سجل الأخطاء في الإعدادات.", Modifier.align(Alignment.Center))
                else LoadingState("بيتحلل الفيديو... ممكن ياخد ثواني للفيديوهات الطويلة.")
            }
        } else Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(Sp.lg), verticalArrangement = Arrangement.spacedBy(Sp.lg)) {
            r.frame?.let { fr ->
                val shown = remember(r) { drawBoxes(fr, r.overlays, r.qr) }
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Sp.md), verticalArrangement = Arrangement.spacedBy(Sp.sm)) {
                        Image(shown.asImageBitmap(), "إطار من الفيديو", Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
                        Text("الأحمر: موضع محتمل لشعار أو علامة (تقديري) • الأزرق: رمز QR", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            val i = r.info
            Panel("المعلومات الفنية", Icons.Default.Info) {
                KV("الدقة", "${i.resolution} (${if (i.isPortrait) "عمودي" else "أفقي"})")
                KV("النسبة", String.format(Locale.US, "%.2f", i.width.toFloat() / i.height))
                KV("الترميز", r.codec + if (r.hdr) " • HDR" else "")
                KV("المدة والحجم", "${i.durationText} • ${i.sizeText}")
                KV("معدل البت", i.bitrateBps?.let { String.format(Locale.US, "%.1f Mbps", it / 1e6) } ?: "غير معروف")
                KV("معدل الإطارات المعلن", i.fpsText)
                KV("الصوت", if (i.hasAudioGuess) "موجود" else "غير موجود")
                KV("التدوير", "${i.rotation}°")
            }
            r.timing?.let { t ->
                Panel("انتظام الإطارات", Icons.Default.Tune) {
                    KV("عدد الإطارات", "${t.frames}")
                    KV("المتوسط / الوسيط", String.format(Locale.US, "%.2f / %.2f fps", t.avgFps, t.medianFps))
                    KV("أقصر / أطول فاصل", String.format(Locale.US, "%.1f / %.1f ms", t.minIntervalMs, t.maxIntervalMs))
                    KV("غير منتظم", String.format(Locale.US, "%.1f%%", t.irregularPct))
                    KV("النوع", if (t.isVfr) "VFR (متغير)" else "قريب من CFR (ثابت)")
                }
            }
            if (r.meta.isNotEmpty()) Panel("البيانات الوصفية", Icons.Default.Info) {
                r.meta.forEach { (k, v) -> KV(k, v) }
            }

            Text("نتيجة الفحص", style = MaterialTheme.typography.titleMedium)
            val warn = LocalWarn.current
            r.findings.forEach { f ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(Sp.md), horizontalArrangement = Arrangement.spacedBy(Sp.md)) {
                        val (icon, tint) = when (f.level) {
                            Level.OK -> Icons.Default.CheckCircle to cs.tertiary
                            Level.INFO -> Icons.Default.Info to cs.primary
                            Level.WARN -> Icons.Default.Warning to warn.fg
                        }
                        Icon(icon, null, Modifier.size(22.dp), tint = tint)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall)
                            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            if (f.fix != Fix.NONE) Pill(f.fix.label, cs.surfaceVariant, cs.onSurfaceVariant)
                            Text(f.action, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("الفحص البصري (الشعارات، الأشرطة، QR) تقديري من 5 إلى 8 إطارات بس، وممكن يفوّت حاجات أو يغلط.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Button(onClick = onEdit, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text("تجهيز الفيديو")
            }
            OriginalityCard(r)
            Spacer(Modifier.height(Sp.lg))
        }
    }
}

@Composable
fun OriginalityCard(a: Analysis) {
    val ctx = LocalContext.current
    Panel("مساعد الأصالة وأهلية النشر", Icons.Default.Info) {
        Banner(BannerKind.Warning, "ده إرشاد مبني على الفحص الفني بس. قرار التوصية بيرجع لـ TikTok، ومفيش أداة تقدر تعرفه من الملف. التطبيق مبيضمنش إن الفيديو «مؤهل للتوصية».")
        Text("أفكار تضيف قيمة حقيقية", style = MaterialTheme.typography.titleSmall)
        Bullet("تعليق صوتي أصلي بصوتك")
        Bullet("شرح أو تحليل خاص بيك للمحتوى")
        Bullet("معلومات مفيدة أو سياق جديد")
        Bullet("مقدمة وخاتمة أصليتين")
        Bullet("راجع حقوق استخدام الفيديو والموسيقى قبل النشر")
        if (a.overlays.isNotEmpty() || !a.qr.isNullOrEmpty())
            Banner(BannerKind.Info, "الفحص لقى عناصر ممكن تخص مصدر تاني (شعار أو QR). التطبيق مبيشيلها ومبيضيفش تشويش أو تغييرات شكلية للتحايل على اكتشاف إعادة النشر. لو الفيديو مش بتاعك، خد إذن صاحبه أو استخدم محتواك.")
        Text("المتطلبات بتتغير، فراجعها من المصدر الرسمي (محتاج إنترنت).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/community-guidelines")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("إرشادات TikTok الرسمية") }
    }
}

private data class Side(val info: VideoInfo?, val timing: TimingStats?, val thumb: Bitmap?, val loc: Boolean)

private fun sideOf(ctx: Context, uri: Uri): Side {
    val info = runCatching { readVideoInfo(ctx, uri) }.getOrNull() ?: return Side(null, null, null, false)
    val timing = runCatching { timingStats(ctx, uri) }.getOrNull()
    var thumb: Bitmap? = null; var loc = false
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null
        val h = (360f * info.height / info.width).toInt().coerceAtLeast(1)
        thumb = r.getScaledFrameAtTime(minOf(1_000_000L, info.durationMs * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, h)
    } catch (_: Exception) {} finally { r.release() }
    return Side(info, timing, thumb, loc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(job: ExportJob, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data by produceState<Pair<Side, Side>?>(null, job.id) {
        value = withContext(Dispatchers.IO) { sideOf(ctx, Uri.parse(job.srcUri)) to sideOf(ctx, Uri.parse(job.outUri ?: "")) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("مقارنة: ${job.name}", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        val d = data
        if (d == null) { Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        val (s, o) = d
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("الأصل" to s, "الناتج" to o).forEach { (label, side) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        side.thumb?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit) }
                            ?: Text("غير متاح", Modifier.height(220.dp))
                    }
                }
            }
            fun v(x: Side, f: (VideoInfo) -> String) = x.info?.let(f) ?: "غير متاح"
            CmpRow("", "الأصل", "الناتج")
            CmpRow("الدقة", v(s) { it.resolution }, v(o) { it.resolution })
            CmpRow("المدة", v(s) { it.durationText }, v(o) { it.durationText })
            CmpRow("الحجم", v(s) { it.sizeText }, v(o) { it.sizeText })
            CmpRow("معدل البت", v(s) { i -> i.bitrateBps?.let { String.format(Locale.US, "%.1f Mbps", it / 1e6) } ?: "-" },
                v(o) { i -> i.bitrateBps?.let { String.format(Locale.US, "%.1f Mbps", it / 1e6) } ?: "-" })
            CmpRow("FPS (مقاس)", s.timing?.let { String.format(Locale.US, "%.2f", it.avgFps) } ?: "-", o.timing?.let { String.format(Locale.US, "%.2f", it.avgFps) } ?: "-")
            CmpRow("الإطارات", s.timing?.frames?.toString() ?: "-", o.timing?.frames?.toString() ?: "-")
            CmpRow("النوع", s.timing?.let { if (it.isVfr) "VFR" else "CFR" } ?: "-", o.timing?.let { if (it.isVfr) "VFR" else "CFR" } ?: "-")
            CmpRow("الترميز", v(s) { codecName(it.mime) }, v(o) { codecName(it.mime) })
            CmpRow("الموقع", if (s.loc) "موجود" else "لا", if (o.loc) "موجود" else "لا")
            Text("المقارنة معلوماتية. عدد الإطارات بيختلف لو قصّيت أو خفّضت الـ FPS. التقطيع الحقيقي بيتأكد منه بالمشاهدة على جهازك.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openVideo(ctx, Uri.parse(job.srcUri)) }) { Text("تشغيل الأصل") }
                job.outUri?.let { u -> Button(onClick = { openVideo(ctx, Uri.parse(u)) }) { Text("تشغيل الناتج") } }
            }
        }
    }
}

@Composable
private fun CmpRow(label: String, a: String, b: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
        Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(b, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
