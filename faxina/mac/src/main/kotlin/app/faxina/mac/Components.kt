package app.faxina.mac

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.faxina.core.FileCategory
import app.faxina.core.categoryOf
import app.faxina.core.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .then(if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier),
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
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val bg = if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
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
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier = modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
    stroke: Dp = 24.dp,
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

// ---------------------------------------------------------------- miniaturas (só JDK: sem baixar nada)

private val thumbGate = Semaphore(4)
private val thumbCache = object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 300
}

private fun decodeThumb(file: File, target: Int): ImageBitmap? {
    if (!file.isFile || file.length() > 60L * 1000 * 1000) return null
    return try {
        ImageIO.createImageInputStream(file)?.use { iis ->
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(iis, true, true)
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                val sub = maxOf(1, minOf(w, h) / target)
                val param = reader.defaultReadParam.also { it.setSourceSubsampling(sub, sub, 0, 0) }
                val src = reader.read(0, param)
                val side = minOf(src.width, src.height)
                val out = BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB)
                val g = out.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(
                    src, 0, 0, target, target,
                    (src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2, null,
                )
                g.dispose()
                out.toComposeImageBitmap()
            } finally {
                reader.dispose()
            }
        }
    } catch (_: Throwable) {
        null
    }
}

@Composable
fun rememberThumb(path: String, px: Int): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, path, px) {
        val cached = synchronized(thumbCache) { if (thumbCache.containsKey(path)) thumbCache[path] to true else null to false }
        if (cached.second) {
            value = cached.first
        } else {
            value = withContext(Dispatchers.IO) { thumbGate.withPermit { decodeThumb(File(path), px) } }
            synchronized(thumbCache) { thumbCache[path] = value }
        }
    }
    return state
}

@Composable
fun FileThumb(path: String, modifier: Modifier = Modifier, size: Dp = 44.dp, isDir: Boolean = false) {
    val category = remember(path) { categoryOf(path.substringAfterLast('/')) }
    val ext = remember(path) { path.substringAfterLast('.', "").lowercase() }
    val canDecode = !isDir && category == FileCategory.IMAGE && ext in setOf("jpg", "jpeg", "png", "gif", "bmp")
    val bmp = if (canDecode) rememberThumb(path, 96) else null
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val (icon, tint) = when {
                isDir -> Icons.Rounded.Folder to Brand.Amber
                category == FileCategory.VIDEO -> Icons.Rounded.Movie to Brand.Violet
                category == FileCategory.IMAGE -> Icons.Rounded.InsertDriveFile to Brand.Blue
                else -> Icons.Rounded.InsertDriveFile to Brand.Slate
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun FullWidthText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
