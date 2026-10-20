package app.faxina.core.mail

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Uma resposta IMAP já com os "literais" ({123}\r\n...) trocados por marcadores no texto.
 * Assim o parser enxerga uma única string e os bytes continuam exatos.
 */
internal class Line(val text: String, val literals: List<String>)

internal const val LIT_OPEN = ''
internal const val LIT_CLOSE = ''

internal class ImapReader(private val input: InputStream) {
    private val buf = ByteArray(32 * 1024)
    private var pos = 0
    private var lim = 0

    private fun read(): Int {
        if (pos == lim) {
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return -1
            pos = 0
            lim = n
        }
        return buf[pos++].toInt() and 0xff
    }

    private fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            if (pos == lim) {
                val r = input.read(buf, 0, buf.size)
                if (r < 0) throw EOFException("Conexão encerrada pelo servidor")
                pos = 0
                lim = r
            }
            val take = minOf(n - off, lim - pos)
            System.arraycopy(buf, pos, out, off, take)
            pos += take
            off += take
        }
        return out
    }

    fun readLine(): Line {
        val text = StringBuilder()
        val lits = ArrayList<String>()
        val chunk = ByteArrayOutputStream(256)
        while (true) {
            chunk.reset()
            while (true) {
                val b = read()
                if (b < 0) throw EOFException("Conexão encerrada pelo servidor")
                if (b == '\n'.code) break
                chunk.write(b)
            }
            var s = chunk.toString("UTF-8")
            if (s.endsWith("\r")) s = s.dropLast(1)
            val m = LITERAL_END.find(s)
            if (m == null) {
                text.append(clean(s))
                return Line(text.toString(), lits)
            }
            val n = m.groupValues[1].toLong()
            if (n > MAX_LITERAL) throw IOException("Resposta grande demais do servidor ($n bytes)")
            text.append(clean(s.substring(0, m.range.first)))
            lits.add(String(readFully(n.toInt()), Charsets.UTF_8))
            text.append(LIT_OPEN).append(lits.size - 1).append(LIT_CLOSE)
        }
    }

    private fun clean(s: String) = if (s.indexOf(LIT_OPEN) >= 0 || s.indexOf(LIT_CLOSE) >= 0) s.replace(LIT_OPEN, '?').replace(LIT_CLOSE, '?') else s

    companion object {
        private val LITERAL_END = Regex("\\{(\\d+)\\+?\\}$")
        private const val MAX_LITERAL = 16L * 1024 * 1024
    }
}

internal sealed class Node {
    data class Atom(val value: String) : Node()
    data class Str(val value: String) : Node()
    object Nil : Node()
    data class Lst(val items: List<Node>) : Node()

    /** Texto de átomo/string; null para NIL e listas. */
    val textOrNull: String?
        get() = when (this) {
            is Atom -> value
            is Str -> value
            else -> null
        }
}

/** Parser de respostas IMAP: átomos, strings entre aspas, literais, NIL e listas aninhadas. */
internal class ImapParser(private val s: String, private val literals: List<String>) {
    private var i = 0

    fun parseAll(): List<Node> {
        val out = ArrayList<Node>()
        while (true) {
            skipWs()
            if (i >= s.length) return out
            out.add(parseNode())
        }
    }

    private fun skipWs() {
        while (i < s.length && s[i] == ' ') i++
    }

    private fun parseNode(): Node = when (s[i]) {
        '(' -> parseList()
        '"' -> parseQuoted()
        LIT_OPEN -> parseLiteral()
        else -> parseAtom()
    }

    private fun parseList(): Node {
        i++ // (
        val items = ArrayList<Node>()
        while (true) {
            skipWs()
            if (i >= s.length) return Node.Lst(items) // lista truncada: devolve o que deu
            if (s[i] == ')') { i++; return Node.Lst(items) }
            items.add(parseNode())
        }
    }

    private fun parseQuoted(): Node {
        i++ // "
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
            if (c == '"') { i++; break }
            sb.append(c)
            i++
        }
        return Node.Str(sb.toString())
    }

    private fun parseLiteral(): Node {
        i++ // LIT_OPEN
        val end = s.indexOf(LIT_CLOSE, i)
        val idx = s.substring(i, end).toInt()
        i = end + 1
        return Node.Str(literals[idx])
    }

    private fun parseAtom(): Node {
        val start = i
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '(' || c == ')' || c == '"' || c == LIT_OPEN) break
            if (c == '[') { // BODY[HEADER.FIELDS (FROM SUBJECT)] e [UIDVALIDITY 1] têm espaços e parênteses
                val close = s.indexOf(']', i)
                i = if (close < 0) s.length else close + 1
                continue
            }
            i++
        }
        val v = s.substring(start, i)
        return if (v.equals("NIL", ignoreCase = true)) Node.Nil else Node.Atom(v)
    }
}

internal fun parseLine(line: Line): List<Node> = ImapParser(line.text, line.literals).parseAll()
