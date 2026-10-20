package app.faxina.core.mail

import java.nio.charset.Charset
import java.util.Base64

/** Decodifica cabeçalhos no formato =?UTF-8?B?...?= (RFC 2047), comuns em assunto e remetente. */
object Rfc2047 {
    private val WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")

    fun decode(input: String): String {
        if (!input.contains("=?")) return input
        val sb = StringBuilder()
        var last = 0
        var prevEncoded = false
        for (m in WORD.findAll(input)) {
            val between = input.substring(last, m.range.first)
            val decoded = decodeWord(m)
            // Espaço entre duas palavras codificadas seguidas não conta (RFC 2047 §6.2)
            if (!(prevEncoded && between.isBlank() && decoded != null)) sb.append(between)
            if (decoded != null) {
                sb.append(decoded)
                prevEncoded = true
            } else {
                sb.append(m.value)
                prevEncoded = false
            }
            last = m.range.last + 1
        }
        sb.append(input.substring(last))
        return sb.toString()
    }

    private fun decodeWord(m: MatchResult): String? = try {
        val charset = Charset.forName(m.groupValues[1].substringBefore('*'))
        val enc = m.groupValues[2].uppercase()
        val text = m.groupValues[3]
        val bytes = if (enc == "B") Base64.getMimeDecoder().decode(text) else decodeQ(text)
        String(bytes, charset)
    } catch (_: Exception) {
        null
    }

    private fun decodeQ(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '_' -> out.write(' '.code)
                c == '=' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2]) -> {
                    out.write(s.substring(i + 1, i + 3).toInt(16)); i += 2
                }
                else -> out.write(c.code)
            }
            i++
        }
        return out.toByteArray()
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}

/** Nomes de pasta IMAP vêm em "UTF-7 modificado" (&AOM- = á). */
object ImapUtf7 {
    fun decode(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val end = s.indexOf('-', i + 1)
            if (end < 0) { sb.append(s.substring(i)); break }
            if (end == i + 1) {
                sb.append('&')
            } else {
                val b64 = s.substring(i + 1, end).replace(',', '/')
                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                try {
                    sb.append(String(Base64.getDecoder().decode(padded), Charsets.UTF_16BE))
                } catch (_: IllegalArgumentException) {
                    sb.append(s, i, end + 1)
                }
            }
            i = end + 1
        }
        return sb.toString()
    }
}

/** Lê o bloco de cabeçalhos (já "desdobrado") devolvido por BODY[HEADER.FIELDS (...)]. */
internal object HeaderBlock {
    fun parse(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        var name: String? = null
        val value = StringBuilder()
        fun flush() {
            val n = name
            if (n != null && !out.containsKey(n)) out[n] = value.toString().trim()
            name = null
            value.setLength(0)
        }
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && name != null) {
                value.append(' ').append(line.trim())
                continue
            }
            flush()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            name = line.substring(0, colon).trim().lowercase()
            value.append(line.substring(colon + 1).trim())
        }
        flush()
        return out
    }

    /** "Fulano <fulano@x.com>" → (nome, endereço em minúsculas). */
    fun parseAddress(from: String): Pair<String, String> {
        val decoded = Rfc2047.decode(from).trim()
        val lt = decoded.lastIndexOf('<')
        val gt = decoded.lastIndexOf('>')
        if (lt >= 0 && gt > lt) {
            val addr = decoded.substring(lt + 1, gt).trim().lowercase()
            val name = decoded.substring(0, lt).trim().trim('"').trim()
            return name to addr
        }
        val addr = decoded.split(' ', ',', ';').firstOrNull { it.contains('@') }?.trim('<', '>', '"')?.lowercase()
        return "" to (addr ?: decoded.lowercase())
    }
}
