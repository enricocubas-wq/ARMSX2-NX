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
            val success = try {
                if (e.isDir) deleteTree(File(e.path).toPath()) else Files.deleteIfExists(File(e.path).toPath()).let { true }
            } catch (_: IOException) { false } catch (_: SecurityException) { false }
            if (success) { freed += e.size; ok.add(e.path) } else bad.add(e.path)
            onProgress?.invoke(++i, entries.size)
        }
        return DeleteResult(freed, ok, bad)
    }

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
