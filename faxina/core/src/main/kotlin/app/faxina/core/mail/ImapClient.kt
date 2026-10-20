package app.faxina.core.mail

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class Security { SSL, STARTTLS, NONE }

class ImapConfig(
    val host: String,
    val port: Int = 993,
    val user: String,
    val password: String,
    val security: Security = Security.SSL,
    val timeoutMs: Int = 90_000,
)

class ImapException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, AUTH, REJECTED, PROTOCOL }
}

enum class FolderRole { INBOX, ALL, TRASH, SPAM, SENT, DRAFTS, ARCHIVE, OTHER }

/** [raw] é o nome como o servidor mandou (usado nos comandos); [display] é legível. */
class MailFolder(val raw: String, val display: String, val delimiter: String?, val flags: Set<String>, val role: FolderRole)

class SelectInfo(val exists: Int, val uidValidity: Long, val uidNext: Long)

class FetchedHeader(
    val seq: Int,
    val uid: Long,
    val size: Long,
    /** dias desde 1970 (UTC) da data de recebimento. */
    val epochDay: Int,
    val seen: Boolean,
    val flagged: Boolean,
    val from: String,
    val subject: String,
    val hasUnsubscribe: Boolean,
)

internal class Reply(val status: String, val text: String, val lines: List<Line>) {
    val ok: Boolean get() = status == "OK"
}

/**
 * Cliente IMAP mínimo e rápido, escrito sob medida para limpeza: só lê cabeçalhos (nunca baixa
 * anexos), busca em lotes e move/apaga por UID. Funciona com Gmail (extensões X-GM), Outlook,
 * Yahoo, iCloud e qualquer servidor IMAP4rev1.
 */
class ImapClient(private val cfg: ImapConfig) : Closeable {
    private lateinit var socket: Socket
    private lateinit var reader: ImapReader
    private lateinit var out: OutputStream
    private var tagCounter = 0
    private var loggedOut = false

    var capabilities: Set<String> = emptySet()
        private set

    val isGmail: Boolean get() = "X-GM-EXT-1" in capabilities
    val hasMove: Boolean get() = "MOVE" in capabilities
    val hasUidPlus: Boolean get() = "UIDPLUS" in capabilities

    // ---------------------------------------------------------------- conexão

    fun connect() {
        try {
            val raw = Socket()
            raw.connect(InetSocketAddress(cfg.host, cfg.port), minOf(cfg.timeoutMs, 20_000))
            raw.soTimeout = cfg.timeoutMs
            socket = if (cfg.security == Security.SSL) wrapTls(raw) else raw
            attach(socket.getInputStream(), socket.getOutputStream())
            val greeting = reader.readLine().text
            if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH")) {
                throw ImapException("Esse servidor não parece ser IMAP (respondeu: ${greeting.take(80)})", ImapException.Kind.PROTOCOL)
            }
            refreshCapabilities()
            if (cfg.security == Security.STARTTLS) {
                executeOk("STARTTLS")
                socket = wrapTls(socket)
                attach(socket.getInputStream(), socket.getOutputStream())
                refreshCapabilities()
            }
        } catch (e: SocketTimeoutException) {
            throw ImapException("O servidor demorou demais para responder", ImapException.Kind.NETWORK, e)
        } catch (e: IOException) {
            throw ImapException("Não consegui conectar em ${cfg.host}: ${e.message ?: e.javaClass.simpleName}", ImapException.Kind.NETWORK, e)
        }
    }

    private fun attach(input: InputStream, output: OutputStream) {
        reader = ImapReader(input.buffered(64 * 1024))
        out = output.buffered(16 * 1024)
    }

    private fun wrapTls(raw: Socket): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, cfg.host, cfg.port, true) as SSLSocket
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.soTimeout = cfg.timeoutMs
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(cfg.host, ssl.session)) {
            ssl.close()
            throw SSLPeerUnverifiedException("Certificado não confere com ${cfg.host}")
        }
        return ssl
    }

    private fun refreshCapabilities() {
        val caps = HashSet<String>()
        val r = execute("CAPABILITY") { l ->
            if (l.text.startsWith("* CAPABILITY", ignoreCase = true)) {
                l.text.substring("* CAPABILITY".length).trim().split(' ').filter { it.isNotEmpty() }.forEach { caps.add(it.uppercase()) }
            }
        }
        if (!r.ok) throw ImapException("CAPABILITY recusado: ${r.text}", ImapException.Kind.PROTOCOL)
        capabilities = caps
    }

    fun login() {
        if ("LOGINDISABLED" in capabilities) {
            throw ImapException("Esse servidor exige conexão segura (SSL/TLS) para entrar", ImapException.Kind.AUTH)
        }
        val plain = cfg.user.all { it.code in 0x20..0x7e } && cfg.password.all { it.code in 0x20..0x7e }
        val reply = if (plain) execute("LOGIN ${quote(cfg.user)} ${quote(cfg.password)}") else authenticatePlain()
        if (!reply.ok) throw ImapException(reply.text.ifBlank { "Login recusado" }, ImapException.Kind.AUTH)
        refreshCapabilities()
    }

    private fun authenticatePlain(): Reply {
        val tag = nextTag()
        send("$tag AUTHENTICATE PLAIN")
        while (true) {
            val l = readOrThrow()
            if (l.text.startsWith("+")) break
            if (l.text.startsWith("$tag ")) return tagged(tag, l, emptyList())
        }
        send(Base64.getEncoder().encodeToString("\u0000${cfg.user}\u0000${cfg.password}".toByteArray(Charsets.UTF_8)))
        return awaitTagged(tag, null)
    }

    fun logout() {
        if (loggedOut) return
        loggedOut = true
        try {
            val tag = nextTag()
            send("$tag LOGOUT")
        } catch (_: IOException) {
        }
    }

    override fun close() {
        logout()
        try { socket.close() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- comandos básicos

    private fun nextTag() = "F${++tagCounter}"

    private fun send(line: String) {
        try {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\r'.code)
            out.write('\n'.code)
            out.flush()
        } catch (e: IOException) {
            throw ImapException("Conexão perdida: ${e.message}", ImapException.Kind.NETWORK, e)
        }
    }

    private fun readOrThrow(): Line = try {
        reader.readLine()
    } catch (e: SocketTimeoutException) {
        throw ImapException("O servidor demorou demais para responder", ImapException.Kind.NETWORK, e)
    } catch (e: IOException) {
        throw ImapException("Conexão perdida: ${e.message}", ImapException.Kind.NETWORK, e)
    }

    private fun tagged(tag: String, line: Line, untagged: List<Line>): Reply {
        val rest = line.text.substring(tag.length + 1)
        return Reply(rest.substringBefore(' ').uppercase(), rest.substringAfter(' ', ""), untagged)
    }

    private fun awaitTagged(tag: String, onUntagged: ((Line) -> Unit)?): Reply {
        val collected = if (onUntagged == null) ArrayList<Line>() else null
        while (true) {
            val line = readOrThrow()
            val t = line.text
            if (t.startsWith("$tag ")) return tagged(tag, line, collected ?: emptyList())
            if (t.startsWith("* BYE")) throw ImapException("O servidor encerrou a conexão: ${t.drop(6)}", ImapException.Kind.NETWORK)
            if (t.startsWith("+")) continue
            if (onUntagged != null) onUntagged(line) else collected!!.add(line)
        }
    }

    internal fun execute(command: String, onUntagged: ((Line) -> Unit)? = null): Reply {
        val tag = nextTag()
        send("$tag $command")
        return awaitTagged(tag, onUntagged)
    }

    internal fun executeOk(command: String, onUntagged: ((Line) -> Unit)? = null): Reply {
        val r = execute(command, onUntagged)
        if (!r.ok) {
            throw ImapException("${command.substringBefore(' ')} recusado pelo servidor: ${r.status} ${r.text}".trim(), ImapException.Kind.REJECTED)
        }
        return r
    }

    // ---------------------------------------------------------------- pastas

    fun listFolders(): List<MailFolder> {
        val out = ArrayList<MailFolder>()
        executeOk("LIST \"\" \"*\"") { line ->
            val n = parseLine(line)
            // * LIST (flags) "delim" nome
            if (n.size >= 5 && n[1].textOrNull.equals("LIST", ignoreCase = true) && n[2] is Node.Lst) {
                val flags = (n[2] as Node.Lst).items.mapNotNull { it.textOrNull?.lowercase() }.toSet()
                if ("\\noselect" !in flags && "\\nonexistent" !in flags) {
                    val raw = n[4].textOrNull ?: return@executeOk
                    val delim = n[3].textOrNull
                    out.add(MailFolder(raw, ImapUtf7.decode(raw), delim, flags, FolderRole.OTHER))
                }
            }
        }
        return assignRoles(out)
    }

    fun select(folder: MailFolder, readOnly: Boolean = true): SelectInfo {
        var exists = 0
        var validity = 0L
        var next = 0L
        executeOk("${if (readOnly) "EXAMINE" else "SELECT"} ${quote(folder.raw)}") { l ->
            val t = l.text
            if (t.endsWith(" EXISTS") && t.startsWith("* ")) exists = t.substring(2, t.length - 7).trim().toIntOrNull() ?: exists
            UIDVALIDITY.find(t)?.let { validity = it.groupValues[1].toLong() }
            UIDNEXT.find(t)?.let { next = it.groupValues[1].toLong() }
        }
        return SelectInfo(exists, validity, next)
    }

    // ---------------------------------------------------------------- leitura

    /** Busca cabeçalhos das mensagens [from]..[to] (números de sequência), sem marcar como lidas. */
    fun fetchHeaders(from: Int, to: Int, onHeader: (FetchedHeader) -> Unit) {
        executeOk("FETCH $from:$to (UID RFC822.SIZE INTERNALDATE FLAGS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT LIST-UNSUBSCRIBE)])") { line ->
            parseFetchHeader(line)?.let(onHeader)
        }
    }

    /** Soma o tamanho de todas as mensagens da pasta selecionada: (quantidade, bytes). */
    fun sumSizes(exists: Int, onProgress: ((Int) -> Unit)? = null): Pair<Int, Long> {
        var count = 0
        var total = 0L
        var start = 1
        while (start <= exists) {
            val end = minOf(exists, start + SIZE_BATCH - 1)
            executeOk("FETCH $start:$end (RFC822.SIZE)") { line ->
                val n = parseLine(line)
                if (n.size >= 4 && n[2].textOrNull.equals("FETCH", ignoreCase = true) && n[3] is Node.Lst) {
                    val items = (n[3] as Node.Lst).items
                    var k = 0
                    while (k + 1 < items.size) {
                        if (items[k].textOrNull.equals("RFC822.SIZE", ignoreCase = true)) {
                            total += items[k + 1].textOrNull?.toLongOrNull() ?: 0
                            count++
                        }
                        k += 2
                    }
                }
            }
            onProgress?.invoke(end)
            start = end + 1
        }
        return count to total
    }

    fun uidSearch(criteria: String): LongArray {
        var result = LongArray(0)
        executeOk("UID SEARCH $criteria") { line ->
            val t = line.text
            if (t.startsWith("* SEARCH", ignoreCase = true)) result = parseNumbers(t, "* SEARCH".length)
        }
        return result
    }

    /** Busca no estilo do Gmail: `category:promotions`, `larger:10M`, `older_than:2y`... */
    fun gmailSearch(query: String): LongArray {
        require(isGmail) { "Só disponível no Gmail" }
        return uidSearch("X-GM-RAW ${quote(query)}")
    }

    // ---------------------------------------------------------------- escrita

    /** Move para a lixeira (ou copia e marca como apagado se o servidor não tem MOVE). */
    fun moveToFolder(uids: LongArray, dest: MailFolder) {
        for (set in uidSets(uids)) {
            if (hasMove) {
                executeOk("UID MOVE $set ${quote(dest.raw)}")
            } else {
                executeOk("UID COPY $set ${quote(dest.raw)}")
                markDeleted(set)
                expunge(set)
            }
        }
    }

    /** Apaga de vez (marca \Deleted e expurga) na pasta atualmente selecionada. */
    fun deleteForever(uids: LongArray) {
        for (set in uidSets(uids)) {
            markDeleted(set)
            expunge(set)
        }
    }

    fun emptySelectedFolder(exists: Int) {
        if (exists <= 0) return
        executeOk("STORE 1:* +FLAGS.SILENT (\\Deleted)")
        executeOk("EXPUNGE")
    }

    private fun markDeleted(set: String) {
        executeOk("UID STORE $set +FLAGS.SILENT (\\Deleted)")
    }

    private fun expunge(set: String) {
        if (hasUidPlus) executeOk("UID EXPUNGE $set") else executeOk("EXPUNGE")
    }

    // ---------------------------------------------------------------- parsing

    private fun parseFetchHeader(line: Line): FetchedHeader? {
        val n = parseLine(line)
        if (n.size < 4 || !n[2].textOrNull.equals("FETCH", ignoreCase = true)) return null
        val items = (n[3] as? Node.Lst)?.items ?: return null
        var uid = 0L
        var size = 0L
        var day = 0
        var seen = false
        var flagged = false
        var headers = emptyMap<String, String>()
        var k = 0
        while (k + 1 < items.size) {
            val key = items[k].textOrNull?.uppercase() ?: break
            val v = items[k + 1]
            when {
                key == "UID" -> uid = v.textOrNull?.toLongOrNull() ?: 0
                key == "RFC822.SIZE" -> size = v.textOrNull?.toLongOrNull() ?: 0
                key == "INTERNALDATE" -> day = parseInternalDateDay(v.textOrNull ?: "")
                key == "FLAGS" -> {
                    val flags = (v as? Node.Lst)?.items?.mapNotNull { it.textOrNull?.lowercase() } ?: emptyList()
                    seen = "\\seen" in flags
                    flagged = "\\flagged" in flags
                }
                key.startsWith("BODY[") -> headers = HeaderBlock.parse(v.textOrNull ?: "")
            }
            k += 2
        }
        if (uid == 0L) return null
        return FetchedHeader(
            seq = n[1].textOrNull?.toIntOrNull() ?: 0,
            uid = uid,
            size = size,
            epochDay = day,
            seen = seen,
            flagged = flagged,
            from = headers["from"] ?: "",
            subject = Rfc2047.decode(headers["subject"] ?: ""),
            hasUnsubscribe = headers.containsKey("list-unsubscribe"),
        )
    }

    companion object {
        private val UIDVALIDITY = Regex("\\[UIDVALIDITY (\\d+)]")
        private val UIDNEXT = Regex("\\[UIDNEXT (\\d+)]")
        private const val SIZE_BATCH = 2000
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** "17-Jul-1996 02:44:25 -0700" → dias desde 1970. 0 se não entender. */
        fun parseInternalDateDay(s: String): Int {
            val m = Regex("^\\s*(\\d{1,2})-([A-Za-z]{3})-(\\d{4})").find(s) ?: return 0
            val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            if (month == 0) return 0
            return try {
                java.time.LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()).toEpochDay().toInt()
            } catch (_: Exception) { 0 }
        }

        internal fun parseNumbers(text: String, from: Int): LongArray {
            var out = LongArray(256)
            var n = 0
            var i = from
            val len = text.length
            while (i < len) {
                while (i < len && (text[i] < '0' || text[i] > '9')) i++
                if (i >= len) break
                var v = 0L
                while (i < len && text[i] in '0'..'9') { v = v * 10 + (text[i] - '0'); i++ }
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = v
            }
            return out.copyOf(n)
        }

        /** [1,2,3,7,9,10] → ["1:3,7,9:10"], quebrado em comandos de no máximo ~6 KB. */
        fun uidSets(uids: LongArray, maxLen: Int = 6000): List<String> {
            if (uids.isEmpty()) return emptyList()
            val sorted = uids.copyOf().also { it.sort() }
            val sets = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] <= sorted[j] + 1) j++
                val part = if (sorted[i] == sorted[j]) "${sorted[i]}" else "${sorted[i]}:${sorted[j]}"
                if (sb.isNotEmpty() && sb.length + part.length + 1 > maxLen) {
                    sets.add(sb.toString()); sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(part)
                i = j + 1
            }
            if (sb.isNotEmpty()) sets.add(sb.toString())
            return sets
        }

        private val TRASH_NAMES = setOf("trash", "deleted items", "deleted messages", "deleted", "lixeira", "itens excluídos", "itens excluidos", "excluídos", "papelera", "corbeille", "bin")
        private val SPAM_NAMES = setOf("spam", "junk", "junk e-mail", "junk email", "lixo eletrônico", "lixo eletronico", "bulk mail", "correo no deseado", "indesejados")

        internal fun assignRoles(folders: List<MailFolder>): List<MailFolder> {
            fun byFlag(f: Set<String>): FolderRole? = when {
                "\\all" in f -> FolderRole.ALL
                "\\trash" in f -> FolderRole.TRASH
                "\\junk" in f -> FolderRole.SPAM
                "\\sent" in f -> FolderRole.SENT
                "\\drafts" in f -> FolderRole.DRAFTS
                "\\archive" in f -> FolderRole.ARCHIVE
                else -> null
            }
            val assigned = folders.map { f ->
                val role = if (f.raw.equals("INBOX", ignoreCase = true)) FolderRole.INBOX else byFlag(f.flags) ?: FolderRole.OTHER
                MailFolder(f.raw, f.display, f.delimiter, f.flags, role)
            }.toMutableList()
            for ((role, names) in listOf(FolderRole.TRASH to TRASH_NAMES, FolderRole.SPAM to SPAM_NAMES)) {
                if (assigned.any { it.role == role }) continue
                val idx = assigned.indexOfFirst { it.role == FolderRole.OTHER && it.display.substringAfterLast(it.delimiter ?: "/").lowercase() in names }
                if (idx >= 0) {
                    val f = assigned[idx]
                    assigned[idx] = MailFolder(f.raw, f.display, f.delimiter, f.flags, role)
                }
            }
            return assigned
        }
    }
}
