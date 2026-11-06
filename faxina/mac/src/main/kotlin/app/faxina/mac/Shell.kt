package app.faxina.mac

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.faxina.core.formatBytes
import kotlinx.coroutines.delay

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Rounded.Home
    Tab.CLEAN -> Icons.Rounded.CleaningServices
    Tab.FOLDERS -> Icons.Rounded.FolderOpen
    Tab.APPS -> Icons.Rounded.Apps
    Tab.MAIL -> Icons.Rounded.Email
}

@Composable
fun App(c: MacController, mail: MailController) {
    val tab by c.tab.collectAsState()
    val busy by c.busy.collectAsState()
    val report by c.report.collectAsState()
    val toast by c.toast.collectAsState()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(c, Modifier.width(236.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Crossfade(tab, animationSpec = tween(160), label = "tab") { t ->
                    when (t) {
                        Tab.HOME -> HomeScreen(c)
                        Tab.CLEAN -> CleanScreen(c)
                        Tab.FOLDERS -> ExplorerScreen(c)
                        Tab.APPS -> AppsScreen(c)
                        Tab.MAIL -> MailScreen(mail)
                    }
                }
            }
        }

        busy?.let { b ->
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.86f)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 6.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (b.total > 0) "${b.message} ${b.done}/${b.total}" else b.message,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        report?.let { r -> ReportDialog(c, r) }

        LaunchedEffect(toast) {
            if (toast != null) {
                delay(3200)
                c.dismissToast()
            }
        }
        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
        ) {
            Text(
                toast ?: "",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { c.dismissToast() }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Sidebar(c: MacController, modifier: Modifier) {
    val tab by c.tab.collectAsState()
    val fda by c.fda.collectAsState()
    val lifetime by c.lifetimeFreed.collectAsState()
    val sel by c.selStats.collectAsState()

    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Faxina", style = MaterialTheme.typography.titleLarge)
                Text("para Mac", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (t in Tab.values()) {
            val selected = t == tab
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { c.go(t) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    t.icon(), null, modifier = Modifier.size(22.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    t.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (t == Tab.CLEAN && sel.foundBytes > 0) {
                    Text(formatBytes(sel.foundBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (fda == false) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { Ops.openFullDiskAccessSettings() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Acesso limitado", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (lifetime > 0) {
            Text(
                "Já liberado: ${formatBytes(lifetime)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun ReportDialog(c: MacController, r: CleanReport) {
    val done = r.freed > 0 || r.inTrash > 0 || r.trashEmptied
    AlertDialog(
        onDismissRequest = { c.dismissReport() },
        title = {
            Text(
                when {
                    r.trashEmptied -> "Lixeira esvaziada"
                    r.freed > 0 && r.inTrash == 0L -> "Espaço liberado"
                    r.inTrash > 0 -> "Quase lá"
                    else -> "Nada foi apagado"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.freed > 0) {
                    Text(formatBytes(r.freed), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text("liberados agora.", style = MaterialTheme.typography.bodyMedium)
                }
                if (r.inTrash > 0) {
                    Text(
                        "${formatBytes(r.inTrash)} foram para a Lixeira. O espaço só volta quando a Lixeira for esvaziada.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (r.trashEmptyFailed) {
                    Text(
                        "Não consegui esvaziar a Lixeira sozinho. No Finder, use Finder › Esvaziar Lixeira.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (r.failed > 0) {
                    Text(
                        "${r.failed} ${if (r.failed == 1) "item não pôde" else "itens não puderam"} ser removido(s): estão em uso ou protegidos pelo macOS. " +
                            "Conceder \"Acesso Total ao Disco\" ao Faxina costuma resolver.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!done && r.failed == 0) Text("Nenhum item marcado.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (r.inTrash > 0) {
                TextButton(onClick = { c.emptyTrashNow() }) { Text("Esvaziar Lixeira agora", fontWeight = FontWeight.Bold) }
            } else {
                TextButton(onClick = { c.dismissReport() }) { Text("Fechar") }
            }
        },
        dismissButton = {
            if (r.inTrash > 0) TextButton(onClick = { c.dismissReport() }) { Text("Deixar na Lixeira") }
        },
    )
}

@Composable
fun CenterNote(title: String, text: String? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
