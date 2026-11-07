package app.faxina.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class DeleteResult(val freedBytes: Long, val deleted: List<String>, val failed: List<String>)

/** Apaga arquivos e pastas escolhidos. Nunca segue links simbólicos. */
object Deleter {
    fun delete(entries: Collection<FileEntry>, onProgress: ((done: Int, total: Int) -> Unit)? = null): DeleteResult {
        var freed = 0L
        val ok = ArrayList<String>()
        val bad = ArrayList<String>()
        var i = 0
        for (e in entries) {
            val path = File(e.path).toPath()
            val success = try {
                when {
                    e.isDir && e.contentsOnly -> deleteContents(path)
                    e.isDir -> deleteTree(path)
                    else -> Files.deleteIfExists(path).let { true }
                }
            } catch (_: Exception) { false }
            if (success) {
                freed += e.size
                ok.add(e.path)
            } else {
                // Algo dentro estava em uso: conta o que saiu de fato.
                if (e.isDir) freed += (e.size - sizeOf(path)).coerceAtLeast(0)
                bad.add(e.path)
            }
            onProgress?.invoke(++i, entries.size)
        }
        return DeleteResult(freed, ok, bad)
    }

    private fun deleteContents(dir: Path): Boolean {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        var allOk = true
        Files.newDirectoryStream(dir).use { st -> for (child in st) if (!deleteTree(child)) allOk = false }
        return allOk
    }

    /** Bytes que ainda restam em [p] (0 se sumiu). */
    private fun sizeOf(p: Path): Long = try {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) 0L
        else Files.walk(p).use { st ->
            st.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    } catch (_: Exception) { 0L }

    private fun deleteTree(root: Path): Boolean {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        var allOk = true
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try { Files.deleteIfExists(file) } catch (_: IOException) { allOk = false }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                allOk = false
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                try { Files.deleteIfExists(dir) } catch (_: IOException) { allOk = false }
                return FileVisitResult.CONTINUE
            }
        })
        return allOk
    }
}
