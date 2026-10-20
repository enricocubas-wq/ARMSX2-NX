package app.faxina.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.faxina.core.FileCategory
import app.faxina.core.GroupId
import app.faxina.core.formatBytes
import app.faxina.vm.DupUi
import app.faxina.vm.MainViewModel
import app.faxina.vm.ReviewFilter
import app.faxina.vm.Screen
import app.faxina.vm.ScanUi

@Composable
fun HomeScreen(vm: MainViewModel) {
    val usage by vm.usage.collectAsStateWithLifecycle()
    val cats by vm.bytesByCategory.collectAsStateWithLifecycle()
    val appsBytes by vm.appsBytes.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val dup by vm.dup.collectAsStateWithLifecycle()
    val lifetime by vm.lifetimeFreed.collectAsStateWithLifecycle()
    val sel by vm.selStats.collectAsStateWithLifecycle()

    val scanned = scan == ScanUi.Done
    val total = usage.total.coerceAtLeast(1)

    // --- segmentos do anel
    val segments = remember(usage, cats, appsBytes, scanned) {
        val used = usage.used
        if (!scanned || cats.isEmpty()) {
            listOf(RingSegment(used.toFloat() / total, Brand.Slate))
        } else {
            val img = cats[FileCategory.IMAGE] ?: 0
            val vid = cats[FileCategory.VIDEO] ?: 0
            val aud = cats[FileCategory.AUDIO] ?: 0
            val docs = (cats[FileCategory.DOCUMENT] ?: 0) + (cats[FileCategory.ARCHIVE] ?: 0)
            val apps = (appsBytes ?: 0) + (cats[FileCategory.APK] ?: 0)
            val known = img + vid + aud + docs + apps
            val scale = if (known > used && known > 0) used.toDouble() / known else 1.0
            val parts = listOf(
                img to Brand.Blue, vid to Brand.Violet, aud to Brand.Pink, docs to Brand.Amber, apps to Brand.Teal,
            ).map { (b, c) -> (b * scale).toLong() to c }
            val other = (used - parts.sumOf { it.first }).coerceAtLeast(0)
            (parts + (other to Brand.Slate)).filter { it.first > 0 }.map { (b, c) -> RingSegment(b.toFloat() / total, c) }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        // --- topo
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Faxina", style = MaterialTheme.typography.headlineSmall)
                Text("Limpeza de armazenamento", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.rescan() }, enabled = scan != ScanUi.Running) {
                Icon(Icons.Rounded.Refresh, "Analisar de novo")
            }
        }

        Spacer(Modifier.height(20.dp))

        // --- anel
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StorageRing(segments, Modifier.size(272.dp), stroke = 24.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        animatedBytes(usage.free),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp),
                        maxLines = 1,
                    )
                    Text("livres", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatBytes(usage.used)} usados de ${formatBytes(usage.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Legend(cats, appsBytes, scanned)

        Spacer(Modifier.height(18.dp))

        // --- estado da análise
        when {
            scan == ScanUi.Running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Analisando… ${"%,d".format(progress.files).replace(',', '.')} arquivos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            scanned && sel.foundBytes > 0 -> {
                Text(
                    "Encontrei ${formatBytes(sel.foundBytes)} que dá para liberar",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        BigButton(
            text = "Limpar agora",
            icon = Icons.Rounded.CleaningServices,
            onClick = { vm.openWithScan(Screen.Review(ReviewFilter.ALL)) },
            height = 68.dp,
        )

        if (lifetime > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Você já liberou ${formatBytes(lifetime)} com o Faxina",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Ferramentas")
        Spacer(Modifier.height(10.dp))

        val dupBytes = groups.firstOrNull { it.id == GroupId.DUPLICATES }?.bytes
        val bigBytes = groups.firstOrNull { it.id == GroupId.BIG_FILES }?.bytes
        val msgBytes = groups.filter { it.id in MESSENGER_GROUPS }.sumOf { it.bytes }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                Icons.Rounded.ContentCopy, "Duplicados",
                when {
                    dupBytes != null -> formatBytes(dupBytes)
                    dup is DupUi.Running -> "buscando ${(dup as DupUi.Running).percent}%"
                    scanned -> "nenhum"
                    else -> "analisar"
                },
                Brand.Violet, Modifier.weight(1f),
            ) { vm.openWithScan(Screen.Group(GroupId.DUPLICATES)) }
            Tile(
                Icons.Rounded.Inventory2, "Arquivos grandes",
                if (scanned) formatBytes(bigBytes ?: 0) else "analisar",
                Brand.Amber, Modifier.weight(1f),
            ) { vm.openWithScan(Screen.Group(GroupId.BIG_FILES)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                Icons.Rounded.Chat, "WhatsApp e Telegram",
                if (scanned) formatBytes(msgBytes) else "analisar",
                Brand.Mint, Modifier.weight(1f),
            ) { vm.openWithScan(Screen.Review(ReviewFilter.MESSENGERS)) }
            Tile(Icons.Rounded.Apps, "Apps", "tamanho e uso", Brand.Teal, Modifier.weight(1f)) { vm.go(Screen.Apps) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Icons.Rounded.Email, "E-mail", "limpar caixa", Brand.Pink, Modifier.weight(1f)) { vm.go(Screen.Mail) }
            Tile(Icons.Rounded.FolderOpen, "Explorar pastas", "raio-x do espaço", Brand.Blue, Modifier.weight(1f)) {
                vm.openWithScan(Screen.Explorer(""))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Tudo roda no seu celular, sem anúncios e sem IA. A única conexão de rede é a do e-mail, direto com o seu provedor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Legend(cats: Map<FileCategory, Long>, appsBytes: Long?, scanned: Boolean) {
    if (!scanned || cats.isEmpty()) return
    val items = listOf(
        Triple("Fotos", Brand.Blue, cats[FileCategory.IMAGE] ?: 0),
        Triple("Vídeos", Brand.Violet, cats[FileCategory.VIDEO] ?: 0),
        Triple("Áudio", Brand.Pink, cats[FileCategory.AUDIO] ?: 0),
        Triple("Documentos", Brand.Amber, (cats[FileCategory.DOCUMENT] ?: 0) + (cats[FileCategory.ARCHIVE] ?: 0)),
        Triple("Apps", Brand.Teal, (appsBytes ?: 0) + (cats[FileCategory.APK] ?: 0)),
    ).filter { it.third > 0 }
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
            row.forEach { (label, color, bytes) ->
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$label ${formatBytes(bytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, value: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
