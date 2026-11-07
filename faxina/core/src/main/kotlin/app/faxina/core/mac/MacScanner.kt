package app.faxina.core.mac

import app.faxina.core.CancelToken
import app.faxina.core.CleanGroup
import app.faxina.core.DirNode
import app.faxina.core.Duplicates
import app.faxina.core.FileCategory
import app.faxina.core.FileEntry
import app.faxina.core.GroupId
import app.faxina.core.ScanProgress
import app.faxina.core.categoryOf
import app.faxina.core.extensionOf
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveTask
import java.util.concurrent.atomic.LongAdder

/**
 * Varredura única da pasta pessoal do Mac. Percorre tudo uma vez (em paralelo) e separa o que importa:
 * caches e logs, ferramentas de desenvolvedor, backups de iPhone, Lixeira, instaladores, capturas de tela,
 * arquivos grandes e candidatos a duplicado.
 */
class MacScanner(private val opts: MacScanOptions, val progress: ScanProgress = ScanProgress()) {
    private val queues = HashMap<MacGroupId, ConcurrentLinkedQueue<FileEntry>>().also { m ->
        for (g in MacGroupId.values()) m[g] = ConcurrentLinkedQueue()
    }
    private val dupCandidates = ConcurrentLinkedQueue<FileEntry>()
    private val denied = ConcurrentLinkedQueue<String>()
    private val catBytes = FileCategory.values().associateWith { LongAdder() }
    private val totalFiles = LongAdder()
    private lateinit var token: CancelToken

    private val homePath: String = opts.home.path.trimEnd('/')

    fun scan(cancel: CancelToken = CancelToken()): MacScanResult {
        token = cancel
        progress.phase = ScanProgress.Phase.WALK
        val pool = ForkJoinPool(opts.parallelism.coerceIn(1, 8))
        val root: DirNode = try {
            pool.invoke(Walk(opts.home.toPath(), MCtx.root(), isRoot = true))
        } finally {
            pool.shutdown()
        }
        return buildResult(root)
    }

    private fun buildResult(root: DirNode): MacScanResult {
        sortTree(root)
        val lib = root.children.firstOrNull { it.name == "Library" }

        fun node(rel: String): DirNode? {
            var cur: DirNode = root
            for (part in rel.split('/')) cur = cur.children.firstOrNull { it.name == part } ?: return null
            return cur
        }
        fun entryOf(n: DirNode) = FileEntry(n.path, n.size, File(n.path).lastModified(), isDir = true)

        val claimed = HashSet<String>()

        // --- ferramentas de desenvolvedor
        for (rel in DEV_SAFE_PATHS) {
            val n = node(rel) ?: continue
            claimed.add(n.path)
            if (n.size >= MIN_DIR_BYTES) queues.getValue(MacGroupId.DEV_CACHES).add(entryOf(n))
        }
        for (rel in DEV_HEAVY_PATHS) {
            val n = node(rel) ?: continue
            if (n.size >= MIN_DIR_BYTES) queues.getValue(MacGroupId.DEV_HEAVY).add(entryOf(n))
        }
        node(".cache")?.children?.forEach { c ->
            if (c.size >= 50L * 1000 * 1000) queues.getValue(MacGroupId.DEV_HEAVY).add(entryOf(c))
        }

        // --- caches de apps (um item por app); caches da Apple ficam de fora
        node("Library/Caches")?.children?.forEach { c ->
            if (c.path in claimed || c.name.startsWith("com.apple.")) return@forEach
            if (c.size >= MIN_CACHE_BYTES) queues.getValue(MacGroupId.APP_CACHES).add(entryOf(c))
        }
        node("Library/Containers")?.children?.forEach { c ->
            if (c.name.startsWith("com.apple.")) return@forEach
            val cache = c.children.firstOrNull { it.name == "Data" }?.children?.firstOrNull { it.name == "Library" }
                ?.children?.firstOrNull { it.name == "Caches" }
            // A pasta Caches faz parte do contêiner do app: apagamos só o que tem dentro.
            if (cache != null && cache.size >= MIN_CACHE_BYTES) queues.getValue(MacGroupId.APP_CACHES).add(entryOf(cache).copy(contentsOnly = true))
        }

        // --- logs e relatórios de erro
        node("Library/Logs")?.children?.forEach { c ->
            if (c.size >= 64L * 1000) queues.getValue(MacGroupId.LOGS).add(entryOf(c))
        }
        // arquivos soltos em Library/Logs não aparecem na árvore (só pastas): lista direto
        File(homePath, "Library/Logs").listFiles()?.filter { it.isFile && it.length() >= 64L * 1000 }?.forEach {
            queues.getValue(MacGroupId.LOGS).add(FileEntry(it.path, it.length(), it.lastModified()))
        }

        // --- anexos baixados do Mail
        node("Library/Containers/com.apple.mail/Data/Library/Mail Downloads")?.let {
            if (it.size >= MIN_DIR_BYTES) queues.getValue(MacGroupId.MAIL_DOWNLOADS).add(entryOf(it).copy(contentsOnly = true))
        }

        // --- backups de iPhone/iPad
        node("Library/Application Support/MobileSync/Backup")?.children?.forEach { c ->
            if (c.size >= MIN_DIR_BYTES) queues.getValue(MacGroupId.IOS_BACKUPS).add(entryOf(c))
        }

        // --- Lixeira (cada item de primeiro nível)
        val trashDir = File(homePath, ".Trash")
        trashDir.listFiles()?.forEach { f ->
            val n = node(".Trash/${f.name}")
            val size = if (f.isDirectory) n?.size ?: 0L else f.length()
            if (size > 0) queues.getValue(MacGroupId.TRASH).add(FileEntry(f.path, size, f.lastModified(), isDir = f.isDirectory))
        }

        // --- grandes: tamanho de verdade no disco (arquivos esparsos e do iCloud não contam)
        val bigQueue = queues.getValue(MacGroupId.BIG_FILES)
        val bigList = bigQueue.toList()
        bigQueue.clear()
        val physical = PhysicalSize.of(bigList.map { it.path })
        for (e in bigList) {
            val real = physical[e.path] ?: e.size
            if (real >= opts.bigFileBytes) bigQueue.add(e.copy(size = real))
        }

        val groups = LinkedHashMap<MacGroupId, MacGroup>()
        for (g in MacGroupId.values()) {
            val items = queues.getValue(g).toList()
            if (items.isNotEmpty()) groups[g] = MacGroup(g, items.sortedByDescending { it.size })
        }
        progress.phase = ScanProgress.Phase.DONE
        return MacScanResult(
            totalFiles = totalFiles.sum(),
            homeBytes = root.size,
            libraryBytes = lib?.size ?: 0L,
            bytesByCategory = catBytes.mapValues { it.value.sum() },
            groups = groups,
            dupCandidates = dupCandidates.groupBy { it.size }.filterValues { it.size >= 2 },
            tree = root,
            denied = denied.toList().sorted(),
            cancelled = token.cancelled,
        )
    }

    private fun sortTree(n: DirNode) {
        n.children.sortByDescending { it.size }
        n.children.forEach { sortTree(it) }
    }

    private inner class Walk(
        private val dir: Path,
        private val ctx: MCtx,
        private val isRoot: Boolean = false,
    ) : RecursiveTask<DirNode>() {

        override fun compute(): DirNode {
            val node = DirNode(if (isRoot) dir.toString() else ctx.name, dir.toString())
            if (token.cancelled || ctx.skip != Skip.NONE) return node
            progress.currentDir = ctx.rel
            val subs = ArrayList<Pair<Path, MCtx>>()
            var ownBytes = 0L
            var ownFiles = 0L
            try {
                Files.newDirectoryStream(dir).use { stream ->
                    for (p in stream) {
                        val attrs = try {
                            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (_: Exception) { continue }
                        if (attrs.isSymbolicLink) continue
                        val name = p.fileName.toString()
                        if (attrs.isDirectory) {
                            subs.add(p to ctx.child(name))
                        } else if (attrs.isRegularFile) {
                            ownFiles++
                            ownBytes += attrs.size()
                            onFile(dir.toString(), name, attrs.size(), attrs.lastModifiedTime().toMillis(), ctx)
                        }
                    }
                }
            } catch (e: Exception) {
                node.unreadable = true
                val msg = e.message ?: ""
                if ((e is AccessDeniedException || msg.contains("not permitted", ignoreCase = true) ||
                        msg.contains("denied", ignoreCase = true)) && ctx.depth <= 4 && denied.size < 40
                ) {
                    denied.add(ctx.rel.ifEmpty { "/" })
                }
            }

            val tasks = subs.map { (p, c) -> Walk(p, c) }
            val parallel = ctx.depth < FORK_DEPTH && tasks.size > 1
            if (parallel) {
                tasks.forEach { it.fork() }
                tasks.forEach { it.join() }
            }
            var size = ownBytes
            var files = ownFiles
            val childNodes = ArrayList<DirNode>(tasks.size)
            for (t in tasks) {
                val cn = if (parallel) t.join() else t.compute()
                childNodes.add(cn)
                size += cn.size
                files += cn.files
            }
            node.size = size
            node.files = files
            node.children.addAll(childNodes)

            for (i in tasks.indices) {
                val c = subs[i].second
                val cn = childNodes[i]
                if (c.junkTop) {
                    if (cn.size > 0) queues.getValue(c.junk!!).add(FileEntry(cn.path, cn.size, 0L, isDir = true))
                } else if (c.pkgExt in BIG_PACKAGE_EXT && !ctx.noCollect && ctx.junk == null &&
                    cn.size >= opts.bigFileBytes
                ) {
                    queues.getValue(MacGroupId.BIG_FILES).add(FileEntry(cn.path, cn.size, 0L, isDir = true))
                }
            }
            return node
        }
    }

    private fun onFile(dirPath: String, name: String, size: Long, modified: Long, ctx: MCtx) {
        totalFiles.increment()
        progress.files.increment()
        progress.bytes.add(size)
        val lower = name.lowercase()
        val cat = categoryOf(lower)
        if (!ctx.inLibrary) catBytes.getValue(cat).add(size)
        if (ctx.junk != null || ctx.noCollect) return

        val path = "$dirPath/$name"
        fileTempGroup(lower, modified, opts.now)?.let {
            queues.getValue(it).add(FileEntry(path, size, modified))
            return
        }
        val entry = FileEntry(path, size, modified)
        val ext = extensionOf(lower)
        if ((ctx.downloads || ctx.desktop) && ext in INSTALLER_EXT) queues.getValue(MacGroupId.INSTALLERS).add(entry)
        if ((cat == FileCategory.IMAGE || cat == FileCategory.VIDEO) && isScreenshotName(lower)) {
            queues.getValue(MacGroupId.SCREENSHOTS).add(entry)
        }
        if (ctx.downloads && opts.now - modified > opts.oldDownloadMs) queues.getValue(MacGroupId.OLD_DOWNLOADS).add(entry)
        if (size >= opts.bigFileBytes) queues.getValue(MacGroupId.BIG_FILES).add(entry)
        if (size >= opts.dupMinBytes) dupCandidates.add(entry)
    }

    companion object {
        private const val FORK_DEPTH = 3
        private const val MIN_DIR_BYTES = 1_000_000L
        private const val MIN_CACHE_BYTES = 256_000L

        /**
         * Procura duplicados exatos. Antes de comparar, descarta arquivos que não estão inteiros no disco
         * (iCloud na nuvem): ler um deles forçaria o download.
         */
        fun findDuplicates(
            result: MacScanResult,
            progress: ScanProgress? = null,
            cancel: CancelToken = CancelToken(),
        ): MacGroup {
            val all = result.dupCandidates.values.flatten()
            val physical = PhysicalSize.of(all.map { it.path })
            val local = all.filter { e ->
                val real = physical[e.path]
                real == null || real >= e.size / 2
            }
            val regrouped = local.groupBy { it.size }.filterValues { it.size >= 2 }
            val found = Duplicates.find(regrouped, progress, cancel)
            val flat = Duplicates.flatten(found)
            check(flat.id == GroupId.DUPLICATES)
            return MacGroup(MacGroupId.DUPLICATES, flat.items)
        }
    }
}
