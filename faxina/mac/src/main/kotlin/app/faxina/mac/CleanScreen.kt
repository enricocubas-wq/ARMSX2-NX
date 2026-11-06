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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.faxina.core.FileEntry
import app.faxina.core.Risk
import app.faxina.core.formatBytes
import app.faxina.core.mac.MacGroup
import app.faxina.core.mac.MacGroupId

private sealed interface Row2 {
    data class Header(val setId: Int, val size: Long, val copies: Int) : Row2
    data class Item(val entry: FileEntry) : Row2
}

private fun buildRows(items: List<FileEntry>, grouped: Boolean): List<Row2> {
    if (!grouped) return items.map { Row2.Item(it) }
    val out = ArrayList<Row2>(items.size + items.size / 2)
    var last = Int.MIN_VALUE
    val counts = items.groupingBy { it.setId }.eachCount()
    for (e in items) {
        if (e.setId != last) {
            out.add(Row2.Header(e.setId, e.size, counts[e.setId] ?: 1))
            last = e.setId
        }
        out.add(Row2.Item(e))
    }
    return out
}

@Composable
fun CleanScreen(c: MacController) {
    val scan by c.scan.collectAsState()
    val progress by c.progress.collectAsState()
    val groups by c.groups.collectAsState()
    val sel by c.selStats.collectAsState()
    val dup by c.dup.collectAsState()
    val active by c.activeGroup.collectAsState()
    val useTrash by c.useTrash.collectAsState()
    var confirm by remember { mutableStateOf(false) }

    if (scan == ScanUi.Running) {
        Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 8.dp)
            Spacer(Modifier.height(26.dp))
            Text("Fazendo o raio-x…", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("${formatCount(progress.files)} arquivos • ${formatBytes(progress.bytes)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(progress.dir.ifEmpty { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val safe = groups.filter { it.risk == Risk.SAFE }.sortedByDescending { it.bytes }
    val review = groups.filter { it.risk == Risk.REVIEW }.sortedByDescending { it.bytes }
    val current = groups.firstOrNull { it.id == active } ?: groups.maxByOrNull { it.bytes }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ------------------------------------------------ lista de gavetas
            Column(Modifier.width(400.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text("Vai liberar", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        animatedBytes(sel.totalBytes, 450),
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 46.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("${formatCount(sel.totalCount.toLong())} itens marcados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (dup is DupUi.Running) {
                    Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 10.dp)) {
                        Text("Procurando duplicados… ${(dup as DupUi.Running).percent}%", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { (dup as DupUi.Running).percent / 100f }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (groups.isEmpty()) item { CenterNote("Nada para limpar", "Seu Mac está em dia.", Modifier.fillMaxWidth()) }
                    if (safe.isNotEmpty()) item { SectionLabel("Pode apagar sem medo", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                    items(safe, key = { it.id.name }) { g -> GroupRow(c, g, current?.id == g.id, sel) }
                    if (review.isNotEmpty()) item { SectionLabel("Confira antes de apagar", Modifier.padding(top = 14.dp, bottom = 2.dp)) }
                    items(review, key = { it.id.name }) { g -> GroupRow(c, g, current?.id == g.id, sel) }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }

            // ------------------------------------------------ detalhe da gaveta
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (current == null) {
                    CenterNote("Escolha uma gaveta à esquerda")
                } else {
                    GroupDetail(c, current, sel)
                }
            }
        }

        // ------------------------------------------------ barra de ação
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = useTrash, onCheckedChange = { c.setUseTrash(it) })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Mandar os itens \"confira antes\" para a Lixeira", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (useTrash) "Dá para desfazer pelo Finder. O espaço volta quando você esvaziar a Lixeira."
                    else "Apagados na hora, sem passar pela Lixeira.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BigButton(
                text = if (sel.totalBytes > 0) "Limpar ${formatBytes(sel.totalBytes)}" else "Marque o que quer limpar",
                icon = Icons.Rounded.DeleteForever,
                enabled = sel.totalBytes > 0,
                onClick = { confirm = true },
                modifier = Modifier.width(320.dp),
            )
        }
    }

    if (confirm) {
        val (count, permanent, toTrash) = c.planSummary()
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Limpar ${formatCount(count.toLong())} ${if (count == 1) "item" else "itens"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (permanent > 0) Text("• ${formatBytes(permanent)} serão apagados de vez (não dá para desfazer).")
                    if (toTrash > 0) Text("• ${formatBytes(toTrash)} vão para a Lixeira, e você decide depois se esvazia.")
                    Text("Nada fora desta lista será tocado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; c.cleanSelected() }) {
                    Text("Limpar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun GroupRow(c: MacController, g: MacGroup, isActive: Boolean, sel: SelStats) {
    val selCount = sel.count(g.id)
    val state = when {
        selCount == 0 -> ToggleableState.Off
        selCount >= g.count -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val tint = if (g.risk == Risk.SAFE) MaterialTheme.colorScheme.primary else Brand.Amber
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { c.setActive(g.id) }
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Icon(g.id.icon(), null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(g.id.title(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(g.bytes)} • ${formatCount(g.count.toLong())} ${if (g.count == 1) "item" else "itens"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TriStateCheckbox(state = state, onClick = { c.setGroup(g.id, state != ToggleableState.On) })
    }
}

@Composable
private fun GroupDetail(c: MacController, g: MacGroup, sel: SelStats) {
    val selected by c.selected.collectAsState()
    val rows = remember(g) { buildRows(g.items, g.id == MacGroupId.DUPLICATES) }
    val listState = rememberLazyListState()
    val allOn = sel.count(g.id) >= g.count && g.count > 0

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.id.title(), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    if (g.risk == Risk.SAFE) Pill("seguro", color = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    else Pill("confira")
                }
                Spacer(Modifier.height(4.dp))
                Text(g.id.hint(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { c.setGroup(g.id, !allOn) }) { Text(if (allOn) "Desmarcar tudo" else "Marcar tudo") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
                items(
                    rows,
                    key = { r ->
                        when (r) {
                            is Row2.Header -> "h${r.setId}"
                            is Row2.Item -> r.entry.path
                        }
                    },
                ) { r ->
                    when (r) {
                        is Row2.Header -> Text(
                            "${r.copies} cópias de ${formatBytes(r.size)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
                        )
                        is Row2.Item -> EntryRow(r.entry, r.entry.path in selected) { c.toggle(r.entry.path) }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private val GENERIC_NAMES = setOf("cache", "caches", "logs", "code cache", "gpucache", "cachestorage", "cacheddata", "shadercache", "grshadercache", "dawncache", "backup", "data", "tmp", "temp")

/** "Cache" sozinho não diz nada: mostra "Slack › Cache". */
private fun displayName(e: FileEntry): String {
    val n = e.name
    if (n.lowercase() !in GENERIC_NAMES) return n
    val parent = e.parent.substringAfterLast('/')
    return if (parent.isNotEmpty()) "$parent › $n" else n
}

@Composable
fun EntryRow(entry: FileEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(entry.path, size = 46.dp, isDir = entry.isDir)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(displayName(entry), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(prettyPath(entry.parent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatBytes(entry.size) + if (entry.modified > 0) " • ${ago(entry.modified)}" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.keep) {
                    Spacer(Modifier.width(8.dp))
                    Pill("MANTER", color = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        IconButton(onClick = { Ops.reveal(entry.path) }) {
            Icon(Icons.Rounded.OpenInNew, "Mostrar no Finder", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (entry.keep) {
            Icon(Icons.Rounded.Check, "Cópia mantida", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
        } else {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}
