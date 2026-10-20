package app.faxina.core

/**
 * Regras (100% locais, sem IA) que dizem o que é lixo seguro e o que merece revisão.
 * O contexto é calculado uma vez por pasta e herdado pelas subpastas, então decidir sobre
 * cada arquivo custa quase nada.
 */

internal val USER_ROOTS = setOf(
    "dcim", "pictures", "movies", "music", "documents", "download", "downloads",
    "podcasts", "audiobooks", "recordings", "alarms", "notifications", "ringtones",
)

private val CACHE_DIR_NAMES = setOf(
    "cache", ".cache", "tmp", ".tmp", "temp", ".temp", "temporary",
    "thumbs", ".thumbs", "thumbnails", ".thumbnails", "thumbnail", ".thumbnail",
)

/** Dentro de pastas do usuário (DCIM etc.) só estas pastas ocultas são tratadas como lixo. */
private val HIDDEN_JUNK_IN_USER_DIRS = setOf(".thumbnails", ".thumbs", ".thumbnail", ".cache", ".tmp", ".temp")

private val TRASH_DIR_NAMES = setOf(".trash", ".trashbin", ".globaltrash", ".recycle", ".recyclebin", "recycle", "\$recycle.bin")

/** Só tratados como lixo fora das pastas de mídia/documentos do usuário. */
private val LOOSE_TEMP_EXTENSIONS = setOf("dmp", "log")
private val PARTIAL_EXTENSIONS = setOf("crdownload", "part", "partial")

private val SCREENSHOT_DIRS = setOf(
    "screenshots", "screenshot", "screen recordings", "screen_recordings", "screenrecorder",
    "screen recorder", "screenrecord", "capturas de tela", "gravações de tela",
)

private const val DAY_MS = 24L * 3600 * 1000

internal enum class Messenger { NONE, WHATSAPP, TELEGRAM }

/** Estado herdado enquanto descemos a árvore de pastas. */
internal class DirCtx(
    val name: String,
    val rel: String,
    val depth: Int,
    val top: String,
    /** Grupo de lixo se esta pasta ou uma ancestral é lixo. */
    val junk: GroupId?,
    /** true somente na pasta que "abre" o lixo (é a que será apagada). */
    val junkTop: Boolean,
    val noCollect: Boolean,
    val hidden: Boolean,
    val screenshots: Boolean,
    val downloads: Boolean,
    val messenger: Messenger,
    /** Nível dentro da pasta do mensageiro: 1=raiz, 2=Media, 3=tipo de mídia. */
    val msStage: Int,
    val msGroup: GroupId?,
    val msSent: Boolean,
    val waDb: Boolean,
    val androidMedia: Boolean,
) {
    fun child(childName: String): DirCtx {
        val lower = childName.lowercase()
        val childRel = if (rel.isEmpty()) childName else "$rel/$childName"
        val childDepth = depth + 1
        val childTop = if (depth == 0) lower else top
        val childHidden = hidden || childName.startsWith('.')

        // --- pastas de apps em Android/: só olhamos Android/media e o cache externo (Android ≤ 10)
        var noCollect = this.noCollect
        var androidMedia = this.androidMedia
        if (depth == 1 && top == "android") {
            if (lower == "media") androidMedia = true
            noCollect = true // Android/data, Android/obb e afins: não sugerimos arquivos de apps
        }
        var junk = this.junk
        var junkTop = false
        if (junk == null) {
            junk = dirJunkGroup(lower, childName, childTop, childDepth, childRel, this)
            junkTop = junk != null
        }

        // --- mensageiros
        var ms = messenger
        var stage = msStage
        var group = msGroup
        var sent = msSent
        var waDb = false
        when {
            ms == Messenger.NONE && (top != "android" || androidMedia) && (lower == "whatsapp" || lower == "whatsapp business") -> {
                ms = Messenger.WHATSAPP; stage = 1; noCollect = false
            }
            ms == Messenger.NONE && (top != "android" || androidMedia) && lower == "telegram" -> {
                ms = Messenger.TELEGRAM; stage = 1; noCollect = false
            }
            ms == Messenger.WHATSAPP && stage == 1 && lower == "media" -> stage = 2
            ms == Messenger.WHATSAPP && stage == 1 && lower == "databases" -> {
                waDb = true; noCollect = true
            }
            ms == Messenger.WHATSAPP && stage == 1 && lower == "backups" -> noCollect = true
            ms == Messenger.WHATSAPP && stage == 2 -> {
                stage = 3
                group = when {
                    lower.startsWith("whatsapp images") -> GroupId.WA_IMAGES
                    lower.startsWith("whatsapp video") || lower.startsWith("whatsapp animated") -> GroupId.WA_VIDEOS
                    lower.startsWith("whatsapp audio") || lower.startsWith("whatsapp voice") -> GroupId.WA_AUDIO
                    lower.startsWith("whatsapp documents") -> GroupId.WA_DOCS
                    else -> null
                }
            }
            ms == Messenger.WHATSAPP && stage >= 3 && lower == "sent" -> sent = true
            ms == Messenger.TELEGRAM && stage == 1 -> {
                stage = 3
                group = when {
                    lower == "telegram images" -> GroupId.TG_IMAGES
                    lower == "telegram video" -> GroupId.TG_VIDEOS
                    lower == "telegram audio" -> GroupId.TG_AUDIO
                    lower == "telegram documents" -> GroupId.TG_DOCS
                    else -> null
                }
            }
        }
        // Em Android/media/<pacote>/ só liberamos a pasta do WhatsApp/Telegram (acima).
        if (androidMedia && depth >= 2 && ms == Messenger.NONE) noCollect = true

        return DirCtx(
            name = childName, rel = childRel, depth = childDepth, top = childTop,
            junk = junk, junkTop = junkTop, noCollect = noCollect, hidden = childHidden,
            screenshots = screenshots || lower in SCREENSHOT_DIRS,
            downloads = downloads || (depth == 0 && (lower == "download" || lower == "downloads")),
            messenger = ms, msStage = stage, msGroup = group, msSent = sent, waDb = waDb,
            androidMedia = androidMedia,
        )
    }

    /** Pastas vazias só são sugeridas fora de Android/ e fora das pastas-raiz padrão. */
    val emptyDirDeletable: Boolean
        get() = junk == null && top != "android" && !(depth == 1 && top in USER_ROOTS)

    companion object {
        fun root() = DirCtx(
            name = "", rel = "", depth = 0, top = "", junk = null, junkTop = false, noCollect = false,
            hidden = false, screenshots = false, downloads = false, messenger = Messenger.NONE,
            msStage = 0, msGroup = null, msSent = false, waDb = false, androidMedia = false,
        )
    }
}

private fun dirJunkGroup(lower: String, name: String, top: String, depth: Int, rel: String, parent: DirCtx): GroupId? {
    // Status do WhatsApp: cópias temporárias de status dos contatos
    if (lower == ".statuses" && parent.messenger == Messenger.WHATSAPP) return GroupId.WA_STATUS
    if (lower == ".shared" && parent.messenger == Messenger.WHATSAPP && parent.msStage == 1) return GroupId.CACHE_THUMBS

    // Cache externo de apps em Android ≤ 10: Android/data/<pacote>/cache
    if (depth == 4 && top == "android" && lower == "cache" && rel.startsWith("Android/data/")) return GroupId.CACHE_THUMBS
    if (top == "android") return null

    val inUserArea = top in USER_ROOTS
    if (lower == "lost.dir") return GroupId.TEMP_FILES
    if (lower in TRASH_DIR_NAMES) return GroupId.GALLERY_TRASH
    if (lower in CACHE_DIR_NAMES) {
        if (!inUserArea) return GroupId.CACHE_THUMBS
        if (lower in HIDDEN_JUNK_IN_USER_DIRS) return GroupId.CACHE_THUMBS
    }
    return null
}

/** Regras por arquivo (fora de pastas de lixo). [strict] = dentro de DCIM/Documentos/etc. Retorna o grupo ou null. */
internal fun fileJunkGroup(lower: String, modified: Long, now: Long, strict: Boolean): GroupId? {
    if (lower.startsWith(".trashed-")) return GroupId.GALLERY_TRASH
    if (lower.startsWith(".thumbdata")) return GroupId.CACHE_THUMBS
    val ext = extensionOf(lower)
    if (lower.startsWith(".pending-")) return if (now - modified > 3 * DAY_MS) GroupId.TEMP_FILES else null
    if (ext == "tmp" || ext == "temp" || ext == "chk") return GroupId.TEMP_FILES
    if (!strict && ext in LOOSE_TEMP_EXTENSIONS) return GroupId.TEMP_FILES
    if (ext in PARTIAL_EXTENSIONS) return if (now - modified > 3 * DAY_MS) GroupId.TEMP_FILES else null
    if (lower == "thumbs.db" || lower == "desktop.ini" || lower == ".ds_store") return GroupId.TEMP_FILES
    return null
}

/** Arquivos de mensageiro: grupo onde o arquivo se encaixa, ou null. */
internal fun messengerGroup(ctx: DirCtx): GroupId? {
    if (ctx.messenger == Messenger.NONE || ctx.msStage < 3) return null
    val g = ctx.msGroup ?: return null
    if (ctx.messenger == Messenger.WHATSAPP && ctx.msSent) return GroupId.WA_SENT
    return g
}
