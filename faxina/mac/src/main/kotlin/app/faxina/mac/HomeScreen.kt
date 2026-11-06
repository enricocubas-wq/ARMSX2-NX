package app.faxina.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import app.faxina.core.FileCategory
import app.faxina.core.formatBytes
import app.faxina.core.mac.MacGroupId

private val SystemColor = Color(0xFF3A4A5E)

@Composable
fun HomeScreen(c: MacController) {
    val usage by c.usage.collectAsState()
    val scan by c.scan.collectAsState()
    val progress by c.progress.collectAsState()
    val res by c.result.collectAsState()
    val groups by c.groups.collectAsState()
    val dup by c.dup.collectAsState()
    val sel by c.selStats.collectAsState()
    val fda by c.fda.collectAsState()
    val lifetime by c.lifetimeFreed.collectAsState()

    val scanned = scan == ScanUi.Done && res != null
    val total = usage.total.coerceAtLeast(1)

    val parts: List<Triple<String, Color, Long>> = remember(usage, res, scanned) {
        val r = res
        if (!scanned || r == null) {
            listOf(Triple("Usado", Brand.Slate, usage.used))
        } else {
            val cat = r.bytesByCategory
            val img = cat[FileCategory.IMAGE] ?: 0
            val vid = cat[FileCategory.VIDEO] ?: 0
            val aud = cat[FileCategory.AUDIO] ?: 0
            val docs = (cat[FileCategory.DOCUMENT] ?: 0) + (cat[FileCategory.ARCHIVE] ?: 0)
            val userFiles = img + vid + aud + docs
            val others = (r.homeBytes - r.libraryBytes - userFiles).coerceAtLeast(0)
            val system = (usage.used - r.homeBytes).coerceAtLeast(0)
            val raw = listOf(
                Triple("Fotos", Brand.Blue, img), Triple("Vídeos", Brand.Violet, vid), Triple("Áudio", Brand.Pink, aud),
                Triple("Documentos", Brand.Amber, docs), Triple("Outros arquivos", Brand.Slate, others),
                Triple("Biblioteca (apps)", Brand.Teal, r.libraryBytes), Triple("Sistema", SystemColor, system),
            ).filter { it.third > 0 }
            val sum = raw.sumOf { it.third }
            val scale = if (sum > usage.used && sum > 0) usage.used.toDouble() / sum else 1.0
            raw.map { Triple(it.first, it.second, (it.third * scale).toLong()) }
        }
    }
    val segments = parts.map { RingSegment(it.third.toFloat() / total, it.second) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Seu Mac", style = MaterialTheme.typography.headlineLarge)
                Text("Limpeza de armazenamento", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { c.rescan() }, enabled = scan != ScanUi.Running) { Icon(Icons.Rounded.Refresh, "Analisar de novo") }
        }
        Spacer(Modifier.height(20.dp))

        if (fda == false) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Brand.Amber, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Libere o Acesso Total ao Disco", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Sem isso o macOS esconde a Lixeira, o Mail e os caches protegidos. Em Ajustes do Sistema › Privacidade e Segurança › Acesso Total ao Disco, ative o Faxina e abra o app de novo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { Ops.openFullDiskAccessSettings() }) { Text("Abrir ajustes", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(20.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
            StorageRing(segments, Modifier.size(300.dp), stroke = 26.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(animatedBytes(usage.free), style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp), maxLines = 1)
                    Text("livres", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatBytes(usage.used)} usados de ${formatBytes(usage.total)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    scan == ScanUi.Running -> {
                        Text("Fazendo o raio-x…", style = MaterialTheme.typography.headlineMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        Text(
                            "${formatCount(progress.files)} arquivos • ${formatBytes(progress.bytes)}",
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            progress.dir.ifEmpty { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    scanned && sel.foundBytes > 0 -> {
                        Text("Dá para liberar", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            animatedBytes(sel.foundBytes),
                            style = MaterialTheme.typography.displayMedium.copy(fontSize = 56.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${formatBytes(sel.totalBytes)} já vêm marcados como seguros. O resto é com você.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    scanned -> Text("Seu Mac está em dia!", style = MaterialTheme.typography.headlineMedium)
                    else -> Text("Pronto para analisar.", style = MaterialTheme.typography.headlineMedium)
                }
                BigButton(
                    text = "Limpar agora",
                    icon = Icons.Rounded.CleaningServices,
                    onClick = { c.go(Tab.CLEAN) },
                    modifier = Modifier.width(300.dp),
                    height = 62.dp,
                )
                if (lifetime > 0) {
                    Text(
                        "Você já liberou ${formatBytes(lifetime)} com o Faxina",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        if (scanned) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { (label, color, bytes) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(color)
                                Spacer(Modifier.width(6.dp))
                                Text("$label ${formatBytes(bytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Atalhos")
        Spacer(Modifier.height(10.dp))

        fun gb(id: MacGroupId): String = groups.firstOrNull { it.id == id }?.bytes?.let { formatBytes(it) } ?: if (scanned) "nada" else "—"
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(Icons.Rounded.ContentCopy, "Duplicados", if (dup is DupUi.Running && gb(MacGroupId.DUPLICATES) == "nada") "buscando ${(dup as DupUi.Running).percent}%" else gb(MacGroupId.DUPLICATES), Brand.Violet, Modifier.weight(1f)) { c.openGroup(MacGroupId.DUPLICATES) }
            Tile(Icons.Rounded.Inventory2, "Arquivos grandes", gb(MacGroupId.BIG_FILES), Brand.Amber, Modifier.weight(1f)) { c.openGroup(MacGroupId.BIG_FILES) }
            Tile(Icons.Rounded.Code, "Desenvolvimento", gb(MacGroupId.DEV_CACHES), Brand.Teal, Modifier.weight(1f)) { c.openGroup(MacGroupId.DEV_CACHES) }
            Tile(Icons.Rounded.PhoneIphone, "Backups de iPhone", gb(MacGroupId.IOS_BACKUPS), Brand.Pink, Modifier.weight(1f)) { c.openGroup(MacGroupId.IOS_BACKUPS) }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(Icons.Rounded.Delete, "Lixeira", gb(MacGroupId.TRASH), Brand.Red, Modifier.weight(1f)) { c.openGroup(MacGroupId.TRASH) }
            Tile(Icons.Rounded.Apps, "Apps", "tamanho e uso", Brand.Blue, Modifier.weight(1f)) { c.go(Tab.APPS) }
            Tile(Icons.Rounded.Email, "E-mail", "limpar caixa", Brand.Mint, Modifier.weight(1f)) { c.go(Tab.MAIL) }
            Tile(Icons.Rounded.FolderOpen, "Explorar pastas", "raio-x do espaço", Brand.Slate, Modifier.weight(1f)) { c.go(Tab.FOLDERS) }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            "Tudo roda no seu Mac, sem anúncios e sem IA. A única conexão de rede é a do e-mail, direto com o seu provedor.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, value: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
