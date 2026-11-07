package app.faxina.core.mac

import app.faxina.core.CancelToken
import app.faxina.core.FileEntry
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Apps instalados (/Applications e ~/Applications), com tamanho, último uso e restos espalhados pela Biblioteca. */
object MacApps {
    fun roots(home: File): List<File> = listOf(File("/Applications"), File(home, "Applications")).filter { it.isDirectory }

    /** Lista os .app (e os que estão dentro de subpastas como Utilities). */
    fun findBundles(roots: List<File>): List<File> {
        val out = ArrayList<File>()
        for (r in roots) {
            r.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                if (f.isDirectory && f.name.endsWith(".app", ignoreCase = true)) {
                    out.add(f)
                } else if (f.isDirectory && !f.name.startsWith(".")) {
                    f.listFiles()?.filter { it.isDirectory && it.name.endsWith(".app", ignoreCase = true) }?.let { out.addAll(it) }
                }
            }
        }
        return out
    }

    fun load(home: File, cancel: CancelToken = CancelToken(), parallelism: Int = 8): List<MacApp> {
        val bundles = findBundles(roots(home))
        if (bundles.isEmpty()) return emptyList()
        val sizes = PhysicalSize.of(bundles.map { it.path })
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 16))
        try {
            val jobs = bundles.map { b ->
                Callable {
                    if (cancel.cancelled) null else {
                        val (bundleId, last) = readMetadata(b)
                        MacApp(
                            name = b.name.removeSuffix(".app").removeSuffix(".App"),
                            path = b.path,
                            bytes = sizes[b.path] ?: 0L,
                            bundleId = bundleId,
                            lastUsedMs = last,
                        )
                    }
                }
            }
            return pool.invokeAll(jobs).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Pergunta ao Spotlight (mdls) o identificador do app e quando foi aberto pela última vez. */
    private fun readMetadata(app: File): Pair<String?, Long?> = try {
        val p = ProcessBuilder(
            "mdls", "-name", "kMDItemCFBundleIdentifier", "-name", "kMDItemLastUsedDate", app.path,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        parseMdls(text)
    } catch (_: Exception) {
        null to null
    }

    private val ID_RE = Regex("""kMDItemCFBundleIdentifier\s*=\s*"([^"]+)"""")
    private val DATE_RE = Regex("""kMDItemLastUsedDate\s*=\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{4})""")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

    fun parseMdlsForTest(text: String) = parseMdls(text)

    internal fun parseMdls(text: String): Pair<String?, Long?> {
        val id = ID_RE.find(text)?.groupValues?.get(1)
        val last = DATE_RE.find(text)?.groupValues?.get(1)?.let {
            try { OffsetDateTime.parse(it, DATE_FMT).toInstant().toEpochMilli() } catch (_: Exception) { null }
        }
        return id to last
    }

    /** Pastas e arquivos que um app deixa em ~/Library e que sobram depois de arrastá-lo para a Lixeira. */
    fun leftovers(home: File, app: MacApp): List<FileEntry> {
        val lib = File(home, "Library")
        val ids = listOfNotNull(app.bundleId)
        val names = listOf(app.name)
        val candidates = ArrayList<File>()
        for (n in names + ids) {
            candidates += File(lib, "Application Support/$n")
            candidates += File(lib, "Logs/$n")
            candidates += File(lib, "Caches/$n")
        }
        for (id in ids) {
            candidates += File(lib, "Preferences/$id.plist")
            candidates += File(lib, "Containers/$id")
            candidates += File(lib, "Saved Application State/$id.savedState")
            candidates += File(lib, "HTTPStorages/$id")
            candidates += File(lib, "WebKit/$id")
            candidates += File(lib, "Application Scripts/$id")
            candidates += File(lib, "Cookies/$id.binarycookies")
        }
        val existing = candidates.distinct().filter { it.exists() }
        val sizes = PhysicalSize.of(existing.map { it.path })
        return existing.map { f ->
            FileEntry(f.path, sizes[f.path] ?: if (f.isFile) f.length() else 0L, f.lastModified(), isDir = f.isDirectory)
        }.filter { it.size > 0 }.sortedByDescending { it.size }
    }
}
