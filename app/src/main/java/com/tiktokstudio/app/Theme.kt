package com.tiktokstudio.app

/*
 * نظام التصميم (Design System)
 * الهوية: هادئة ونظيفة، لون واحد مميز (Indigo) + ألوان محايدة. الألوان الدلالية (نجاح/تحذير/خطأ) للحالات فقط.
 *
 * ألوان الوضع الفاتح:  Brand #4F46E5 | Background #F7F7F9 | Surface #FFFFFF | Border #E4E4EA
 *                       Text #16161D | Text-2 #6B6B7A | Success #15803D | Warning #B45309 | Error #DC2626
 * ألوان الوضع الداكن:  Brand #9A9CFA | Background #0E0E12 | Surface #16161C | Border #2A2A34
 *                       Text #F2F2F7 | Text-2 #9A9AAA | Success #4ADE80 | Warning #FBBF24 | Error #F87171
 * الخطوط: خط النظام (يدعم العربية). عنوان 28 | قسم 17 | عنصر 15 | نص 15 | تفاصيل 13 (وزن Bold/SemiBold للعناوين فقط).
 * المسافات: 4 / 8 / 12 / 16 / 24 / 32.  الاستدارة: 10 / 14 / 20 / 28.  الظلال: بدون (حد 1dp بدلًا منها).
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Sp { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp; val xxl = 32.dp }

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFECEBFE), onPrimaryContainer = Color(0xFF2B2799),
    secondary = Color(0xFF5B5B6B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEBFE), onSecondaryContainer = Color(0xFF2B2799),
    tertiary = Color(0xFF15803D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8F6EC), onTertiaryContainer = Color(0xFF0F5A2B),
    background = Color(0xFFF7F7F9), onBackground = Color(0xFF16161D),
    surface = Color.White, onSurface = Color(0xFF16161D),
    surfaceVariant = Color(0xFFEEEEF3), onSurfaceVariant = Color(0xFF6B6B7A),
    outline = Color(0xFF9A9AAA), outlineVariant = Color(0xFFE4E4EA),
    error = Color(0xFFDC2626), onError = Color.White,
    errorContainer = Color(0xFFFDECEC), onErrorContainer = Color(0xFF7F1D1D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF3F3F7), surfaceContainerHigh = Color(0xFFEDEDF2), surfaceContainerHighest = Color(0xFFE7E7EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9A9CFA), onPrimary = Color(0xFF1B1A5C),
    primaryContainer = Color(0xFF26265F), onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF9A9AAA), onSecondary = Color(0xFF16161C),
    secondaryContainer = Color(0xFF26265F), onSecondaryContainer = Color(0xFFE0E0FF),
    tertiary = Color(0xFF4ADE80), onTertiary = Color(0xFF0B3D1E),
    tertiaryContainer = Color(0xFF12301F), onTertiaryContainer = Color(0xFFBBF7D0),
    background = Color(0xFF0E0E12), onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF16161C), onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF23232C), onSurfaceVariant = Color(0xFF9A9AAA),
    outline = Color(0xFF6E6E80), outlineVariant = Color(0xFF2A2A34),
    error = Color(0xFFF87171), onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF3A1717), onErrorContainer = Color(0xFFFECACA),
    surfaceContainerLowest = Color(0xFF0A0A0E), surfaceContainerLow = Color(0xFF121218),
    surfaceContainer = Color(0xFF1B1B22), surfaceContainerHigh = Color(0xFF24242D), surfaceContainerHighest = Color(0xFF2E2E39),
)

data class WarnColors(val fg: Color, val bg: Color)
val LocalWarn = staticCompositionLocalOf { WarnColors(Color(0xFFB45309), Color(0xFFFFF4E0)) }

private val baseType = Typography()
private val AppTypography = Typography(
    headlineMedium = baseType.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    titleLarge = baseType.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleMedium = baseType.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleSmall = baseType.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    bodyLarge = baseType.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = baseType.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = baseType.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = baseType.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = baseType.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalWarn provides (if (dark) WarnColors(Color(0xFFFBBF24), Color(0xFF35290E)) else WarnColors(Color(0xFFB45309), Color(0xFFFFF4E0)))) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

// ------------------------------------------------------------------ مكونات مشتركة

/** سطح أبيض بحد رفيع، بدون ظل. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = border, content = content,
    )
}

/** قسم بعنوان وأيقونة صغيرة. */
@Composable
fun Panel(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Sp.lg), verticalArrangement = Arrangement.spacedBy(Sp.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.sm)) {
                if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, color = content, style = MaterialTheme.typography.labelMedium)
    }
}

enum class BannerKind { Success, Warning, Error, Info }

/** رسالة حالة (نجاح/تحذير/خطأ/معلومة) بأيقونة حقيقية. */
@Composable
fun Banner(kind: BannerKind, text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val w = LocalWarn.current
    val (fg, bg, icon) = when (kind) {
        BannerKind.Success -> Triple(cs.tertiary, cs.tertiaryContainer, Icons.Default.CheckCircle)
        BannerKind.Warning -> Triple(w.fg, w.bg, Icons.Default.Warning)
        BannerKind.Error -> Triple(cs.error, cs.errorContainer, Icons.Default.Error)
        BannerKind.Info -> Triple(cs.primary, cs.primaryContainer, Icons.Default.Info)
    }
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(Sp.md),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChecked)
    }
}

@Composable
fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sp.md)) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Sp.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Sp.sm)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Sp.xs))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(Sp.xxl), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(Modifier.height(Sp.lg))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** بطاقة الإجراء الرئيسي: اللون المميز الوحيد القوي في الشاشة. */
@Composable
fun HeroCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(cs.primary).clickable(onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.lg),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimary.copy(alpha = 0.85f))
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(cs.onPrimary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onPrimary)
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(Sp.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** صف في قائمة (أيقونة + عنوان + وصف + سهم). */
@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String, badge: Int = 0, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Sp.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = cs.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (badge > 0) Pill("$badge", cs.primary, cs.onPrimary)
        Icon(Icons.Default.KeyboardArrowLeft, null, tint = cs.outline)
    }
}
