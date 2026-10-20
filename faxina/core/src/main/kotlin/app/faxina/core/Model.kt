package app.faxina.core

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

enum class FileCategory { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

/** SAFE = pode apagar sem perder nada seu. REVIEW = o usuário decide. */
enum class Risk { SAFE, REVIEW }

/** Cada grupo é uma "gaveta" da tela de revisão. O texto fica na camada de UI. */
enum class GroupId(val risk: Risk) {
    TEMP_FILES(Risk.SAFE),
    CACHE_THUMBS(Risk.SAFE),
    GALLERY_TRASH(Risk.SAFE),
    EMPTY_DIRS(Risk.SAFE),
    WA_STATUS(Risk.SAFE),
    APK_DONE(Risk.SAFE),
    DUPLICATES(Risk.REVIEW),
    BIG_FILES(Risk.REVIEW),
    SCREENSHOTS(Risk.REVIEW),
    OLD_DOWNLOADS(Risk.REVIEW),
    APK_OTHER(Risk.REVIEW),
    WA_IMAGES(Risk.REVIEW),
    WA_VIDEOS(Risk.REVIEW),
    WA_AUDIO(Risk.REVIEW),
    WA_DOCS(Risk.REVIEW),
    WA_SENT(Risk.REVIEW),
    WA_BACKUPS(Risk.REVIEW),
    TG_IMAGES(Risk.REVIEW),
    TG_VIDEOS(Risk.REVIEW),
    TG_AUDIO(Risk.REVIEW),
    TG_DOCS(Risk.REVIEW),
}

/**
 * Um arquivo (ou pasta, quando [isDir]) que pode ser apagado.
 * Em conjuntos de duplicados, [setId] identifica o conjunto e [keep] marca a cópia que fica.
 */
data class FileEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean = false,
    val setId: Int = -1,
    val keep: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

class CleanGroup(val id: GroupId, val items: List<FileEntry>) {
    val risk: Risk get() = id.risk

    /** Bytes que seriam liberados apagando tudo que não é "cópia mantida". */
    val bytes: Long = items.sumOf { if (it.keep) 0L else it.size }
    val count: Int = items.count { !it.keep }
}

/** Pasta na árvore de uso de espaço (raio-x). Só guarda diretórios; arquivos são listados sob demanda. */
class DirNode(val name: String, val path: String) {
    @Volatile var size: Long = 0
    @Volatile var files: Long = 0
    val children: MutableList<DirNode> = ArrayList()
    internal var empty: Boolean = true
    internal var unreadable: Boolean = false
}

class CancelToken {
    @Volatile var cancelled: Boolean = false
        private set

    fun cancel() { cancelled = true }
}

/** Contadores vivos para a UI desenhar o progresso da varredura. */
class ScanProgress {
    val files = LongAdder()
    val bytes = LongAdder()
    @Volatile var currentDir: String = ""
    val hashedBytes = AtomicLong()
    @Volatile var phase: Phase = Phase.WALK

    enum class Phase { WALK, DUPLICATES, DONE }
}

class ScanOptions(
    val roots: List<java.io.File>,
    val bigFileBytes: Long = 50L * 1000 * 1000,
    val dupMinBytes: Long = 100L * 1000,
    val oldDownloadMs: Long = 90L * 24 * 3600 * 1000,
    val parallelism: Int = 4,
    val now: Long = System.currentTimeMillis(),
)

class ScanResult(
    val totalFiles: Long,
    val totalBytes: Long,
    val bytesByCategory: Map<FileCategory, Long>,
    val groups: Map<GroupId, CleanGroup>,
    /** Arquivos com tamanho repetido (candidatos a duplicado), agrupados por tamanho. */
    val dupCandidates: Map<Long, List<FileEntry>>,
    val tree: List<DirNode>,
    val cancelled: Boolean,
) {
    fun group(id: GroupId): CleanGroup? = groups[id]
}
