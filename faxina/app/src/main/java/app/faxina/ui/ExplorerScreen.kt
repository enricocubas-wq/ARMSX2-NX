package app.faxina.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.faxina.core.DirNode
import app.faxina.core.FileEntry
import app.faxina.core.formatBytes
import app.faxina.data.Openers
import app.faxina.data.StorageInfo
import app.faxina.vm.DeleteUi
import app.faxina.vm.MainViewModel
import app.faxina.vm.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun findNode(roots: List<DirNode>, path: String): DirNode? {
    var list = roots
    while (true) {
        val n = list.firstOrNull { path == it.path || path.startsWith(it.path + "/") } ?: return null
        if (n.path == path) return n
        list = n.children
    }
}

@Composable
fun ExplorerScreen(vm: MainViewModel, path: String) {
    val tree by vm.tree.collectAsStateWithLifecycle()
    val version by vm.treeVersion.collectAsStateWithLifecycle()
    val delete by vm.delete.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var selected by remember(path) { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }

    val node = remember(tree, path, version) { if (path.isEmpty()) null else findNode(tree, path) }
    val dirs: List<DirNode> = if (path.isEmpty()) tree else node?.children ?: emptyList()
    val files by produceState(emptyList<FileEntry>(), path, version) {
        value = if (path.isEmpty()) emptyList() else withContext(Dispatchers.IO) { vm.childFilesOf(path) }
    }
    val rows = remember(dirs, files, version) {
        val all = dirs.map { FileEntry(it.path, it.size, 0L, isDir = true) } + files
        all.sortedByDescending { it.size }
    }
    val biggest = rows.firstOrNull()?.size?.coerceAtLeast(1) ?: 1L
    val primary = remember { StorageInfo.primaryRoot().path }
    val selectedEntries = rows.filter { it.path in selected }
    val selectedBytes = selectedEntries.sumOf { it.size }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(
            when {
                path.isEmpty() -> "Explorar pastas"
                path == primary -> "Armazenamento interno"
                else -> path.substringAfterLast('/')
            },
            onBack = { vm.back() },
        )
        if (path.isNotEmpty()) {
            Text(
                "${shortParent(path)}  •  ${formatBytes(node?.size ?: 0)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Pasta vazia", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.path }) { e ->
                    val label = when {
                        path.isEmpty() && e.path == primary -> "Armazenamento interno"
                        path.isEmpty() -> "Cartão de memória"
                        else -> e.name
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { if (e.isDir) vm.go(Screen.Explorer(e.path)) else Openers.openFile(ctx, e.path) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileThumb(e.path, size = 48.dp, isDir = e.isDir)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(formatBytes(e.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(5.dp))
                            Box(
                                Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((e.size.toFloat() / biggest).coerceIn(0.01f, 1f))
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(if (e.isDir) Brand.Amber else Brand.Blue),
                                )
                            }
                        }
                        if (path.isNotEmpty()) {
                            Checkbox(
                                checked = e.path in selected,
                                onCheckedChange = { on -> selected = if (on) selected + e.path else selected - e.path },
                            )
                        } else {
                            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                BigButton(
                    text = "Apagar ${formatBytes(selectedBytes)}",
                    icon = Icons.Rounded.DeleteForever,
                    enabled = delete is DeleteUi.Idle,
                    onClick = { confirm = true },
                )
            }
        }
    }

    if (confirm) {
        ConfirmDelete(
            count = selectedEntries.size,
            bytes = selectedBytes,
            onCancel = { confirm = false },
            onConfirm = {
                confirm = false
                vm.deleteEntries(selectedEntries)
                selected = emptySet()
            },
        )
    }
    DeletingOverlay(delete)
}
