package app.faxina.core

import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.system.exitProcess

/** Mini-harness sem dependências: roda no Gradle (tarefa selfTest) e direto com kotlinc/java. */
class Harness {
    var failures = 0
    var passed = 0

    fun test(name: String, body: () -> Unit) {
        try {
            body()
            passed++
            println("  ok   $name")
        } catch (e: Throwable) {
            failures++
            println("  FAIL $name -> ${e::class.simpleName}: ${e.message}")
            e.stackTrace.take(6).forEach { println("         at $it") }
        }
    }
}

fun expect(cond: Boolean, msg: () -> String = { "expectativa falhou" }) {
    if (!cond) throw AssertionError(msg())
}

fun <T> expectEq(expected: T, actual: T, what: String = "") {
    if (expected != actual) throw AssertionError("$what esperado=<$expected> obtido=<$actual>")
}

fun main() {
    val h = Harness()
    println("Núcleo: formatação")
    formatTests(h)
    println("Núcleo: varredura e regras")
    scannerTests(h)
    println("Núcleo: duplicados e remoção")
    duplicateTests(h)
    println("E-mail: IMAP")
    imapTests(h)
    println("\n${h.passed} ok, ${h.failures} falhas")
    if (h.failures > 0) exitProcess(1)
}

private fun formatTests(h: Harness) {
    h.test("formatBytes") {
        expectEq("0 B", formatBytes(0))
        expectEq("999 B", formatBytes(999))
        expectEq("1,5 KB", formatBytes(1500))
        expectEq("12 KB", formatBytes(12_000))
        expectEq("1,23 GB", formatBytes(1_234_000_000))
        expectEq("47,2 GB", formatBytes(47_240_000_000))
        expectEq("128 GB", formatBytes(128_000_000_000))
        expectEq("2,00 TB", formatBytes(2_000_000_000_000))
    }
    h.test("categoryOf") {
        expectEq(FileCategory.IMAGE, categoryOf("A.JPG"))
        expectEq(FileCategory.VIDEO, categoryOf("clip.mp4"))
        expectEq(FileCategory.APK, categoryOf("x.apk"))
        expectEq(FileCategory.OTHER, categoryOf("noext"))
        expectEq(FileCategory.OTHER, categoryOf("trailingdot."))
    }
}

private val DAY = 24L * 3600 * 1000

private fun File.write(rel: String, bytes: ByteArray, ageDays: Long = 0): File {
    val f = File(this, rel)
    f.parentFile.mkdirs()
    f.writeBytes(bytes)
    if (ageDays > 0) f.setLastModified(System.currentTimeMillis() - ageDays * DAY)
    return f
}

private fun bytes(seed: Long, n: Int): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

private fun scannerTests(h: Harness) {
    val root = Files.createTempDirectory("faxina-root").toFile()
    try {
        val a = bytes(1, 200_000)
        val big = bytes(2, 2_000_000)
        root.write("DCIM/Camera/IMG_1.jpg", a, ageDays = 400)
        root.write("DCIM/Camera/IMG_2.jpg", a, ageDays = 10)
        root.write("DCIM/Camera/.trashed-1700000000-IMG_9.jpg", bytes(9, 120_000))
        root.write("DCIM/.thumbnails/.thumbdata3-1", bytes(3, 300_000))
        root.write("DCIM/.private/a.jpg", a)
        root.write("Download/IMG_1_copy.jpg", a, ageDays = 30)
        root.write("Download/old.zip", bytes(4, 150_000), ageDays = 200)
        root.write("Download/setup.apk", bytes(5, 150_000))
        root.write("Download/x.tmp", bytes(6, 1_000))
        root.write("Download/partial.crdownload", bytes(7, 1_000), ageDays = 0)
        root.write("Download/stale.crdownload", bytes(8, 1_000), ageDays = 10)
        root.write("Documents/build.log", bytes(10, 1_000)) // log em Documentos NÃO é lixo
        root.write("Notes/app.log", bytes(11, 1_000)) // log fora de mídia é lixo
        root.write("WhatsApp/Media/WhatsApp Images/IMG-1.jpg", bytes(12, 150_000))
        root.write("WhatsApp/Media/WhatsApp Images/Sent/IMG-2.jpg", bytes(13, 150_000))
        root.write("WhatsApp/Media/.Statuses/s1.mp4", bytes(14, 400_000))
        root.write("WhatsApp/Databases/msgstore-2024-01-01.1.db.crypt14", bytes(15, 50_000), ageDays = 30)
        root.write("WhatsApp/Databases/msgstore.db.crypt14", bytes(16, 50_000), ageDays = 1)
        root.write("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/v.mp4", bytes(17, 300_000))
        root.write("Android/media/com.other/file.bin", bytes(18, 300_000))
        root.write("Android/data/com.x/cache/c.bin", bytes(19, 250_000))
        root.write("Android/data/com.x/files/f.bin", bytes(20, 250_000))
        root.write("Telegram/Telegram Images/t.jpg", bytes(21, 150_000))
        root.write("Pictures/Screenshots/s.png", bytes(22, 150_000))
        root.write("Movies/big.mp4", big)
        root.write("SomeApp/cache/blob.bin", bytes(23, 100_000))
        File(root, "EmptyDir1").mkdirs()
        File(root, "Foo/Bar/Baz").mkdirs()
        File(root, "Documents/Sub").mkdirs()
        // link simbólico não deve ser seguido (nem contado duas vezes)
        try { Files.createSymbolicLink(File(root, "Download/link").toPath(), File(root, "DCIM").toPath()) } catch (_: Exception) {}

        val opts = ScanOptions(listOf(root), bigFileBytes = 1_000_000, dupMinBytes = 100_000, parallelism = 4)
        val res = Scanner(opts).scan()

        fun paths(g: GroupId) = res.group(g)?.items?.map { it.path.removePrefix(root.path + "/") }?.sorted() ?: emptyList()

        h.test("lixo temporário") {
            expectEq(listOf("Download/stale.crdownload", "Download/x.tmp", "Notes/app.log"), paths(GroupId.TEMP_FILES), "TEMP_FILES")
        }
        h.test("miniaturas e cache") {
            expectEq(listOf("Android/data/com.x/cache", "DCIM/.thumbnails", "SomeApp/cache"), paths(GroupId.CACHE_THUMBS), "CACHE_THUMBS")
            val thumbs = res.group(GroupId.CACHE_THUMBS)!!.items.first { it.path.endsWith("DCIM/.thumbnails") }
            expect(thumbs.isDir && thumbs.size == 300_000L) { "pasta .thumbnails deve somar 300000, veio ${thumbs.size}" }
        }
        h.test("lixeira da galeria") {
            expectEq(listOf("DCIM/Camera/.trashed-1700000000-IMG_9.jpg"), paths(GroupId.GALLERY_TRASH), "GALLERY_TRASH")
        }
        h.test("WhatsApp") {
            expectEq(listOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/v.mp4"), paths(GroupId.WA_VIDEOS), "WA_VIDEOS")
            expectEq(listOf("WhatsApp/Media/WhatsApp Images/IMG-1.jpg"), paths(GroupId.WA_IMAGES), "WA_IMAGES")
            expectEq(listOf("WhatsApp/Media/WhatsApp Images/Sent/IMG-2.jpg"), paths(GroupId.WA_SENT), "WA_SENT")
            expectEq(listOf("WhatsApp/Media/.Statuses"), paths(GroupId.WA_STATUS), "WA_STATUS")
            expectEq(listOf("WhatsApp/Databases/msgstore-2024-01-01.1.db.crypt14"), paths(GroupId.WA_BACKUPS), "WA_BACKUPS")
        }
        h.test("Telegram, capturas, grandes, APK, downloads antigos") {
            expectEq(listOf("Telegram/Telegram Images/t.jpg"), paths(GroupId.TG_IMAGES), "TG_IMAGES")
            expectEq(listOf("Pictures/Screenshots/s.png"), paths(GroupId.SCREENSHOTS), "SCREENSHOTS")
            expectEq(listOf("Movies/big.mp4"), paths(GroupId.BIG_FILES), "BIG_FILES")
            expectEq(listOf("Download/setup.apk"), paths(GroupId.APK_OTHER), "APK_OTHER")
            expectEq(listOf("Download/old.zip"), paths(GroupId.OLD_DOWNLOADS), "OLD_DOWNLOADS")
        }
        h.test("pastas vazias (só a mais externa; as raízes padrão ficam)") {
            expectEq(listOf("Documents/Sub", "EmptyDir1", "Foo"), paths(GroupId.EMPTY_DIRS), "EMPTY_DIRS")
        }
        h.test("arquivos de apps em Android/ não viram sugestão") {
            val all = res.groups.values.flatMap { it.items }.map { it.path }
            expect(all.none { it.contains("com.other") || it.endsWith("com.x/files/f.bin") }) { "vazou arquivo de app" }
        }
        h.test("candidatos a duplicado: tamanho repetido, sem ocultos nem lixo") {
            val c = res.dupCandidates[200_000L]!!.map { it.path.removePrefix(root.path + "/") }.sorted()
            expectEq(listOf("DCIM/Camera/IMG_1.jpg", "DCIM/Camera/IMG_2.jpg", "Download/IMG_1_copy.jpg"), c, "candidatos")
        }
        h.test("totais e categorias ignoram link simbólico") {
            val real = Files.walk(root.toPath()).use { st ->
                st.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
            }
            expectEq(real, res.totalBytes, "totalBytes")
            expect(res.bytesByCategory.getValue(FileCategory.APK) == 150_000L) { "bytes de APK" }
        }
        h.test("árvore de pastas soma certo e vem ordenada") {
            val node = res.tree.single()
            expectEq(res.totalBytes, node.size, "tree size")
            val sizes = node.children.map { it.size }
            expectEq(sizes.sortedDescending(), sizes, "ordenação")
        }
        h.test("progresso conta arquivos") {
            expect(res.totalFiles > 20) { "totalFiles=${res.totalFiles}" }
        }

        // Duplicados de ponta a ponta sobre o resultado
        h.test("duplicados exatos: mantém DCIM/Camera, sugere apagar o resto") {
            val dup = Duplicates.find(res.dupCandidates, threads = 2)
            expectEq(1, dup.groups.size, "conjuntos")
            val g = Duplicates.flatten(dup)
            val keep = g.items.single { it.keep }
            expect(keep.path.endsWith("DCIM/Camera/IMG_1.jpg") || keep.path.endsWith("DCIM/Camera/IMG_2.jpg")) { "keeper=${keep.path}" }
            expectEq(400_000L, g.bytes, "bytes liberáveis")
            // o mais antigo da câmera é o original
            expect(keep.path.endsWith("IMG_1.jpg")) { "deveria manter o mais antigo: ${keep.path}" }
        }
        h.test("remoção libera o que prometeu e não toca no resto") {
            val trash = res.group(GroupId.CACHE_THUMBS)!!.items + res.group(GroupId.EMPTY_DIRS)!!.items
            val r = Deleter.delete(trash)
            expectEq(0, r.failed.size, "falhas")
            expect(!File(root, "DCIM/.thumbnails").exists() && !File(root, "Foo").exists() && !File(root, "EmptyDir1").exists())
            expect(File(root, "DCIM/Camera/IMG_1.jpg").exists()) { "foto não pode sumir" }
            expectEq(trash.sumOf { it.size }, r.freedBytes, "freedBytes")
        }
    } finally {
        root.deleteRecursively()
    }
}

private fun duplicateTests(h: Harness) {
    h.test("mesmo tamanho, mesmo início/fim, miolo diferente NÃO é duplicado") {
        val dir = Files.createTempDirectory("faxina-dup").toFile()
        try {
            val base = bytes(50, 1_000_000)
            val other = base.copyOf()
            other[500_000 + 200_000] = (other[700_000].toInt() xor 0x55).toByte() // longe das amostras de 64 KB
            val f1 = dir.write("a.bin", base)
            val f2 = dir.write("b.bin", other)
            val f3 = dir.write("c.bin", base)
            val cands = mapOf(1_000_000L to listOf(f1, f2, f3).map { FileEntry(it.path, it.length(), it.lastModified()) })
            val r = Duplicates.find(cands, threads = 2)
            expectEq(1, r.groups.size, "conjuntos")
            expectEq(setOf("a.bin", "c.bin"), r.groups[0].map { it.name }.toSet(), "membros")
        } finally { dir.deleteRecursively() }
    }
    h.test("pontuação de cópia original") {
        val ordered = Duplicates.orderKeeperFirst(
            listOf(
                FileEntry("/s/Download/a.jpg", 1, 5),
                FileEntry("/s/WhatsApp/Media/WhatsApp Images/a.jpg", 1, 1),
                FileEntry("/s/DCIM/Camera/a.jpg", 1, 9),
            ),
        )
        expectEq("/s/DCIM/Camera/a.jpg", ordered.first().path, "original")
    }
}
