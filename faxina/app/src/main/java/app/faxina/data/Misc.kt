package app.faxina.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.faxina.core.extensionOf
import java.io.File

/** Diz se um APK solto na memória já está instalado (mesma versão ou mais nova). */
object ApkInspector {
    fun isAlreadyInstalled(pm: PackageManager, path: String): Boolean? {
        return try {
            val archive = pm.getPackageArchiveInfo(path, 0) ?: return null
            val installed = try {
                pm.getPackageInfo(archive.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return false
            }
            PackageInfoCompat.getLongVersionCode(installed) >= PackageInfoCompat.getLongVersionCode(archive)
        } catch (_: Exception) {
            null
        }
    }
}

object MediaRefresh {
    /** Avisa a galeria que esses arquivos sumiram, para as miniaturas fantasmas desaparecerem. */
    fun forgetDeleted(ctx: Context, paths: List<String>) {
        paths.chunked(400).forEach { chunk ->
            try {
                MediaScannerConnection.scanFile(ctx.applicationContext, chunk.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
    }
}

object Openers {
    fun openFile(ctx: Context, path: String): Boolean {
        return try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(file.name)) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun appDetails(ctx: Context, pkg: String) {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    fun uninstall(ctx: Context, pkg: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    /** Tenta o botão "limpar cache de todos os apps" do sistema. Devolve false se este celular não tem. */
    fun clearAllAppCache(ctx: Context): Boolean {
        val intents = listOf(
            Intent(StorageManager.ACTION_CLEAR_APP_CACHE),
            Intent(StorageManager.ACTION_MANAGE_STORAGE),
        )
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    val sdk: Int get() = Build.VERSION.SDK_INT
}
