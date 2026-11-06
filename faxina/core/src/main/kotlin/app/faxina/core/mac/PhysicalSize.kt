package app.faxina.core.mac

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tamanho que o arquivo realmente ocupa no disco (blocos alocados), via `du`.
 * Importante no Mac: arquivos "esparsos" (discos de máquina virtual, Docker) e arquivos do iCloud
 * ainda na nuvem têm tamanho lógico enorme mas ocupam quase nada.
 */
object PhysicalSize {
    fun of(paths: List<String>, batch: Int = 150): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (chunk in paths.chunked(batch)) {
            try {
                val p = ProcessBuilder(listOf("du", "-sk", "--") + chunk)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                p.outputStream.close()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(90, TimeUnit.SECONDS)
                for (line in text.lineSequence()) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val kb = line.substring(0, tab).trim().toLongOrNull() ?: continue
                    out[line.substring(tab + 1)] = kb * 1024
                }
            } catch (_: Exception) {
                // sem `du` (ou erro): devolvemos o que deu e o chamador cai no tamanho lógico
            }
        }
        return out
    }

    fun of(file: File): Long? = of(listOf(file.path))[file.path]
}
