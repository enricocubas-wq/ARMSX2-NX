package app.faxina.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.faxina.core.CleanGroup
import app.faxina.core.FileCategory
import app.faxina.core.FileEntry
import app.faxina.core.GroupId
import app.faxina.core.Risk
import app.faxina.core.categoryOf
import app.faxina.core.formatBytes
import app.faxina.data.Openers
import app.faxina.vm.DeleteUi
import app.faxina.vm.DupUi
import app.faxina.vm.MainViewModel
import app.faxina.vm.ReviewFilter
import app.faxina.vm.ScanUi
import app.faxina.vm.Screen

// ====================================================================== varredura

@Composable
fun ScanScreen(vm: MainViewModel, then: Screen?) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    LaunchedEffect(scan) {
        when (scan) {
            ScanUi.Done -> vm.replace(then ?: Screen.Review(ReviewFilter.ALL))
            ScanUi.Idle -> vm.back()
            ScanUi.Running -> {}
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(150.dp), strokeWidth = 10.dp, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text("Fazendo o raio-x…", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${formatCount(progress.files)} arquivos • ${formatBytes(progress.bytes)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            progress.dir.ifEmpty { "…" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(36.dp))
        TextButton(onClick = { vm.cancelScan() }) { Text("Cancelar") }
    }
}

fun formatCount(n: Long): String = "%,d".format(n).replace(',', '.')

// ====================================================================== revisão

@Composable
fun ReviewScreen(vm: MainViewModel, filter: ReviewFilter) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val sel by vm.selStats.collectAsStateWithLifecycle()
    val dup by vm.dup.collectAsStateWithLifecycle()
    val delete by vm.delete.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    val visible = remember(groups, filter) {
        val base = if (filter == ReviewFilter.MESSENGERS) groups.filter { it.id in MESSENGER_GROUPS } else groups
        base.sortedByDescending { it.bytes }
    }
    val safe = visible.filter { it.risk == Risk.SAFE }
    val review = visible.filter { it.risk == Risk.REVIEW }
    // na tela completa o total conta cada arquivo uma vez só, mesmo que apareça em dois grupos
    val visibleSelected = if (filter == ReviewFilter.ALL) sel.totalBytes else visible.sumOf { sel.bytes(it.id) }
    val visibleSelectedCount = if (filter == ReviewFilter.ALL) sel.totalCount else visible.sumOf { sel.count(it.id) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(if (filter == ReviewFilter.MESSENGERS) "WhatsApp e Telegram" else "Limpeza", onBack = { vm.back() })

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Vai liberar", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        animatedBytes(visibleSelected, 450),
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${formatCount(visibleSelectedCount.toLong())} itens marcados",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (dup is DupUi.Running && filter == ReviewFilter.ALL) {
                item {
                    Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Procurando arquivos duplicados… ${(dup as DupUi.Running).percent}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (dup as DupUi.Running).percent / 100f },
                                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                            )
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(
                            "Nada para limpar aqui. Seu celular está em dia!",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
            if (safe.isNotEmpty()) {
                item { SectionLabel("Pode apagar sem medo", Modifier.padding(top = 8.dp)) }
                items(safe, key = { it.id.name }) { g -> GroupRow(g, vm) }
            }
            if (review.isNotEmpty()) {
                item { SectionLabel("Confira antes de apagar", Modifier.padding(top = 12.dp)) }
                items(review, key = { it.id.name }) { g -> GroupRow(g, vm) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            BigButton(
                text = if (visibleSelected > 0) "Limpar ${formatBytes(visibleSelected)}" else "Marque o que quer limpar",
                icon = Icons.Rounded.DeleteForever,
                enabled = visibleSelected > 0 && delete is DeleteUi.Idle,
                onClick = { confirm = true },
            )
        }
    }

    if (confirm) {
        ConfirmDelete(
            count = visibleSelectedCount,
            bytes = visibleSelected,
            onCancel = { confirm = false },
            onConfirm = {
                confirm = false
                if (filter == ReviewFilter.MESSENGERS) {
                    vm.deleteEntries(
                        groups.filter { it.id in MESSENGER_GROUPS }.flatMap { g -> g.items }
                            .filter { !it.keep && it.path in vm.selected.value }.distinctBy { it.path },
                    )
                } else {
                    vm.deleteSelected()
                }
            },
        )
    }
    DeletingOverlay(delete)
}

@Composable
private fun GroupRow(g: CleanGroup, vm: MainViewModel) {
    val sel by vm.selStats.collectAsStateWithLifecycle()
    val selCount = sel.count(g.id)
    val state = when {
        selCount == 0 -> ToggleableState.Off
        selCount >= g.count -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val tint = if (g.risk == Risk.SAFE) MaterialTheme.colorScheme.primary else Brand.Amber
    Panel(Modifier.fillMaxWidth(), onClick = { vm.go(Screen.Group(g.id)) }) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(g.id.icon(), null, tint = tint, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.id.title(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatBytes(g.bytes)} • ${formatCount(g.count.toLong())} ${if (g.count == 1) "item" else "itens"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TriStateCheckbox(state = state, onClick = { vm.setGroup(g.id, state != ToggleableState.On) })
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
        }
    }
}

@Composable
fun ConfirmDelete(count: Int, bytes: Long, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Apagar ${formatCount(count.toLong())} ${if (count == 1) "item" else "itens"}?") },
        text = {
            Text(
                "Vão sair do celular ${formatBytes(bytes)}. Isso não dá para desfazer — apagar daqui não manda para a lixeira.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Apagar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

@Composable
fun DeletingOverlay(state: DeleteUi) {
    if (state !is DeleteUi.Running) return
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.86f)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 7.dp)
            Spacer(Modifier.height(20.dp))
            Text("Limpando… ${state.done}/${state.total}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ====================================================================== lista de um grupo

private sealed interface Row2 {
    data class Header(val setId: Int, val size: Long, val copies: Int) : Row2
    data class Item(val entry: FileEntry) : Row2
}

@Composable
fun GroupScreen(vm: MainViewModel, id: GroupId) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val sel by vm.selStats.collectAsStateWithLifecycle()
    val dup by vm.dup.collectAsStateWithLifecycle()
    val delete by vm.delete.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<FileCategory?>(null) }

    val group = groups.firstOrNull { it.id == id }

    // grupo esvaziado depois de apagar: volta sozinho
    LaunchedEffect(group == null, dup, scan) {
        if (group == null && scan == ScanUi.Done && dup !is DupUi.Running) {
            if (id != GroupId.DUPLICATES && id != GroupId.BIG_FILES) vm.back()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(
            id.title(),
            onBack = { vm.back() },
            actions = {
                if (group != null) {
                    val all = sel.count(id) >= group.count && group.count > 0
                    TextButton(onClick = { vm.setGroup(id, !all) }) { Text(if (all) "Desmarcar tudo" else "Marcar tudo") }
                }
            },
        )

        if (group == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (id == GroupId.DUPLICATES && dup is DupUi.Running) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Procurando duplicados… ${(dup as DupUi.Running).percent}%", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Comparando o conteúdo dos arquivos, byte a byte. Só apagamos cópias idênticas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nada por aqui", style = MaterialTheme.typography.titleMedium)
                }
            }
            return@Column
        }

        Text(
            id.hint(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (id == GroupId.BIG_FILES) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip("Tudo", typeFilter == null) { typeFilter = null }
                TypeChip("Vídeos", typeFilter == FileCategory.VIDEO) { typeFilter = FileCategory.VIDEO }
                TypeChip("Fotos", typeFilter == FileCategory.IMAGE) { typeFilter = FileCategory.IMAGE }
                TypeChip("Outros", typeFilter == FileCategory.OTHER) { typeFilter = FileCategory.OTHER }
            }
        }

        val rows = remember(group, typeFilter) {
            val items = if (typeFilter == null) group.items else group.items.filter {
                val c = categoryOf(it.name)
                if (typeFilter == FileCategory.OTHER) c != FileCategory.VIDEO && c != FileCategory.IMAGE else c == typeFilter
            }
            buildRows(items, id == GroupId.DUPLICATES)
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            items(rows, key = { r ->
                when (r) {
                    is Row2.Header -> "h${r.setId}"
                    is Row2.Item -> r.entry.path
                }
            }) { r ->
                when (r) {
                    is Row2.Header -> Text(
                        "${r.copies} cópias de ${formatBytes(r.size)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
                    )
                    is Row2.Item -> EntryRow(
                        entry = r.entry,
                        checked = r.entry.path in selected,
                        onToggle = { vm.toggle(r.entry.path) },
                        onOpen = { if (!r.entry.isDir) Openers.openFile(ctx, r.entry.path) },
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            val bytes = sel.bytes(id)
            BigButton(
                text = if (bytes > 0) "Apagar ${formatBytes(bytes)}" else "Marque o que quer apagar",
                icon = Icons.Rounded.DeleteForever,
                enabled = bytes > 0 && delete is DeleteUi.Idle,
                onClick = { confirm = true },
            )
        }
    }

    if (confirm && group != null) {
        ConfirmDelete(
            count = sel.count(id),
            bytes = sel.bytes(id),
            onCancel = { confirm = false },
            onConfirm = {
                confirm = false
                vm.deleteEntries(group.items.filter { !it.keep && it.path in selected })
            },
        )
    }
    DeletingOverlay(delete)
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
private fun TypeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun EntryRow(entry: FileEntry, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(entry.path, size = 52.dp, isDir = entry.isDir)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                shortParent(entry.parent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatBytes(entry.size) + if (entry.modified > 0) " • ${ago(entry.modified)}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.keep) {
                    Spacer(Modifier.width(8.dp))
                    Pill("MANTER", color = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (entry.keep) {
            Icon(Icons.Rounded.Check, "Cópia mantida", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
        } else {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors())
        }
    }
}

/** "/storage/emulated/0/DCIM/Camera" → "DCIM/Camera". */
fun shortParent(parent: String): String {
    val marker = "/0/"
    val i = parent.indexOf(marker)
    return if (i >= 0) parent.substring(i + marker.length) else parent.removePrefix("/storage/")
}

// ====================================================================== pronto

@Composable
fun DoneScreen(vm: MainViewModel) {
    val freed by vm.lastFreed.collectAsStateWithLifecycle()
    val failed by vm.lastFailed.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val remaining = groups.sumOf { it.bytes }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(120.dp)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(72.dp)) }
        Spacer(Modifier.height(28.dp))
        Text(if (freed > 0) "Espaço liberado" else "Nada foi apagado", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            animatedBytes(freed, 1100),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text("Agora você tem ${formatBytes(usage.free)} livres", style = MaterialTheme.typography.bodyLarge)
        if (failed > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "$failed ${if (failed == 1) "item não pôde" else "itens não puderam"} ser apagado${if (failed == 1) "" else "s"} (em uso ou protegido pelo sistema).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(36.dp))
        BigButton(text = "Concluir", onClick = { vm.home() })
        if (remaining > 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.replace(Screen.Review(ReviewFilter.ALL)) }) {
                Text("Ainda há ${formatBytes(remaining)} para conferir")
            }
        }
    }
}
