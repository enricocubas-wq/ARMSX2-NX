package app.faxina.mac

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.faxina.core.FileEntry
import app.faxina.core.formatBytes
import app.faxina.core.mac.MacApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

private val iconGate = Semaphore(3)

/** Ícone do app via Quick Look (qlmanage), guardado em ~/Library/Caches/Faxina/icons. */
private fun loadAppIcon(appPath: String): ImageBitmap? {
    if (!Ops.isMac) return null
    return try {
        val dir = File(Ops.home, "Library/Caches/Faxina/icons").also { it.mkdirs() }
        val out = File(dir, File(appPath).name + ".png")
        if (!out.exists()) Ops.run("qlmanage", "-t", "-s", "96", "-o", dir.path, appPath, timeoutSec = 20)
        if (out.exists()) ImageIO.read(out)?.toComposeImageBitmap() else null
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun AppIcon(app: MacApp, size: Dp = 46.dp) {
    val bmp by produceState<ImageBitmap?>(null, app.path) {
        value = withContext(Dispatchers.IO) { iconGate.withPermit { loadAppIcon(app.path) } }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(app.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class AppSort(val label: String) { SIZE("Maiores"), UNUSED("Menos usados") }

@Composable
fun AppsScreen(c: MacController) {
    val apps by c.apps.collectAsState()
    var sort by remember { mutableStateOf(AppSort.SIZE) }
    var target by remember { mutableStateOf<MacApp?>(null) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp)) {
            Text("Apps", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Tamanho de cada app e quando você abriu pela última vez. Desinstalar manda o app e os restos dele para a Lixeira.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSort.values().forEach { s -> Chip(s.label, sort == s) { sort = s } }
            }
        }
        when (val a = apps) {
            AppsUi.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is AppsUi.Ready -> {
                val list = remember(a, sort) {
                    when (sort) {
                        AppSort.SIZE -> a.items.sortedByDescending { it.bytes }
                        AppSort.UNUSED -> a.items.sortedBy { it.lastUsedMs ?: 0L }
                    }
                }
                if (list.isEmpty()) {
                    CenterNote("Nenhum app encontrado", modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.path }) { app ->
                                Panel(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AppIcon(app)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(app.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                formatBytes(app.bytes) + " • " + (app.lastUsedMs?.let { "aberto ${ago(it)}" } ?: "sem registro de uso"),
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        TextButton(onClick = { Ops.reveal(app.path) }) { Text("Mostrar") }
                                        TextButton(onClick = { target = app }) {
                                            Text("Desinstalar…", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }

    target?.let { app -> UninstallDialog(c, app) { target = null } }
}

@Composable
private fun UninstallDialog(c: MacController, app: MacApp, onClose: () -> Unit) {
    val left by produceState<List<FileEntry>?>(null, app.path) { value = c.leftoversFor(app) }
    var chosen by remember(app.path) { mutableStateOf<Set<String>?>(null) }
    val list = left
    val picked = chosen ?: list?.map { it.path }?.toSet().orEmpty()
    val pickedEntries = list?.filter { it.path in picked }.orEmpty()
    val total = app.bytes + pickedEntries.sumOf { it.size }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Desinstalar ${app.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(520.dp)) {
                Text("O app (${formatBytes(app.bytes)}) vai para a Lixeira. Dá para recuperar pelo Finder até esvaziá-la.", style = MaterialTheme.typography.bodyMedium)
                when {
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Procurando restos do app…", style = MaterialTheme.typography.bodySmall)
                    }
                    list.isEmpty() -> Text("Não achei restos em ~/Library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Text("Restos do app na sua Biblioteca:", style = MaterialTheme.typography.labelLarge)
                        list.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = e.path in picked, onCheckedChange = { on -> chosen = if (on) picked + e.path else picked - e.path })
                                Column(Modifier.weight(1f)) {
                                    Text(prettyPath(e.path), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatBytes(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = list != null, onClick = { onClose(); c.uninstall(app, pickedEntries) }) {
                Text("Mover ${formatBytes(total)} para a Lixeira", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}
