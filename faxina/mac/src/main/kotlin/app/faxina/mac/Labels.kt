package app.faxina.mac

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import app.faxina.core.mac.MacGroupId

fun MacGroupId.title(): String = when (this) {
    MacGroupId.APP_CACHES -> "Cache de apps"
    MacGroupId.LOGS -> "Logs e relatórios de erro"
    MacGroupId.DEV_CACHES -> "Cache de desenvolvimento"
    MacGroupId.MAIL_DOWNLOADS -> "Anexos baixados pelo Mail"
    MacGroupId.TEMP_FILES -> "Arquivos temporários"
    MacGroupId.TRASH -> "Lixeira"
    MacGroupId.DUPLICATES -> "Arquivos duplicados"
    MacGroupId.BIG_FILES -> "Arquivos grandes"
    MacGroupId.INSTALLERS -> "Instaladores (.dmg e .pkg)"
    MacGroupId.OLD_DOWNLOADS -> "Downloads com mais de 90 dias"
    MacGroupId.SCREENSHOTS -> "Capturas e gravações de tela"
    MacGroupId.DEV_HEAVY -> "Desenvolvimento: dados pesados"
    MacGroupId.IOS_BACKUPS -> "Backups de iPhone e iPad"
}

fun MacGroupId.hint(): String = when (this) {
    MacGroupId.APP_CACHES -> "Arquivos temporários que os apps recriam sozinhos. Feche os apps antes para liberar mais."
    MacGroupId.LOGS -> "Registros de funcionamento e relatórios de travamento antigos."
    MacGroupId.DEV_CACHES -> "Xcode (DerivedData), Homebrew, npm, pip... refeitos automaticamente na próxima compilação."
    MacGroupId.MAIL_DOWNLOADS -> "Cópias de anexos que o Mail abriu. Os originais continuam nas mensagens."
    MacGroupId.TEMP_FILES -> "Downloads incompletos, arquivos .tmp e pastas __MACOSX deixadas por zips."
    MacGroupId.TRASH -> "O que você já mandou para a Lixeira e ainda ocupa espaço."
    MacGroupId.DUPLICATES -> "Cópias idênticas, byte a byte. Mantemos 1 de cada, a de melhor lugar."
    MacGroupId.BIG_FILES -> "Tudo acima de 100 MB, pelo tamanho real que ocupa no disco."
    MacGroupId.INSTALLERS -> "Arquivos de instalação em Downloads e na Mesa. O app já instalado não é afetado."
    MacGroupId.OLD_DOWNLOADS -> "Baixados há mais de 3 meses e esquecidos em Downloads."
    MacGroupId.SCREENSHOTS -> "Prints e gravações de tela que se acumulam na Mesa."
    MacGroupId.DEV_HEAVY -> "Simuladores, dispositivos do Xcode, Gradle e Maven. Custam tempo para baixar de novo."
    MacGroupId.IOS_BACKUPS -> "Cada pasta é um backup completo feito pelo Finder. Confira a data antes de apagar."
}

fun MacGroupId.icon(): ImageVector = when (this) {
    MacGroupId.APP_CACHES -> Icons.Rounded.Cached
    MacGroupId.LOGS -> Icons.Rounded.Description
    MacGroupId.DEV_CACHES -> Icons.Rounded.Code
    MacGroupId.MAIL_DOWNLOADS -> Icons.Rounded.Email
    MacGroupId.TEMP_FILES -> Icons.Rounded.DeleteSweep
    MacGroupId.TRASH -> Icons.Rounded.Delete
    MacGroupId.DUPLICATES -> Icons.Rounded.ContentCopy
    MacGroupId.BIG_FILES -> Icons.Rounded.Inventory2
    MacGroupId.INSTALLERS -> Icons.Rounded.Download
    MacGroupId.OLD_DOWNLOADS -> Icons.Rounded.History
    MacGroupId.SCREENSHOTS -> Icons.Rounded.Image
    MacGroupId.DEV_HEAVY -> Icons.Rounded.Storage
    MacGroupId.IOS_BACKUPS -> Icons.Rounded.PhoneIphone
}

/** "/Users/ana/Documents/x" → "~/Documents/x". */
fun prettyPath(path: String): String {
    val h = Ops.home.path
    return if (path == h) "~" else if (path.startsWith("$h/")) "~/" + path.removePrefix("$h/") else path
}

fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
    if (then <= 0) return "sem registro"
    val days = ((now - then) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "hoje"
        days == 1 -> "ontem"
        days < 30 -> "há $days dias"
        days < 60 -> "há 1 mês"
        days < 365 -> "há ${days / 30} meses"
        days < 730 -> "há 1 ano"
        else -> "há ${days / 365} anos"
    }
}

fun formatCount(n: Long): String = "%,d".format(n).replace(',', '.')
