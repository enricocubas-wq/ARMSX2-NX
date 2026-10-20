package app.faxina.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.faxina.data.Perms
import app.faxina.vm.MainViewModel

@Composable
fun PermissionScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermissions() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(42.dp)) }
        Text("Vamos liberar espaço", style = MaterialTheme.typography.displaySmall)
        Text(
            "Para achar o que está pesando no celular, o Faxina precisa enxergar seus arquivos.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Bullet("Só olhamos nome, tamanho e data — nada é enviado para fora do celular.")
        Bullet("Nada é apagado sem você ver a lista e confirmar.")
        Bullet("Sem anúncios, sem conta, sem IA.")
        Spacer(Modifier.height(8.dp))
        BigButton(
            text = "Permitir acesso aos arquivos",
            icon = Icons.Rounded.Lock,
            onClick = {
                if (Perms.needsRuntimeRequest) {
                    legacy.launch(Perms.legacyPermissions)
                } else {
                    try { ctx.startActivity(Perms.allFilesIntent(ctx)) } catch (_: Exception) {
                        try { ctx.startActivity(Perms.allFilesFallbackIntent()) } catch (_: Exception) {}
                    }
                }
            },
        )
        if (!Perms.needsRuntimeRequest) {
            Text(
                "Vai abrir uma tela do Android: ative a chave \"Permitir acesso para gerenciar todos os arquivos\" e volte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
    }
}
