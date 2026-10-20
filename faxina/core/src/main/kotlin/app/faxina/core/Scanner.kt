package app.faxina.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveTask
import java.util.concurrent.atomic.LongAdder

/**
 * Varredura única do armazenamento: percorre cada arquivo uma vez só, em paralelo, e já separa
 * tudo que importa (lixo, grandes, duplicados em potencial, WhatsApp, capturas de tela...).
 * Usa java.nio para ler tamanho e data numa chamada só por arquivo.
 */
class Scanner(private val opts: ScanOptions, val progress: ScanProgress = ScanProgress()) {

    private val queues = HashMap<GroupId, ConcurrentLinkedQueue<FileEntry>>().also { m ->
        for (g in GroupId.values()) m[g] = ConcurrentLinkedQueue()
    }
    private val dupCandidates = ConcurrentLinkedQueue<FileEntry>()
    private val waBackups = ConcurrentLinkedQueue<FileEntry>()
    private val catBytes = FileCategory.values().associateWith { LongAdder() }
    private val totalFiles = LongAdder()
    private val totalBytes = LongAdder()
    private lateinit var token: CancelToken

    fun scan(cancel: CancelToken = CancelToken()): ScanResult {
        token = cancel
        progress.phase = ScanProgress.Phase.WALK
        val pool = ForkJoinPool(opts.parallelism.coerceIn(1, 8))
        val tree = ArrayList<DirNode>()
        try {
            for (root in opts.roots) {
                if (!root.isDirectory) continue
                val node = pool.invoke(WalkTask(root.toPath(), DirCtx.root(), isRoot = true))
                tree.add(node)
            }
        } finally {
            pool.shutdown()
        }
        return buildResult(tree)
    }

    private fun buildResult(tree: List<DirNode>): ScanResult {
        val groups = LinkedHashMap<GroupId, CleanGroup>()
        // Backups locais do WhatsApp: manter só o mais recente.
        val backups = waBackups.sortedByDescending { it.modified }
        if (backups.size > 1) queues.getValue(GroupId.WA_BACKUPS).addAll(backups.drop(1))

        for (g in GroupId.values()) {
            val items = queues.getValue(g).toList()
            if (items.isNotEmpty()) groups[g] = CleanGroup(g, items.sortedByDescending { it.size })
        }
        val dups = dupCandidates.groupBy { it.size }.filterValues { it.size >= 2 }
        tree.forEach { sortTree(it) }
        progress.phase = ScanProgress.Phase.DONE
        return ScanResult(
            totalFiles = totalFiles.sum(),
            totalBytes = totalBytes.sum(),
            bytesByCategory = catBytes.mapValues { it.value.sum() },
            groups = groups,
            dupCandidates = dups,
            tree = tree,
            cancelled = token.cancelled,
        )
    }

    private fun sortTree(n: DirNode) {
        n.children.sortByDescending { it.size }
        n.children.forEach { sortTree(it) }
    }

    private inner class WalkTask(
        private val dir: Path,
        private val ctx: DirCtx,
        private val isRoot: Boolean = false,
    ) : RecursiveTask<DirNode>() {

        override fun compute(): DirNode {
            val node = DirNode(if (isRoot) dir.toString() else ctx.name, dir.toString())
            if (token.cancelled) return node
            progress.currentDir = ctx.rel
            val subs = ArrayList<Pair<Path, DirCtx>>()
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
            } catch (_: Exception) {
                // IOException, SecurityException, DirectoryIteratorException (arquivo sumiu no meio)...
                node.unreadable = true
            }
            node.empty = ownFiles == 0L && !node.unreadable

            val tasks = subs.map { (p, c) -> WalkTask(p, c) }
            if (ctx.depth < FORK_DEPTH && tasks.size > 1) {
                tasks.forEach { it.fork() }
                tasks.forEach { it.join() }
            }
            var size = ownBytes
            var files = ownFiles
            val childNodes = ArrayList<DirNode>(tasks.size)
            for (t in tasks) {
                val cn = if (ctx.depth < FORK_DEPTH && tasks.size > 1) t.join() else t.compute()
                childNodes.add(cn)
                size += cn.size
                files += cn.files
                if (!cn.empty) node.empty = false
            }
            node.size = size
            node.files = files
            node.children.addAll(childNodes)

            // Lixo em nível de pasta e pastas vazias
            for (i in tasks.indices) {
                val childCtx = subs[i].second
                val cn = childNodes[i]
                if (childCtx.junkTop) {
                    if (cn.size > 0) {
                        queues.getValue(childCtx.junk!!).add(FileEntry(cn.path, cn.size, 0L, isDir = true))
                    }
                } else if (!node.empty && cn.empty && childCtx.emptyDirDeletable) {
                    queues.getValue(GroupId.EMPTY_DIRS).add(FileEntry(cn.path, 0L, 0L, isDir = true))
                }
            }
            return node
        }
    }

    private fun onFile(dirPath: String, name: String, size: Long, modified: Long, ctx: DirCtx) {
        totalFiles.increment()
        totalBytes.add(size)
        progress.files.increment()
        progress.bytes.add(size)
        val lower = name.lowercase()
        val cat = categoryOf(lower)
        catBytes.getValue(cat).add(size)
        if (ctx.junk != null) return // o tamanho já entra na pasta de lixo inteira

        val inMediaArea = ctx.top in USER_ROOTS && ctx.top != "download" && ctx.top != "downloads"
        val path = "$dirPath/$name"

        if (ctx.waDb) {
            if (lower.startsWith("msgstore") && lower.contains(".crypt")) {
                waBackups.add(FileEntry(path, size, modified))
            }
            return
        }
        if (ctx.noCollect) return

        val junk = fileJunkGroup(lower, modified, opts.now, strict = inMediaArea)
        if (junk != null) {
            queues.getValue(junk).add(FileEntry(path, size, modified))
            return
        }

        val entry = FileEntry(path, size, modified)
        if (cat == FileCategory.APK) queues.getValue(GroupId.APK_OTHER).add(entry)
        messengerGroup(ctx)?.let { queues.getValue(it).add(entry) }
        if (ctx.screenshots && (cat == FileCategory.IMAGE || cat == FileCategory.VIDEO)) {
            queues.getValue(GroupId.SCREENSHOTS).add(entry)
        }
        if (ctx.downloads && cat != FileCategory.APK && opts.now - modified > opts.oldDownloadMs) {
            queues.getValue(GroupId.OLD_DOWNLOADS).add(entry)
        }
        if (size >= opts.bigFileBytes && cat != FileCategory.APK) queues.getValue(GroupId.BIG_FILES).add(entry)
        if (size >= opts.dupMinBytes && !ctx.hidden) dupCandidates.add(entry)
    }

    companion object {
        /** Até esta profundidade as subpastas são varridas em paralelo; abaixo disso, em sequência. */
        private const val FORK_DEPTH = 3
    }
}

/** Lista de raízes de varredura padrão para um [File] (ex.: /storage/emulated/0). */
fun scanRoots(vararg roots: File): List<File> = roots.filter { it.isDirectory }
