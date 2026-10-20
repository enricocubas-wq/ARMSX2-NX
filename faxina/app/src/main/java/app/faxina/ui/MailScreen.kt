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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.faxina.core.formatBytes
import app.faxina.core.mail.MailPreset
import app.faxina.core.mail.MailReport
import app.faxina.data.Openers
import app.faxina.vm.MailUi
import app.faxina.vm.MailViewModel
import app.faxina.vm.MainViewModel
import app.faxina.vm.Provider

@Composable
fun MailScreen(main: MainViewModel, vm: MailViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onOpen() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(
            "Limpar e-mail",
            onBack = { main.back() },
            actions = {
                if (ui is MailUi.Ready) {
                    IconButton(onClick = { vm.scan() }) { Icon(Icons.Rounded.Refresh, "Analisar de novo") }
                }
                if (account != null) {
                    IconButton(onClick = { vm.signOut() }) { Icon(Icons.AutoMirrored.Rounded.Logout, "Sair da conta") }
                }
            },
        )
        when (val s = ui) {
            MailUi.NoAccount -> MailSetup(vm)
            is MailUi.Working -> MailWorking(s)
            is MailUi.Failed -> MailFailed(s, vm)
            is MailUi.Ready -> MailReady(s.report, vm)
        }
    }
}

// ---------------------------------------------------------------- conectar

@Composable
private fun MailSetup(vm: MailViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var email by rememberSaveable { mutableStateOf(account?.email ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf(account?.provider ?: Provider.GMAIL) }
    var host by rememberSaveable { mutableStateOf(account?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((account?.port ?: 993).toString()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Conecte seu e-mail", style = MaterialTheme.typography.headlineMedium)
        Text(
            "O Faxina lê só os cabeçalhos (remetente, tamanho, data) direto do seu provedor. Nada passa por servidor nosso e nada é enviado para IA.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ProviderChip("Gmail", provider == Provider.GMAIL) { provider = Provider.GMAIL }
            ProviderChip("Outlook", provider == Provider.OUTLOOK) { provider = Provider.OUTLOOK }
            ProviderChip("Yahoo", provider == Provider.YAHOO) { provider = Provider.YAHOO }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ProviderChip("iCloud", provider == Provider.ICLOUD) { provider = Provider.ICLOUD }
            ProviderChip("UOL", provider == Provider.UOL) { provider = Provider.UOL }
            ProviderChip("Outro", provider == Provider.OTHER) { provider = Provider.OTHER }
        }

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                if (provider == Provider.GMAIL || provider == Provider.OTHER || it.contains('@')) {
                    val d = Provider.detect(it)
                    if (d != Provider.OTHER) provider = d
                }
            },
            label = { Text("Seu e-mail") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Senha de app") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Mostrar") } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider == Provider.OTHER) {
            OutlinedTextField(
                value = host, onValueChange = { host = it }, label = { Text("Servidor IMAP (ex.: imap.seudominio.com)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Porta (SSL, normalmente 993)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
        }

        Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Por que \"senha de app\"?", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Provedores não deixam apps usarem a senha normal. Uma senha de app é uma senha só para o Faxina: você cria na sua conta (com verificação em duas etapas ligada) e pode apagar quando quiser. Espaços na senha são ignorados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                provider.helpUrl?.let { url ->
                    TextButton(onClick = { Openers.openUrl(ctx, url) }) { Text("Criar senha de app no ${provider.label}") }
                }
            }
        }

        BigButton(
            text = "Conectar e analisar",
            icon = Icons.Rounded.Email,
            onClick = { vm.connect(email, password, provider, host, port.toIntOrNull() ?: 993) },
            enabled = email.contains('@') && password.isNotBlank(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProviderChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

// ---------------------------------------------------------------- andamento / erro

@Composable
private fun MailWorking(s: MailUi.Working) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (s.total > 0) {
            Text("${formatCount(s.done.toLong())} / ${formatCount(s.total.toLong())}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(20.dp))
        Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Só cabeçalhos são lidos — nenhum anexo é baixado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MailFailed(s: MailUi.Failed, vm: MailViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
            Text(s.message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (!s.authProblem) BigButton(text = "Tentar de novo", onClick = { vm.scan() })
        BigButton(
            text = if (s.authProblem) "Digitar a senha de novo" else "Trocar conta",
            onClick = { vm.editAccount() },
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- resultado

private fun presetTitle(p: MailPreset, gmail: Boolean) = when (p) {
    MailPreset.PROMOTIONS -> "Promoções"
    MailPreset.SOCIAL -> "Redes sociais"
    MailPreset.NEWSLETTERS -> if (gmail) "Newsletters e listas" else "Newsletters e propagandas"
    MailPreset.BIG -> "E-mails com mais de 5 MB"
    MailPreset.OLD_READ -> "Lidos há mais de 2 anos"
}

private fun presetHint(p: MailPreset) = when (p) {
    MailPreset.PROMOTIONS -> "Ofertas e cupons da aba Promoções do Gmail."
    MailPreset.SOCIAL -> "Avisos de redes sociais da aba Social do Gmail."
    MailPreset.NEWSLETTERS -> "Mensagens com link de \"cancelar inscrição\"."
    MailPreset.BIG -> "Costumam ser anexos pesados (fotos, PDFs, vídeos)."
    MailPreset.OLD_READ -> "Já abertos e antigos. Confira antes se guarda recibos aqui."
}

@Composable
private fun MailReady(report: MailReport, vm: MailViewModel) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val senders by vm.senders.collectAsStateWithLifecycle()
    val bigPicked by vm.bigPicked.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    val uids = remember(report, presets, senders, bigPicked) { vm.selectedUids(report) }
    val bytes = remember(report, uids) { report.bytesOf(uids) }
    val shown = remember(report) {
        buildList {
            if (report.isGmail) { add(MailPreset.PROMOTIONS); add(MailPreset.SOCIAL) }
            add(MailPreset.NEWSLETTERS); add(MailPreset.BIG); add(MailPreset.OLD_READ)
        }.filter { report.countFor(it) > 0 }
    }
    val trashTotal = report.trashBytes + report.spamBytes
    val canDelete = uids.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(report.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        animatedBytes(bytes, 400),
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("marcados para limpar", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatCount(report.totalCount.toLong())} e-mails analisados • ${formatBytes(report.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            notice?.let { n ->
                item {
                    Panel(Modifier.fillMaxWidth(), onClick = { vm.dismissNotice() }, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(n, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (trashTotal > 0) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lixeira e spam", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${report.trashCount + report.spamCount} e-mails • ${formatBytes(trashTotal)} ocupando espaço",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.emptyTrash(report) }) { Text("Esvaziar", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            if (shown.isNotEmpty()) {
                item { SectionLabel("Limpeza rápida", Modifier.padding(top = 8.dp)) }
                items(shown, key = { it.name }) { p ->
                    SelectRow(
                        title = presetTitle(p, report.isGmail),
                        subtitle = "${formatBytes(report.bytesFor(p))} • ${formatCount(report.countFor(p).toLong())} e-mails\n${presetHint(p)}",
                        checked = p in presets,
                        onToggle = { vm.togglePreset(p) },
                    )
                }
            }
            item { SectionLabel("Quem mais ocupa espaço", Modifier.padding(top = 12.dp)) }
            items(report.senders.take(40), key = { it.index }) { s ->
                SelectRow(
                    title = s.label,
                    subtitle = "${formatBytes(s.bytes)} • ${formatCount(s.count.toLong())} e-mails" + if (s.hasUnsubscribe) " • newsletter" else "",
                    checked = s.index in senders,
                    onToggle = { vm.toggleSender(s.index) },
                )
            }
            if (report.big.isNotEmpty()) {
                item { SectionLabel("Maiores e-mails", Modifier.padding(top = 12.dp)) }
                items(report.big.take(30), key = { "b${it.uid}" }) { m ->
                    SelectRow(
                        title = m.subject.ifBlank { "(sem assunto)" },
                        subtitle = "${formatBytes(m.size)} • ${m.from}",
                        checked = m.uid in bigPicked,
                        onToggle = { vm.toggleBig(m.uid) },
                    )
                }
            }
            item {
                Text(
                    "E-mails com estrela nunca entram na limpeza em massa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            BigButton(
                text = if (canDelete) "Apagar ${formatCount(uids.size.toLong())} e-mails (${formatBytes(bytes)})" else "Marque o que quer apagar",
                icon = Icons.Rounded.DeleteForever,
                enabled = canDelete,
                onClick = { confirm = true },
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Apagar ${formatCount(uids.size.toLong())} e-mails?") },
            text = {
                Column {
                    Text(
                        "\"Apagar de vez\" remove também o que já está na lixeira e no spam e libera ${formatBytes(bytes + trashTotal)} agora. Não dá para desfazer.\n\n\"Só para a lixeira\" deixa você recuperar em até 30 dias (o espaço só volta depois).",
                    )
                    TextButton(onClick = { confirm = false }) { Text("Cancelar") }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.delete(report, permanent = true) }) {
                    Text("Apagar de vez", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false; vm.delete(report, permanent = false) }) { Text("Só para a lixeira") }
            },
        )
    }
}

@Composable
private fun SelectRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), onClick = onToggle) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}
