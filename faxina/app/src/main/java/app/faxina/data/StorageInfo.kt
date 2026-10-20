package app.faxina.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

class StorageUsage(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

object StorageInfo {
    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    fun usage(root: File = primaryRoot()): StorageUsage = try {
        val s = StatFs(root.path)
        StorageUsage(s.totalBytes, s.availableBytes)
    } catch (_: Exception) {
        StorageUsage(0, 0)
    }

    /** Armazenamento interno + cartão SD montado (se houver). */
    fun scanRoots(ctx: Context): List<File> {
        val roots = LinkedHashSet<File>()
        roots.add(primaryRoot())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (v in sm.storageVolumes) {
                    val dir = v.directory ?: continue
                    if (!v.isPrimary && v.state == Environment.MEDIA_MOUNTED) roots.add(dir)
                }
            } else {
                for (dir in ctx.getExternalFilesDirs(null).drop(1)) {
                    val path = dir?.absolutePath ?: continue
                    val i = path.indexOf("/Android/")
                    if (i > 0) roots.add(File(path.substring(0, i)))
                }
            }
        } catch (_: Exception) {
        }
        return roots.filter { it.isDirectory }
    }
}
