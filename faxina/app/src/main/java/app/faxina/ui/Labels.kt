package app.faxina.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.faxina.core.FileCategory
import app.faxina.core.GroupId

fun GroupId.title(): String = when (this) {
    GroupId.TEMP_FILES -> "Arquivos temporários e logs"
    GroupId.CACHE_THUMBS -> "Cache e miniaturas"
    GroupId.GALLERY_TRASH -> "Lixeira da galeria"
    GroupId.EMPTY_DIRS -> "Pastas vazias"
    GroupId.WA_STATUS -> "WhatsApp • Status em cache"
    GroupId.APK_DONE -> "Instaladores de apps que você já tem"
    GroupId.DUPLICATES -> "Arquivos duplicados"
    GroupId.BIG_FILES -> "Arquivos grandes"
    GroupId.SCREENSHOTS -> "Capturas e gravações de tela"
    GroupId.OLD_DOWNLOADS -> "Downloads antigos (mais de 90 dias)"
    GroupId.APK_OTHER -> "Outros instaladores (APK)"
    GroupId.WA_IMAGES -> "WhatsApp • Fotos recebidas"
    GroupId.WA_VIDEOS -> "WhatsApp • Vídeos e GIFs"
    GroupId.WA_AUDIO -> "WhatsApp • Áudios e recados de voz"
    GroupId.WA_DOCS -> "WhatsApp • Documentos"
    GroupId.WA_SENT -> "WhatsApp • Mídia que você enviou"
    GroupId.WA_BACKUPS -> "WhatsApp • Backups locais antigos"
    GroupId.TG_IMAGES -> "Telegram • Fotos"
    GroupId.TG_VIDEOS -> "Telegram • Vídeos"
    GroupId.TG_AUDIO -> "Telegram • Áudios"
    GroupId.TG_DOCS -> "Telegram • Documentos"
}

fun GroupId.hint(): String = when (this) {
    GroupId.TEMP_FILES -> "Sobras de downloads e arquivos .tmp/.log que nenhum app usa mais."
    GroupId.CACHE_THUMBS -> "O Android refaz sozinho quando precisar. Não some nada seu."
    GroupId.GALLERY_TRASH -> "Fotos e vídeos que você já apagou e que ainda ocupam espaço."
    GroupId.EMPTY_DIRS -> "Pastas sem nenhum arquivo dentro."
    GroupId.WA_STATUS -> "Cópias temporárias dos status que você viu."
    GroupId.APK_DONE -> "Arquivos de instalação de apps que já estão no celular."
    GroupId.DUPLICATES -> "Cópias idênticas, byte a byte. Mantemos 1 de cada, a melhor."
    GroupId.BIG_FILES -> "Tudo acima de 50 MB, do maior para o menor."
    GroupId.SCREENSHOTS -> "Prints e gravações que costumam acumular."
    GroupId.OLD_DOWNLOADS -> "Baixados há mais de 3 meses e esquecidos na pasta Download."
    GroupId.APK_OTHER -> "Instaladores de apps que ainda não estão instalados."
    GroupId.WA_IMAGES -> "Fotos que chegaram em conversas."
    GroupId.WA_VIDEOS -> "Vídeos e GIFs que chegaram em conversas."
    GroupId.WA_AUDIO -> "Áudios encaminhados e recados de voz recebidos."
    GroupId.WA_DOCS -> "PDFs e outros arquivos recebidos."
    GroupId.WA_SENT -> "Cópias do que você mandou. Costuma existir na galeria."
    GroupId.WA_BACKUPS -> "Mantemos o backup mais recente e listamos os anteriores."
    GroupId.TG_IMAGES, GroupId.TG_VIDEOS, GroupId.TG_AUDIO, GroupId.TG_DOCS -> "Mídia baixada pelo Telegram."
}

fun GroupId.icon(): ImageVector = when (this) {
    GroupId.TEMP_FILES -> Icons.Rounded.DeleteSweep
    GroupId.CACHE_THUMBS -> Icons.Rounded.Cached
    GroupId.GALLERY_TRASH -> Icons.Rounded.Delete
    GroupId.EMPTY_DIRS -> Icons.Rounded.FolderOpen
    GroupId.WA_STATUS, GroupId.WA_IMAGES, GroupId.WA_VIDEOS, GroupId.WA_AUDIO, GroupId.WA_DOCS, GroupId.WA_SENT -> Icons.Rounded.Chat
    GroupId.WA_BACKUPS -> Icons.Rounded.Cloud
    GroupId.APK_DONE, GroupId.APK_OTHER -> Icons.Rounded.Android
    GroupId.DUPLICATES -> Icons.Rounded.ContentCopy
    GroupId.BIG_FILES -> Icons.Rounded.Inventory2
    GroupId.SCREENSHOTS -> Icons.Rounded.Image
    GroupId.OLD_DOWNLOADS -> Icons.Rounded.Download
    GroupId.TG_IMAGES, GroupId.TG_VIDEOS, GroupId.TG_AUDIO, GroupId.TG_DOCS -> Icons.AutoMirrored.Rounded.Send
}

val MESSENGER_GROUPS = listOf(
    GroupId.WA_STATUS, GroupId.WA_IMAGES, GroupId.WA_VIDEOS, GroupId.WA_AUDIO, GroupId.WA_DOCS,
    GroupId.WA_SENT, GroupId.WA_BACKUPS, GroupId.TG_IMAGES, GroupId.TG_VIDEOS, GroupId.TG_AUDIO, GroupId.TG_DOCS,
)

fun FileCategory.label(): String = when (this) {
    FileCategory.IMAGE -> "Fotos"
    FileCategory.VIDEO -> "Vídeos"
    FileCategory.AUDIO -> "Áudio"
    FileCategory.DOCUMENT -> "Documentos"
    FileCategory.ARCHIVE -> "Compactados"
    FileCategory.APK -> "Instaladores"
    FileCategory.OTHER -> "Outros"
}

fun FileCategory.color(): Color = when (this) {
    FileCategory.IMAGE -> Brand.Blue
    FileCategory.VIDEO -> Brand.Violet
    FileCategory.AUDIO -> Brand.Pink
    FileCategory.DOCUMENT -> Brand.Amber
    FileCategory.ARCHIVE -> Brand.Amber
    FileCategory.APK -> Brand.Teal
    FileCategory.OTHER -> Brand.Slate
}

fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.IMAGE -> Icons.Rounded.Image
    FileCategory.VIDEO -> Icons.Rounded.VideoFile
    FileCategory.AUDIO -> Icons.Rounded.AudioFile
    FileCategory.DOCUMENT -> Icons.Rounded.Description
    FileCategory.ARCHIVE -> Icons.Rounded.Archive
    FileCategory.APK -> Icons.Rounded.Android
    FileCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

/** "há 3 meses", "ontem"... para datas em milissegundos. */
fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
    if (then <= 0) return "nunca"
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
