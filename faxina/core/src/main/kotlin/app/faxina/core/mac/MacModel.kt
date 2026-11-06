package app.faxina.core.mac

import app.faxina.core.DirNode
import app.faxina.core.FileCategory
import app.faxina.core.FileEntry
import app.faxina.core.Risk

/** Cada "gaveta" da limpeza no Mac. O texto de cada uma fica na camada de interface. */
enum class MacGroupId(val risk: Risk) {
    APP_CACHES(Risk.SAFE),
    LOGS(Risk.SAFE),
    DEV_CACHES(Risk.SAFE),
    MAIL_DOWNLOADS(Risk.SAFE),
    TEMP_FILES(Risk.SAFE),
    TRASH(Risk.SAFE),
    DUPLICATES(Risk.REVIEW),
    BIG_FILES(Risk.REVIEW),
    INSTALLERS(Risk.REVIEW),
    OLD_DOWNLOADS(Risk.REVIEW),
    SCREENSHOTS(Risk.REVIEW),
    DEV_HEAVY(Risk.REVIEW),
    IOS_BACKUPS(Risk.REVIEW),
}

class MacGroup(val id: MacGroupId, val items: List<FileEntry>) {
    val risk: Risk get() = id.risk
    val bytes: Long = items.sumOf { if (it.keep) 0L else it.size }
    val count: Int = items.count { !it.keep }
}

class MacScanOptions(
    val home: java.io.File,
    val bigFileBytes: Long = 100L * 1000 * 1000,
    val dupMinBytes: Long = 512L * 1000,
    val oldDownloadMs: Long = 90L * 24 * 3600 * 1000,
    val parallelism: Int = 4,
    val now: Long = System.currentTimeMillis(),
)

class MacScanResult(
    val totalFiles: Long,
    val homeBytes: Long,
    val libraryBytes: Long,
    /** Só arquivos fora de ~/Library. */
    val bytesByCategory: Map<FileCategory, Long>,
    val groups: Map<MacGroupId, MacGroup>,
    val dupCandidates: Map<Long, List<FileEntry>>,
    val tree: DirNode,
    /** Pastas que o macOS não deixou ler (falta "Acesso Total ao Disco"). */
    val denied: List<String>,
    val cancelled: Boolean,
) {
    fun group(id: MacGroupId): MacGroup? = groups[id]
}

class MacApp(
    val name: String,
    val path: String,
    val bytes: Long,
    val bundleId: String?,
    val lastUsedMs: Long?,
)
