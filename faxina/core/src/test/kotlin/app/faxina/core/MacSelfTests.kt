package app.faxina.core

import app.faxina.core.mac.MacApp
import app.faxina.core.mac.MacApps
import app.faxina.core.mac.MacGroupId
import app.faxina.core.mac.MacScanOptions
import app.faxina.core.mac.MacScanner
import app.faxina.core.mac.PhysicalSize
import app.faxina.core.findDirNode
import java.io.File
import java.nio.file.Files
import java.util.Random

private fun File.put(rel: String, size: Int, seed: Long = rel.hashCode().toLong(), ageDays: Long = 0): File {
    val f = File(this, rel)
    f.parentFile.mkdirs()
    f.writeBytes(ByteArray(size).also { Random(seed).nextBytes(it) })
    if (ageDays > 0) f.setLastModified(System.currentTimeMillis() - ageDays * 24L * 3600 * 1000)
    return f
}

internal fun macTests(h: Harness) {
    val home = Files.createTempDirectory("faxina-home").toFile()
    try {
        val same = ByteArray(700_000).also { Random(5).nextBytes(it) }
        home.put("Library/Caches/com.foo.app/blob", 2_000_000)
        home.put("Library/Caches/com.tiny.app/blob", 10_000)
        home.put("Library/Caches/com.apple.Safari/blob", 3_000_000)
        home.put("Library/Caches/Homebrew/downloads/x", 4_000_000)
        home.put("Library/Logs/DiagnosticReports/crash.ips", 300_000)
        home.put("Library/Logs/old.log", 200_000)
        home.put("Library/Application Support/Slack/Cache/Cache_Data/f", 1_500_000)
        home.put("Library/Application Support/Slack/storage/keep.db", 900_000)
        home.put("Library/Application Support/Code/User/History/x", 100_000)
        home.put("Library/Application Support/MobileSync/Backup/ABC123/Manifest.db", 5_000_000)
        home.put("Library/Developer/Xcode/DerivedData/Proj-abc/Build/o", 6_000_000)
        home.put("Library/Developer/Xcode/iOS DeviceSupport/17.0/sym", 2_500_000)
        home.put("Library/Mobile Documents/com~apple~CloudDocs/doc.pdf", 8_000_000)
        home.put(".Trash/velho.zip", 1_200_000)
        home.put(".Trash/pasta/a.bin", 800_000)
        home.put(".npm/_cacache/content/x", 2_200_000)
        home.put(".cache/huggingface/model.bin", 60_000_000)
        home.put("Downloads/app.dmg", 3_000_000)
        home.put("Downloads/foto.jpg", 700_000, seed = 5)
        File(home, "Downloads/foto-copia.jpg").writeBytes(same)
        File(home, "Downloads/foto.jpg").writeBytes(same)
        home.put("Documents/trabalho/foto-original.jpg", 1, seed = 1)
        File(home, "Documents/trabalho/foto-original.jpg").writeBytes(same)
        File(home, "Documents/trabalho/foto-original.jpg").setLastModified(System.currentTimeMillis() - 400L * 24 * 3600 * 1000)
        home.put("Downloads/antigo.zip", 400_000, ageDays = 200)
        home.put("Downloads/parcial.crdownload", 5_000, ageDays = 10)
        home.put("Desktop/Captura de Tela 2025-01-01 as 10.00.00.png", 300_000)
        home.put("Desktop/projeto/node_modules/pkg/big.bin", 900_000, seed = 77)
        home.put("Documents/projeto/node_modules/pkg/big2.bin", 900_000, seed = 77)
        home.put("Movies/filme.mov", 3_000_000)
        home.put("Pictures/Fotos.photoslibrary/originals/a.jpg", 3_000_000, seed = 9)
        home.put("Pictures/Fotos.photoslibrary/originals/b.jpg", 3_000_000, seed = 9)
        home.put("Documents/Maquina.pvm/disk.hdd", 4_000_000)
        home.put("Documents/lixo/__MACOSX/._x", 400_000)
        File(home, "Documents/vazia").mkdirs()

        val opts = MacScanOptions(home, bigFileBytes = 2_500_000, dupMinBytes = 500_000, parallelism = 4)
        val res = MacScanner(opts).scan()
        fun names(g: MacGroupId) = res.group(g)?.items?.map { it.path.removePrefix(home.path + "/") }?.sorted() ?: emptyList()

        h.test("Mac: caches de apps (sem Apple, sem os minúsculos, sem os de desenvolvedor)") {
            expectEq(
                listOf("Library/Application Support/Slack/Cache", "Library/Caches/com.foo.app"),
                names(MacGroupId.APP_CACHES), "APP_CACHES",
            )
        }
        h.test("Mac: logs") {
            expectEq(listOf("Library/Logs/DiagnosticReports", "Library/Logs/old.log"), names(MacGroupId.LOGS), "LOGS")
        }
        h.test("Mac: ferramentas de desenvolvedor (seguras x pesadas)") {
            expectEq(
                listOf(".npm/_cacache", "Library/Caches/Homebrew", "Library/Developer/Xcode/DerivedData"),
                names(MacGroupId.DEV_CACHES), "DEV_CACHES",
            )
            expectEq(
                listOf(".cache/huggingface", "Library/Developer/Xcode/iOS DeviceSupport"),
                names(MacGroupId.DEV_HEAVY), "DEV_HEAVY",
            )
        }
        h.test("Mac: backups de iPhone, Lixeira, temporários e __MACOSX") {
            expectEq(listOf("Library/Application Support/MobileSync/Backup/ABC123"), names(MacGroupId.IOS_BACKUPS), "IOS_BACKUPS")
            expectEq(listOf(".Trash/pasta", ".Trash/velho.zip"), names(MacGroupId.TRASH), "TRASH")
            expectEq(listOf("Documents/lixo/__MACOSX", "Downloads/parcial.crdownload"), names(MacGroupId.TEMP_FILES), "TEMP_FILES")
        }
        h.test("Mac: instaladores, capturas, downloads antigos") {
            expectEq(listOf("Downloads/app.dmg"), names(MacGroupId.INSTALLERS), "INSTALLERS")
            expectEq(listOf("Desktop/Captura de Tela 2025-01-01 as 10.00.00.png"), names(MacGroupId.SCREENSHOTS), "SCREENSHOTS")
            expectEq(listOf("Downloads/antigo.zip"), names(MacGroupId.OLD_DOWNLOADS), "OLD_DOWNLOADS")
        }
        h.test("Mac: grandes ignoram bibliotecas, node_modules e iCloud; máquina virtual entra inteira") {
            val big = names(MacGroupId.BIG_FILES)
            expect("Documents/Maquina.pvm" in big) { "pvm faltando: $big" }
            expect("Movies/filme.mov" in big && "Downloads/app.dmg" in big) { "grandes: $big" }
            expect(big.none { it.contains("photoslibrary") || it.contains("node_modules") || it.contains("Mobile Documents") }) { "vazou: $big" }
        }
        h.test("Mac: iCloud (Mobile Documents) não é percorrido") {
            val total = home.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            expect(res.homeBytes < total) { "homeBytes=${res.homeBytes} total=$total" }
            expect(findDirNode(res.tree, "${home.path}/Library/Mobile Documents")?.size == 0L) { "iCloud deveria contar 0" }
        }
        h.test("Mac: biblioteca de Fotos e node_modules não viram candidatos a duplicado") {
            val cands = res.dupCandidates.values.flatten().map { it.path.removePrefix(home.path + "/") }
            expect(cands.none { it.contains("photoslibrary") || it.contains("node_modules") }) { "candidatos: $cands" }
            expect("Downloads/foto.jpg" in cands && "Documents/trabalho/foto-original.jpg" in cands)
        }
        h.test("Mac: duplicados exatos mantêm a cópia de Documentos, não a de Downloads") {
            val g = MacScanner.findDuplicates(res)
            val keep = g.items.single { it.keep }
            expect(keep.path.endsWith("Documents/trabalho/foto-original.jpg")) { "keeper=${keep.path}" }
            expectEq(2, g.count, "cópias a apagar (foto.jpg e foto-copia.jpg)")
        }
        h.test("Mac: totais — Biblioteca separada e categorias só fora dela") {
            expect(res.libraryBytes > 25_000_000L) { "library=${res.libraryBytes}" }
            expect((res.bytesByCategory[FileCategory.IMAGE] ?: 0) >= 3 * 700_000L)
            expectEq(findDirNode(res.tree, home.path)!!.size, res.homeBytes)
        }
        h.test("Mac: tamanho físico (du) de um arquivo esparso é bem menor que o lógico") {
            val f = File(home, "esparso.bin")
            java.io.RandomAccessFile(f, "rw").use { it.setLength(50_000_000); it.seek(0); it.write(1) }
            val real = PhysicalSize.of(f)
            expect(real != null && real < 5_000_000L) { "físico=$real" }
        }
    } finally {
        home.deleteRecursively()
    }

    h.test("MacApps: lê a saída do mdls") {
        val (id, last) = MacApps.parseMdlsForTest(
            "kMDItemCFBundleIdentifier = \"com.tinyspeck.slackmacgap\"\nkMDItemLastUsedDate        = 2025-03-04 12:30:00 +0000\n",
        )
        expectEq("com.tinyspeck.slackmacgap", id)
        expectEq(java.time.Instant.parse("2025-03-04T12:30:00Z").toEpochMilli(), last)
        val (id2, last2) = MacApps.parseMdlsForTest("kMDItemCFBundleIdentifier = (null)\nkMDItemLastUsedDate        = (null)\n")
        expect(id2 == null && last2 == null)
    }
    h.test("MacApps: restos do app em ~/Library") {
        val home2 = Files.createTempDirectory("faxina-home2").toFile()
        try {
            home2.put("Library/Application Support/Slack/a", 300_000)
            home2.put("Library/Caches/com.tinyspeck.slackmacgap/b", 200_000)
            home2.put("Library/Preferences/com.tinyspeck.slackmacgap.plist", 4_000)
            home2.put("Library/Application Support/OutroApp/x", 900_000)
            val app = MacApp("Slack", "/Applications/Slack.app", 1, "com.tinyspeck.slackmacgap", null)
            val left = MacApps.leftovers(home2, app).map { it.path.removePrefix(home2.path + "/") }.sorted()
            expectEq(
                listOf(
                    "Library/Application Support/Slack",
                    "Library/Caches/com.tinyspeck.slackmacgap",
                    "Library/Preferences/com.tinyspeck.slackmacgap.plist",
                ),
                left, "restos",
            )
        } finally { home2.deleteRecursively() }
    }
}
