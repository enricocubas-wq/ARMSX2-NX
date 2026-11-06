package app.faxina.mac

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.faxina.core.FileEntry
import app.faxina.core.formatBytes
import app.faxina.core.mac.findDirNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pastas do início que o Faxina nunca deixa marcar: apagar uma delas seria um desastre. */
private val PROTECTED_AT_HOME = setOf(
    "library", "desktop", "documents", "downloads", "movies", "music", "pictures", "public", "applications",
)

private fun isProtected(path: String): Boolean {
    val home = Ops.home.path
    if (path == home) return true
    if (File(path).parent != home) return false
    val name = path.substringAfterLast('/')
    return name.lowercase() in PROTECTED_AT_HOME || name.startsWith(".")
}

private typealias File = java.io.File

@Composable
fun ExplorerScreen(c: MacController) {
    val scan by c.scan.collectAsState()
    val res by c.result.collectAsState()
    val folder by c.folder.collectAsState()
    val version by c.treeVersion.collectAsState()
    val useTrash by c.useTrash.collectAsState()
    var selected by remember(folder) { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }

    val tree = res?.tree
    if (tree == null) {
        if (scan == ScanUi.Running) {
            Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Analisando o Mac…", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            CenterNote("Ainda sem análise", "Volte ao Início e toque em analisar de novo.")
        }
        return
    }

    val node = remember(tree, folder, version) { findDirNode(tree, folder) ?: tree }
    val files by produceState(emptyList<FileEntry>(), folder, version) {
        value = withContext(Dispatchers.IO) { c.childFilesOf(node.path) }
    }
    val rows = remember(node, files, version) {
        (node.children.map { FileEntry(it.path, it.size, 0L, isDir = true) } + files).sortedByDescending { it.size }
    }
    val biggest = rows.firstOrNull()?.size?.coerceAtLeast(1) ?: 1L
    val selectedEntries = rows.filter { it.path in selected }
    val selectedBytes = selectedEntries.sumOf { it.size }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c.folderUp() }, enabled = node.path != tree.path) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Pasta de cima")
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    if (node.path == tree.path) "Pasta pessoal" else node.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${prettyPath(node.path)}  •  ${formatBytes(node.size)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { Ops.reveal(node.path) }) { Text("Mostrar no Finder") }
        }

        if (rows.isEmpty()) {
            CenterNote("Pasta vazia", modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(rows, key = { it.path }) { e ->
                        val locked = isProtected(e.path)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .then(if (e.isDir) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { c.openFolder(e.path) } else Modifier)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileThumb(e.path, size = 44.dp, isDir = e.isDir)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        e.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(formatBytes(e.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(5.dp))
                                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((e.size.toFloat() / biggest).coerceIn(0.01f, 1f))
                                            .height(6.dp).clip(CircleShape)
                                            .background(if (e.isDir) Brand.Amber else Brand.Blue),
                                    )
                                }
                            }
                            if (!e.isDir) {
                                IconButton(onClick = { Ops.reveal(e.path) }) {
                                    Icon(Icons.Rounded.OpenInNew, "Mostrar no Finder", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (locked) {
                                Icon(Icons.Rounded.Lock, "Pasta protegida", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp).size(20.dp))
                            } else {
                                Checkbox(checked = e.path in selected, onCheckedChange = { on -> selected = if (on) selected + e.path else selected - e.path })
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }

        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${selected.size} ${if (selected.size == 1) "item" else "itens"} • ${formatBytes(selectedBytes)}",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                BigButton(
                    text = if (useTrash) "Mover para a Lixeira" else "Apagar",
                    icon = Icons.Rounded.DeleteForever,
                    onClick = { confirm = true },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
    }

    if (confirm) {
        val (count, permanent, toTrash) = c.planSummaryFor(selectedEntries)
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remover $count ${if (count == 1) "item" else "itens"}?") },
            text = {
                Text(
                    if (permanent > 0) "${formatBytes(permanent)} serão apagados de vez. Não dá para desfazer."
                    else "${formatBytes(toTrash)} vão para a Lixeira. Você pode recuperar pelo Finder até esvaziá-la.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; c.cleanEntries(selectedEntries); selected = emptySet() }) {
                    Text(if (permanent > 0) "Apagar" else "Mover", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}
