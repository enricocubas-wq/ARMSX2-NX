package app.faxina.mac

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit

class DiskUsage(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

/** Tudo que fala com o macOS: Finder, Lixeira, permissões, Keychain. Cada função falha sem derrubar o app. */
object Ops {
    val isMac: Boolean = System.getProperty("os.name", "").lowercase().contains("mac")
    val home: File = File(System.getProperty("user.home"))

    /** Roda um comando e devolve (código de saída, saída padrão). */
    fun run(vararg cmd: String, input: String? = null, timeoutSec: Long = 30): Pair<Int, String> = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) } else p.outputStream.close()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to text
        } else {
            p.exitValue() to text
        }
    } catch (e: Exception) {
        -1 to (e.message ?: "")
    }

    fun diskUsage(): DiskUsage {
        // No macOS moderno os dados do usuário ficam neste volume
        val f = File("/System/Volumes/Data").takeIf { it.exists() } ?: File("/")
        return DiskUsage(f.totalSpace, f.usableSpace)
    }

    fun open(path: String) {
        if (isMac) run("open", path)
    }

    fun reveal(path: String) {
        if (isMac) run("open", "-R", path)
    }

    fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else if (isMac) {
                run("open", url)
            }
        } catch (_: Exception) {
            if (isMac) run("open", url)
        }
    }

    /** Move para a Lixeira do macOS (dá para desfazer pelo Finder). */
    fun moveToTrash(f: File): Boolean = try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            Desktop.getDesktop().moveToTrash(f)
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    /** Esvazia a Lixeira pelo Finder (funciona sem "Acesso Total ao Disco", mas pede permissão uma vez). */
    fun emptyTrash(): Boolean {
        if (!isMac) return false
        val (code, _) = run("osascript", "-e", "tell application \"Finder\" to empty trash", timeoutSec = 180)
        return code == 0
    }

    /** true/false se dá para saber; null se não há como testar neste Mac. */
    fun hasFullDiskAccess(): Boolean? {
        if (!isMac) return null
        val probes = listOf(File(home, "Library/Safari"), File(home, "Library/Mail"), File("/Library/Application Support/com.apple.TCC"))
        for (p in probes) {
            if (!p.exists()) continue
            return try {
                if (p.isDirectory) Files.newDirectoryStream(p.toPath()).use { it.iterator().hasNext() } else p.inputStream().close()
                true
            } catch (_: Exception) {
                false
            }
        }
        return null
    }

    fun openFullDiskAccessSettings() {
        if (isMac) run("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")
    }

    fun appSupportDir(): File = File(home, "Library/Application Support/Faxina").also { it.mkdirs() }
}

/** Preferências simples (nada secreto) em ~/Library/Application Support/Faxina. */
class Settings(name: String) {
    private val file = File(Ops.appSupportDir(), "$name.properties")
    private val props = Properties().also { p -> if (file.exists()) runCatching { file.inputStream().use { p.load(it) } } }

    fun get(key: String): String? = props.getProperty(key)
    fun getLong(key: String, default: Long = 0) = props.getProperty(key)?.toLongOrNull() ?: default

    fun put(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    fun clear() {
        props.clear()
        save()
    }

    private fun save() {
        runCatching { file.outputStream().use { props.store(it, "Faxina") } }
    }
}

/** Guarda a senha de app do e-mail no Keychain do macOS (nunca em arquivo). */
object Keychain {
    private const val SERVICE = "Faxina E-mail"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Usa `security -i` para a senha entrar por entrada padrão e não aparecer na lista de processos. */
    fun put(account: String, secret: String): Boolean {
        if (!Ops.isMac) return false
        val cmd = "add-generic-password -U -a ${q(account)} -s ${q(SERVICE)} -w ${q(secret)}\n"
        Ops.run("security", "-i", input = cmd)
        return get(account) == secret
    }

    fun get(account: String): String? {
        if (!Ops.isMac) return null
        val (code, out) = Ops.run("security", "find-generic-password", "-a", account, "-s", SERVICE, "-w")
        return if (code == 0) out.trimEnd('\n', '\r').ifEmpty { null } else null
    }

    fun remove(account: String) {
        if (Ops.isMac) Ops.run("security", "delete-generic-password", "-a", account, "-s", SERVICE)
    }
}
