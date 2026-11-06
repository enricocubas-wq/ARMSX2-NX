package app.faxina.mac

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.faxina.core.mac.MacGroupId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.Random

private fun File.zeros(rel: String, mb: Double, ageDays: Long = 0, seed: Long = -1) {
    val f = File(this, rel); f.parentFile.mkdirs()
    val n = (mb * 1_000_000).toLong()
    f.outputStream().buffered().use { o ->
        val buf = ByteArray(1 shl 16)
        if (seed >= 0) Random(seed).nextBytes(buf)
        var left = n
        while (left > 0) { val k = minOf(left, buf.size.toLong()).toInt(); o.write(buf, 0, k); left -= k }
    }
    if (ageDays > 0) f.setLastModified(System.currentTimeMillis() - ageDays * 86_400_000L)
}

private fun populate(h: File) {
    h.zeros("Library/Caches/com.spotify.client/Storage/a", 420.0, seed = 1)
    h.zeros("Library/Caches/com.google.Chrome/Default/Cache/b", 310.0, seed = 2)
    h.zeros("Library/Caches/com.slack.Slack/c", 95.0, seed = 3)
    h.zeros("Library/Caches/Homebrew/downloads/d", 640.0, seed = 4)
    h.zeros("Library/Logs/DiagnosticReports/x.ips", 12.0, seed = 5)
    h.zeros("Library/Developer/Xcode/DerivedData/App-abc/Build/e", 1400.0, seed = 6)
    h.zeros("Library/Developer/Xcode/iOS DeviceSupport/17.2/f", 2100.0, seed = 7)
    h.zeros("Library/Application Support/MobileSync/Backup/00008110-0012/g", 1800.0, seed = 8)
    h.zeros("Library/Application Support/Slack/Cache/Cache_Data/h", 180.0, seed = 9)
    h.zeros("Library/Application Support/Code/storage.db", 60.0, seed = 10)
    h.zeros(".Trash/Antigo.zip", 230.0, seed = 11)
    h.zeros(".Trash/Projeto velho/x", 340.0, seed = 12)
    h.zeros(".npm/_cacache/content/i", 520.0, seed = 13)
    h.zeros("Downloads/Xcode_15.dmg", 160.0, seed = 14)
    h.zeros("Downloads/Zoom.pkg", 120.0, seed = 15)
    h.zeros("Downloads/Relatório final.pdf", 8.0, ageDays = 200, seed = 16)
    h.zeros("Downloads/fotos-viagem.zip", 140.0, ageDays = 150, seed = 17)
    h.zeros("Desktop/Captura de Tela 2026-10-01 as 10.20.30.png", 3.5, seed = 18)
    h.zeros("Desktop/Captura de Tela 2026-10-02 as 11.20.30.png", 2.5, seed = 19)
    h.zeros("Movies/Casamento.mov", 1900.0, seed = 20)
    h.zeros("Movies/Aula gravada.mp4", 950.0, seed = 21)
    h.zeros("Pictures/Ferias/IMG_0001.jpg", 4.0, seed = 22)
    h.zeros("Documents/Backup/IMG_0001.jpg", 4.0, ageDays = 700, seed = 22)
    h.zeros("Downloads/IMG_0001 (1).jpg", 4.0, seed = 22)
    h.zeros("Documents/Maquina.pvm/disk.hdd", 5200.0, seed = 23)
    File(h, "Applications/Slack.app/Contents/MacOS").mkdirs()
    h.zeros("Applications/Slack.app/Contents/MacOS/Slack", 380.0, seed = 24)
    h.zeros("Applications/Figma.app/Contents/MacOS/Figma", 520.0, seed = 25)
    h.zeros("Applications/Antigo.app/Contents/MacOS/Antigo", 910.0, seed = 26)
}

@OptIn(ExperimentalComposeUiApi::class)
fun shot(out: File, name: String, dark: Boolean, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(1770, 1200, Density(1.5f)) { FaxinaTheme(dark = dark) { content() } }
    var t = 0L
    repeat(90) { scene.render(t); t += 20_000_000; Thread.sleep(8) }
    val img = scene.render(t)
    File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("shot $name")
}

/**
 * Gera PNGs das telas do app com dados de mentira (para conferir o visual sem abrir o Mac).
 * Uso: ./gradlew :mac:previewShots -PshotsDir=/caminho
 */
fun main(args: Array<String>) {
    val out = File(args[0]).also { it.mkdirs() }
    val home = Files.createTempDirectory("fakemac").toFile()
    populate(home)
    System.setProperty("user.home", home.path)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = MacController(scope)
    val mail = MailController(scope)
    runBlocking {
        c.scan.first { it is ScanUi.Done }
        c.dup.first { it is DupUi.Done }
    }
    c.loadApps()
    runBlocking { c.apps.first { it is AppsUi.Ready } }
    for (dark in listOf(true, false)) {
        val sfx = if (dark) "dark" else "light"
        c.go(Tab.HOME); shot(out, "home-$sfx", dark) { App(c, mail) }
        c.openGroup(MacGroupId.APP_CACHES); shot(out, "clean-caches-$sfx", dark) { App(c, mail) }
        if (dark) {
            c.openGroup(MacGroupId.DUPLICATES); shot(out, "clean-dups-$sfx", dark) { App(c, mail) }
            c.openGroup(MacGroupId.BIG_FILES); shot(out, "clean-big-$sfx", dark) { App(c, mail) }
            c.go(Tab.FOLDERS); shot(out, "folders-$sfx", dark) { App(c, mail) }
            c.go(Tab.APPS); shot(out, "apps-$sfx", dark) { App(c, mail) }
            c.go(Tab.MAIL); shot(out, "mail-$sfx", dark) { App(c, mail) }
        }
    }
    home.deleteRecursively()
    System.exit(0)
}
