package app.faxina.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

class FakeMsg(
    val uid: Long,
    val size: Long,
    val date: String,
    val flags: MutableSet<String>,
    val from: String,
    val subject: String,
    val unsub: Boolean,
    val category: String? = null,
)

/** Servidor IMAP de brinquedo (só o que o app usa) para testar o cliente de ponta a ponta. */
class FakeImapServer(
    private val user: String,
    private val password: String,
    private val gmail: Boolean = true,
    private val move: Boolean = true,
) : AutoCloseable {
    class Folder(val name: String, val flags: String, val msgs: MutableList<FakeMsg> = ArrayList())

    val folders = LinkedHashMap<String, Folder>()
    private val server = ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val log = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var nextUid = 5000L
    @Volatile private var running = true

    init {
        thread(isDaemon = true, name = "fake-imap") {
            while (running) {
                try { handle(server.accept()) } catch (_: Exception) { }
            }
        }
    }

    fun folder(name: String) = folders.getValue(name)

    private fun caps(): String {
        val base = "IMAP4rev1 UIDPLUS AUTH=PLAIN"
        return base + (if (move) " MOVE" else "") + (if (gmail) " X-GM-EXT-1" else "")
    }

    private fun handle(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val out = BufferedOutputStream(s.getOutputStream())
        fun w(line: String) { out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() }
        fun readLine(): String? {
            val sb = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
                if (b == '\n'.code) return sb.toString("UTF-8").trimEnd('\r')
                sb.write(b)
            }
        }
        w("* OK [CAPABILITY ${caps()}] fake ready")
        var selected: Folder? = null
        var authed = false
        while (true) {
            val line = readLine() ?: break
            log.add(line.replace(password, "***"))
            val tag = line.substringBefore(' ')
            val cmd = line.substringAfter(' ')
            val verb = cmd.substringBefore(' ').uppercase()
            val rest = cmd.substringAfter(' ', "")
            when (verb) {
                "CAPABILITY" -> { w("* CAPABILITY ${caps()}"); w("$tag OK done") }
                "LOGIN" -> {
                    val args = splitArgs(rest)
                    if (args[0] == user && args[1] == password) { authed = true; w("$tag OK [CAPABILITY ${caps()}] logged in") }
                    else w("$tag NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)")
                }
                "AUTHENTICATE" -> {
                    w("+ ")
                    val blob = String(Base64.getDecoder().decode(readLine() ?: ""), Charsets.UTF_8).split('\u0000')
                    if (blob.size == 3 && blob[1] == user && blob[2] == password) { authed = true; w("$tag OK authenticated") }
                    else w("$tag NO [AUTHENTICATIONFAILED] bad")
                }
                "LOGOUT" -> { w("* BYE bye"); w("$tag OK logout"); break }
                "LIST" -> {
                    for (f in folders.values) {
                        val flags = if (f.flags.isEmpty()) "\\HasNoChildren" else "\\HasNoChildren ${f.flags}"
                        w("* LIST ($flags) \"/\" \"${f.name}\"")
                    }
                    w("$tag OK list done")
                }
                "EXAMINE", "SELECT" -> {
                    val name = splitArgs(rest)[0]
                    val f = folders[name]
                    if (f == null) { w("$tag NO no such folder") } else {
                        selected = f
                        w("* ${f.msgs.size} EXISTS")
                        w("* OK [UIDVALIDITY 777] ok")
                        w("* OK [UIDNEXT $nextUid] ok")
                        w("$tag OK [${if (verb == "EXAMINE") "READ-ONLY" else "READ-WRITE"}] done")
                    }
                }
                "FETCH" -> {
                    val f = selected!!
                    val range = rest.substringBefore(' ')
                    val items = rest.substringAfter(' ')
                    val seqs = parseSet(range, f.msgs.size)
                    for (seq in seqs) {
                        val m = f.msgs[seq - 1]
                        if (items.contains("BODY.PEEK")) {
                            val hdr = buildString {
                                append("From: ${m.from}\r\n")
                                append("Subject: ${m.subject}\r\n")
                                if (m.unsub) append("List-Unsubscribe: <mailto:u@x.com>,\r\n <https://x.com/u>\r\n")
                                append("\r\n")
                            }
                            val flags = m.flags.joinToString(" ")
                            val bytes = hdr.toByteArray(Charsets.UTF_8)
                            out.write("* $seq FETCH (UID ${m.uid} RFC822.SIZE ${m.size} INTERNALDATE \"${m.date}\" FLAGS ($flags) BODY[HEADER.FIELDS (FROM SUBJECT LIST-UNSUBSCRIBE)] {${bytes.size}}\r\n".toByteArray())
                            out.write(bytes)
                            out.write(")\r\n".toByteArray())
                        } else {
                            out.write("* $seq FETCH (RFC822.SIZE ${m.size})\r\n".toByteArray())
                        }
                    }
                    out.flush()
                    w("$tag OK fetch done")
                }
                "UID" -> {
                    val sub = rest.substringBefore(' ').uppercase()
                    val args = rest.substringAfter(' ', "")
                    val f = selected!!
                    when (sub) {
                        "SEARCH" -> {
                            val q = splitArgs(args.substringAfter("X-GM-RAW ")).firstOrNull() ?: ""
                            val cat = q.removePrefix("category:")
                            val hit = f.msgs.filter { it.category == cat }.map { it.uid }
                            w("* SEARCH ${hit.joinToString(" ")}".trimEnd())
                            w("$tag OK search done")
                        }
                        "MOVE", "COPY" -> {
                            val set = args.substringBefore(' ')
                            val dest = folders[splitArgs(args.substringAfter(' '))[0]]
                            if (dest == null) { w("$tag NO [TRYCREATE] no dest"); continue }
                            val uids = parseUidSet(set, f)
                            val moved = f.msgs.filter { it.uid in uids }
                            for (m in moved) dest.msgs.add(FakeMsg(nextUid++, m.size, m.date, m.flags.toMutableSet(), m.from, m.subject, m.unsub, m.category))
                            if (sub == "MOVE") f.msgs.removeAll(moved.toSet())
                            w("$tag OK ${sub.lowercase()} done")
                        }
                        "STORE" -> {
                            val set = args.substringBefore(' ')
                            val uids = parseUidSet(set, f)
                            f.msgs.filter { it.uid in uids }.forEach { it.flags.add("\\Deleted") }
                            w("$tag OK store done")
                        }
                        "EXPUNGE" -> {
                            val uids = parseUidSet(args, f)
                            f.msgs.removeAll { it.uid in uids && "\\Deleted" in it.flags }
                            w("$tag OK expunge done")
                        }
                        else -> w("$tag BAD unknown UID command")
                    }
                }
                "STORE" -> {
                    selected!!.msgs.forEach { it.flags.add("\\Deleted") }
                    w("$tag OK store done")
                }
                "EXPUNGE" -> {
                    selected!!.msgs.removeAll { "\\Deleted" in it.flags }
                    w("$tag OK expunge done")
                }
                else -> w("$tag BAD unknown command $verb")
            }
            if (!authed && verb !in setOf("CAPABILITY", "LOGIN", "AUTHENTICATE", "LOGOUT")) { /* ignora: teste confia no cliente */ }
        }
        try { s.close() } catch (_: Exception) {}
    }

    private fun splitArgs(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == ' ') { i++; continue }
            if (s[i] == '"') {
                val sb = StringBuilder(); i++
                while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; sb.append(s[i]); i++ }
                i++; out.add(sb.toString())
            } else {
                val j = s.indexOf(' ', i).let { if (it < 0) s.length else it }
                out.add(s.substring(i, j)); i = j
            }
        }
        return out
    }

    private fun parseSet(set: String, max: Int): List<Int> {
        val out = ArrayList<Int>()
        for (part in set.split(',')) {
            if (part.contains(':')) {
                val a = part.substringBefore(':'); val b = part.substringAfter(':')
                val lo = a.toInt(); val hi = if (b == "*") max else b.toInt()
                for (x in lo..minOf(hi, max)) out.add(x)
            } else out.add(part.toInt())
        }
        return out
    }

    private fun parseUidSet(set: String, f: Folder): Set<Long> {
        val out = HashSet<Long>()
        for (part in set.trim().split(',')) {
            if (part.isEmpty()) continue
            if (part.contains(':')) {
                val lo = part.substringBefore(':').toLong(); val hi = part.substringAfter(':').toLong()
                f.msgs.filter { it.uid in lo..hi }.forEach { out.add(it.uid) }
            } else out.add(part.toLong())
        }
        return out
    }

    override fun close() {
        running = false
        try { server.close() } catch (_: Exception) {}
    }
}
