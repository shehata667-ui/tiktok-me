package com.tiktokstudio.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatermarkScreen(info: VideoInfo, s: ExportSettings, wm: Watermark, onChange: (Watermark) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val frame by produceState<Bitmap?>(null, info.uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, info.uri)
                r.getFrameAtTime(minOf(1_000_000L, info.durationMs * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    val k = 960f / maxOf(b.width, b.height)
                    if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
                }
            } catch (e: Exception) { null } finally { r.release() }
        }
    }
    val placeholder = remember { Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) } }
    val base = frame ?: placeholder
    val preview = remember(base, s.rotation, s.mode, s.cropPos, wm) { composePreview(ctx, base, s, wm) }

    val wmNow by rememberUpdatedState(wm)
    val upd by rememberUpdatedState({ n: Watermark -> onChange(n.copy(enabled = true)) })
    var target by remember { mutableIntStateOf(0) }       // 0 نص، 1 شعار
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var tpls by remember { mutableStateOf(WmStore.templates(ctx)) }
    var selTpl by remember { mutableStateOf<String?>(null) }
    var showSave by remember { mutableStateOf(false) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            upd(wmNow.copy(logoUri = u.toString())); target = 1
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("النص والعلامة المائية") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Image(
                    preview.asImageBitmap(), "معاينة",
                    Modifier.height(320.dp).aspectRatio(9f / 16f)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .onSizeChanged { boxSize = it }
                        .pointerInput(target) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                if (boxSize.width > 0) {
                                    val dx = drag.x / boxSize.width; val dy = drag.y / boxSize.height
                                    val w0 = wmNow
                                    upd(if (target == 0) w0.copy(textX = (w0.textX + dx).coerceIn(0f, 1f), textY = (w0.textY + dy).coerceIn(0f, 1f))
                                        else w0.copy(logoX = (w0.logoX + dx).coerceIn(0f, 1f), logoY = (w0.logoY + dy).coerceIn(0f, 1f)))
                                }
                            }
                        },
                    contentScale = ContentScale.FillBounds)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اسحب على المعاينة لتحريك العنصر المحدد:", style = MaterialTheme.typography.bodySmall)
                Chips(listOf(0, 1), target, { if (it == 0) "النص" else "الشعار" }, true) { target = it }
                Banner(BannerKind.Info, "تطبيق TikTok بيغطي أسفل الشاشة والجانب بأزراره؛ الأفضل تحط العلامة في الأعلى.")

                if (target == 0) {
                    OutlinedTextField(wm.text, { upd(wm.copy(text = it)) }, Modifier.fillMaxWidth(),
                        label = { Text("اسم المستخدم / النص") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(wm.textEnabled, { upd(wm.copy(textEnabled = it)) }); Spacer(Modifier.width(8.dp)); Text("إظهار النص")
                    }
                    SizeRow("حجم النص", wm.textSize, 0.02f..0.30f, 0.01f) { upd(wm.copy(textSize = it)) }
                    SizeRow("الشفافية", wm.textOpacity, 0.1f..1f, 0.1f) { upd(wm.copy(textOpacity = it)) }
                    Text("اللون", style = MaterialTheme.typography.bodySmall)
                    Chips(TEXT_COLORS.map { it.second }, wm.textColor, { c -> TEXT_COLORS.first { it.second == c }.first }, true) { upd(wm.copy(textColor = it)) }
                    Text("الخط", style = MaterialTheme.typography.bodySmall)
                    Chips(FontChoice.entries, wm.font, { it.label }, true) { upd(wm.copy(font = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(wm.shadow, { upd(wm.copy(shadow = it)) }); Spacer(Modifier.width(8.dp)); Text("ظل للنص")
                    }
                    PosRow { x, y -> upd(wm.copy(textX = x, textY = y)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { logoPicker.launch(arrayOf("image/*")) }) { Text(if (wm.logoUri == null) "اختيار شعار (PNG)" else "تغيير الشعار") }
                        if (wm.logoUri != null) OutlinedButton(onClick = { upd(wm.copy(logoUri = null)) }) { Text("إزالة") }
                    }
                    if (wm.logoUri != null) {
                        SizeRow("حجم الشعار", wm.logoSize, 0.05f..0.8f, 0.02f) { upd(wm.copy(logoSize = it)) }
                        SizeRow("الشفافية", wm.logoOpacity, 0.1f..1f, 0.1f) { upd(wm.copy(logoOpacity = it)) }
                        PosRow { x, y -> upd(wm.copy(logoX = x, logoY = y)) }
                    }
                }

                Sec("القوالب")
                if (tpls.isEmpty()) Text("مفيش قوالب محفوظة.", style = MaterialTheme.typography.bodySmall)
                else Chips(tpls.keys.toList(), selTpl ?: "", { it }, true) { n -> selTpl = n; tpls[n]?.let { upd(it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showSave = true }) { Text("حفظ كقالب") }
                    selTpl?.let { n ->
                        if (n in tpls) TextButton(onClick = { WmStore.deleteTemplate(ctx, n); tpls = WmStore.templates(ctx); selTpl = null }) { Text("حذف «$n»") }
                    }
                }
                Text("آخر إعداداتك بتتحفظ تلقائيًا.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("حفظ كقالب") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("اسم القالب") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        WmStore.saveTemplate(ctx, name.trim(), wm); tpls = WmStore.templates(ctx); selTpl = name.trim(); showSave = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("إلغاء") } })
    }
}

@Composable
fun SizeRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - step).coerceIn(range)) }) { Icon(Icons.Default.Remove, "تصغير") }
            Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange((value + step).coerceIn(range)) }) { Icon(Icons.Default.Add, "تكبير") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosRow(onPos: (Float, Float) -> Unit) {
    val items = listOf("أعلى" to (0.5f to 0.1f), "وسط" to (0.5f to 0.5f), "أسفل" to (0.5f to 0.88f), "يمين" to (0.8f to 0.5f), "يسار" to (0.2f to 0.5f))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (n, p) -> OutlinedButton(onClick = { onPos(p.first, p.second) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(n) } }
    }
}
