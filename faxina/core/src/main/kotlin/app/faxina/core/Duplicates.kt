package app.faxina.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Duplicados exatos em 3 etapas, da mais barata à mais cara:
 *  1. mesmo tamanho (já feito pela varredura);
 *  2. mesma "amostra" (início + meio + fim do arquivo);
 *  3. mesmo SHA-1 do arquivo inteiro (só para quem passou da etapa 2).
 * Só apagamos quando o conteúdo é idêntico byte a byte (mesmo hash completo).
 */
object Duplicates {
    private const val SAMPLE = 64 * 1024
    private const val BUFFER = 256 * 1024

    class Result(val groups: List<List<FileEntry>>) {
        val wastedBytes: Long get() = groups.sumOf { g -> g.first().size * (g.size - 1) }
    }

    fun find(
        candidates: Map<Long, List<FileEntry>>,
        progress: ScanProgress? = null,
        cancel: CancelToken = CancelToken(),
        threads: Int = 3,
    ): Result {
        progress?.phase = ScanProgress.Phase.DUPLICATES
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 6))
        try {
            // Maiores primeiro: o usuário vê os ganhos grandes logo no início.
            val sizes = candidates.keys.sortedDescending()
            val out = ArrayList<List<FileEntry>>()
            for (batch in sizes.chunked(threads * 4)) {
                if (cancel.cancelled) break
                val jobs = batch.map { size -> Callable { identicalSets(candidates.getValue(size), progress, cancel) } }
                for (f in pool.invokeAll(jobs)) out.addAll(f.get())
            }
            out.sortByDescending { it.first().size * (it.size - 1) }
            return Result(out)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun identicalSets(files: List<FileEntry>, progress: ScanProgress?, cancel: CancelToken): List<List<FileEntry>> {
        // Etapa 2: amostra
        val bySample = LinkedHashMap<String, MutableList<FileEntry>>()
        for (f in files) {
            if (cancel.cancelled) return emptyList()
            val key = try { sampleHash(File(f.path), f.size) } catch (_: Exception) { continue }
            bySample.getOrPut(key) { ArrayList() }.add(f)
        }
        // Etapa 3: arquivo inteiro
        val result = ArrayList<List<FileEntry>>()
        for (same in bySample.values) {
            if (same.size < 2) continue
            val byFull = LinkedHashMap<String, MutableList<FileEntry>>()
            for (f in same) {
                if (cancel.cancelled) return emptyList()
                val key = try { fullHash(File(f.path), progress, cancel) } catch (_: Exception) { continue }
                byFull.getOrPut(key) { ArrayList() }.add(f)
            }
            for (g in byFull.values) if (g.size >= 2) result.add(orderKeeperFirst(g))
        }
        return result
    }

    private fun sampleHash(file: File, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(SAMPLE)
        RandomAccessFile(file, "r").use { raf ->
            val offsets = if (size <= 3L * SAMPLE) longArrayOf(0) else longArrayOf(0, size / 2 - SAMPLE / 2, size - SAMPLE)
            for (off in offsets) {
                raf.seek(off)
                val want = minOf(SAMPLE.toLong(), size - off).toInt()
                var read = 0
                while (read < want) {
                    val n = raf.read(buf, read, want - read)
                    if (n < 0) break
                    read += n
                }
                md.update(buf, 0, read)
            }
        }
        return toHex(md.digest())
    }

    private fun fullHash(file: File, progress: ScanProgress?, cancel: CancelToken): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(BUFFER)
        file.inputStream().use { input ->
            while (true) {
                if (cancel.cancelled) throw InterruptedException()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                progress?.hashedBytes?.addAndGet(n.toLong())
            }
        }
        return toHex(md.digest())
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    /** Cópia "original" primeiro: pastas de câmera/biblioteca ganham de Download/WhatsApp/Telegram. */
    internal fun keeperScore(path: String): Int {
        val p = path.lowercase()
        var s = 0
        if ("/dcim/camera" in p) s += 50
        else if ("/dcim/" in p) s += 40
        if ("/pictures/" in p || "/movies/" in p || "/music/" in p || "/documents/" in p) s += 30
        if ("/download" in p) s -= 10
        if ("/whatsapp" in p || "/telegram" in p) s -= 20
        if ("/sent/" in p) s -= 10
        if ("/screenshots/" in p) s -= 5
        if ("/." in p) s -= 15
        return s
    }

    fun orderKeeperFirst(group: List<FileEntry>): List<FileEntry> =
        group.sortedWith(
            compareByDescending<FileEntry> { keeperScore(it.path) }
                .thenBy { it.modified }
                .thenBy { it.path.length }
                .thenBy { it.path },
        )

    /** Converte conjuntos em entradas planas com setId/keep para a UI. */
    fun flatten(result: Result): CleanGroup {
        val items = ArrayList<FileEntry>()
        result.groups.forEachIndexed { i, g ->
            g.forEachIndexed { j, f -> items.add(f.copy(setId = i, keep = j == 0)) }
        }
        return CleanGroup(GroupId.DUPLICATES, items)
    }
}
