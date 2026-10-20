package app.faxina.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.faxina.core.formatBytes
import app.faxina.data.AppItem
import app.faxina.data.Openers
import app.faxina.data.Perms
import app.faxina.vm.AppsUi
import app.faxina.vm.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppSort(val label: String) { SIZE("Maiores"), UNUSED("Menos usados"), CACHE("Mais cache") }

@Composable
fun AppIcon(pkg: String, size: Dp = 46.dp) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun AppsScreen(vm: MainViewModel) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var sort by remember { mutableStateOf(AppSort.SIZE) }

    // carrega ao abrir e de novo ao voltar das configurações do sistema (permissão, desinstalação, cache)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.loadApps() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader("Apps", onBack = { vm.back() })
        when (val a = apps) {
            AppsUi.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is AppsUi.Ready -> {
                val list = remember(a, sort) {
                    when (sort) {
                        AppSort.SIZE -> a.items.sortedByDescending { it.total ?: -1 }
                        AppSort.UNUSED -> a.items.sortedBy { it.lastUsed ?: 0L }
                        AppSort.CACHE -> a.items.sortedByDescending { it.cache ?: -1 }
                    }
                }
                val totalCache = a.items.sumOf { it.cache ?: 0L }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Cached, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (a.hasStats) "Cache de todos os apps: ${formatBytes(totalCache)}" else "Cache dos apps",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "O Android não deixa um app limpar o cache de outros sozinho. Tentamos abrir o botão de limpeza do sistema; se o seu celular não tiver, toque num app abaixo e use Armazenamento › Limpar cache.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(10.dp))
                                BigButton(
                                    text = "Limpar cache dos apps",
                                    height = 52.dp,
                                    onClick = {
                                        if (!Openers.clearAllAppCache(ctx)) {
                                            Toast.makeText(ctx, "Seu celular não tem esse atalho. Abra um app da lista e toque em Limpar cache.", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (!a.hasStats) {
                        item {
                            Panel(Modifier.fillMaxWidth(), onClick = {
                                try { ctx.startActivity(Perms.usageAccessIntent(ctx)) } catch (_: Exception) {
                                    try { ctx.startActivity(Perms.usageAccessFallbackIntent()) } catch (_: Exception) {}
                                }
                            }) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Ver tamanho e último uso", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Toque aqui e ative o \"Acesso ao uso\" do Faxina. Sem isso o Android esconde o tamanho e o último uso dos apps.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            AppSort.values().forEach { s -> SortChip(s.label, sort == s) { sort = s } }
                        }
                    }
                    items(list, key = { it.pkg }) { app -> AppRow(app, a.hasStats, ctx) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun AppRow(app: AppItem, hasStats: Boolean, ctx: android.content.Context) {
    Panel(Modifier.fillMaxWidth(), onClick = { Openers.appDetails(ctx, app.pkg) }) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = ArrayList<String>()
                app.total?.let { parts.add(formatBytes(it)) }
                if ((app.cache ?: 0) > 1_000_000) parts.add("cache ${formatBytes(app.cache!!)}")
                if (hasStats) parts.add(if (app.lastUsed == null) "sem uso recente" else "usado ${ago(app.lastUsed)}")
                Text(
                    parts.joinToString(" • ").ifEmpty { "toque para ver detalhes" },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { Openers.uninstall(ctx, app.pkg) }) {
                Icon(Icons.Rounded.Delete, "Desinstalar ${app.label}", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
