package app.faxina.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.faxina.core.FileCategory
import app.faxina.core.categoryOf
import app.faxina.core.formatBytes
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import java.io.File

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    height: Dp = 64.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val bg = if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Texto de tamanho que "conta" até o valor novo. */
@Composable
fun animatedBytes(target: Long, durationMs: Int = 900): String {
    val v by animateFloatAsState(target.toFloat(), tween(durationMs, easing = FastOutSlowInEasing), label = "bytes")
    return formatBytes(v.toLong())
}

class RingSegment(val fraction: Float, val color: Color)

@Composable
fun StorageRing(
    segments: List<RingSegment>,
    modifier: Modifier = Modifier,
    stroke: Dp = 22.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable BoxScope.() -> Unit,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(segments.size, segments.sumOf { (it.fraction * 1000).toInt() }) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val d = size.minDimension - w
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arc = Size(d, d)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(w))
            var start = -90f
            val gap = 2.2f
            for (s in segments) {
                val angle = s.fraction.coerceIn(0f, 1f) * 360f * sweep.value
                if (angle > gap + 0.2f) {
                    drawArc(s.color, start + gap / 2f, angle - gap, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Butt))
                }
                start += angle
            }
        }
        content()
    }
}

@Composable
fun FileThumb(path: String, modifier: Modifier = Modifier, size: Dp = 48.dp, isDir: Boolean = false) {
    val category = remember(path) { categoryOf(path.substringAfterLast('/')) }
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = with(density) { size.roundToPx() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!isDir && (category == FileCategory.IMAGE || category == FileCategory.VIDEO)) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(path)).size(CoilSize(px, px)).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                if (isDir) Icons.Rounded.Folder else category.icon(),
                contentDescription = null,
                tint = if (isDir) Brand.Amber else category.color(),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier = modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
