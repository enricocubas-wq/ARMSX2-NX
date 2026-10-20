package app.faxina.core.mail

import app.faxina.core.CancelToken
import java.util.PriorityQueue

object MailFlag {
    const val SEEN = 1
    const val FLAGGED = 2
    const val UNSUB = 4
    const val PROMO = 8
    const val SOCIAL = 16
    const val UPDATES = 32
    const val FORUMS = 64
}

enum class MailPreset { PROMOTIONS, SOCIAL, NEWSLETTERS, BIG, OLD_READ }

class SenderId(val address: String, val name: String)

class BigMessage(val uid: Long, val size: Long, val from: String, val subject: String, val epochDay: Int)

class SenderStat(
    val index: Int,
    val address: String,
    val name: String,
    val count: Int,
    val bytes: Long,
    val oldestDay: Int,
    val newestDay: Int,
    val hasUnsubscribe: Boolean,
) {
    val label: String get() = name.ifBlank { address }
}

/** Uma linha por mensagem, em vetores de primitivos (100 mil e-mails ocupam poucos MB). */
class MailIndex(
    val uids: LongArray,
    val sizes: IntArray,
    val days: IntArray,
    val senders: IntArray,
    val flags: IntArray,
    val senderTable: List<SenderId>,
) {
    val size: Int get() = uids.size

    fun without(removed: Set<Long>): MailIndex {
        val keep = (0 until size).filter { uids[it] !in removed }
        return MailIndex(
            LongArray(keep.size) { uids[keep[it]] },
            IntArray(keep.size) { sizes[keep[it]] },
            IntArray(keep.size) { days[keep[it]] },
            IntArray(keep.size) { senders[keep[it]] },
            IntArray(keep.size) { flags[keep[it]] },
            senderTable,
        )
    }

    fun position(uid: Long): Int {
        val p = java.util.Arrays.binarySearch(uids, uid)
        return if (p >= 0) p else -1
    }
}

internal class MailIndexBuilder {
    private var uids = LongArray(1024)
    private var sizes = IntArray(1024)
    private var days = IntArray(1024)
    private var senders = IntArray(1024)
    private var flags = IntArray(1024)
    private var n = 0
    private val table = ArrayList<SenderId>()
    private val lookup = HashMap<String, Int>()

    fun add(uid: Long, size: Long, day: Int, address: String, name: String, flag: Int) {
        if (n == uids.size) {
            val cap = n * 2
            uids = uids.copyOf(cap); sizes = sizes.copyOf(cap); days = days.copyOf(cap)
            senders = senders.copyOf(cap); flags = flags.copyOf(cap)
        }
        var idx = lookup[address]
        if (idx == null) {
            idx = table.size
            table.add(SenderId(address, name))
            lookup[address] = idx
        }
        uids[n] = uid
        sizes[n] = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        days[n] = day
        senders[n] = idx
        flags[n] = flag
        n++
    }

    fun build() = MailIndex(uids.copyOf(n), sizes.copyOf(n), days.copyOf(n), senders.copyOf(n), flags.copyOf(n), table)
}

class MailReport(
    val account: String,
    val isGmail: Boolean,
    val scanFolder: MailFolder,
    val trash: MailFolder?,
    val spam: MailFolder?,
    val index: MailIndex,
    val trashCount: Int,
    val trashBytes: Long,
    val spamCount: Int,
    val spamBytes: Long,
    val big: List<BigMessage>,
    /** "Hoje" em dias desde 1970; separado para os testes serem determinísticos. */
    val todayDay: Int,
) {
    val totalCount: Int get() = index.size
    val totalBytes: Long by lazy { var s = 0L; for (x in index.sizes) s += x; s }

    val senders: List<SenderStat> by lazy { computeSenders() }

    private fun computeSenders(): List<SenderStat> {
        val k = index.senderTable.size
        val count = IntArray(k)
        val bytes = LongArray(k)
        val oldest = IntArray(k) { Int.MAX_VALUE }
        val newest = IntArray(k)
        val unsub = BooleanArray(k)
        for (i in 0 until index.size) {
            val s = index.senders[i]
            count[s]++
            bytes[s] += index.sizes[i]
            val d = index.days[i]
            if (d in 1 until oldest[s]) oldest[s] = d
            if (d > newest[s]) newest[s] = d
            if (index.flags[i] and MailFlag.UNSUB != 0) unsub[s] = true
        }
        return (0 until k).filter { count[it] > 0 }.map {
            val id = index.senderTable[it]
            SenderStat(it, id.address, id.name, count[it], bytes[it], if (oldest[it] == Int.MAX_VALUE) 0 else oldest[it], newest[it], unsub[it])
        }.sortedByDescending { it.bytes }
    }

    private fun matches(i: Int, preset: MailPreset): Boolean {
        val f = index.flags[i]
        if (f and MailFlag.FLAGGED != 0) return false // com estrela nunca entra em limpeza em massa
        return when (preset) {
            MailPreset.PROMOTIONS -> f and MailFlag.PROMO != 0
            MailPreset.SOCIAL -> f and MailFlag.SOCIAL != 0
            MailPreset.NEWSLETTERS -> f and MailFlag.UNSUB != 0
            MailPreset.BIG -> index.sizes[i] >= BIG_BYTES
            MailPreset.OLD_READ -> f and MailFlag.SEEN != 0 && index.days[i] in 1 until (todayDay - OLD_DAYS)
        }
    }

    fun uidsFor(preset: MailPreset): LongArray {
        val out = ArrayList<Long>()
        for (i in 0 until index.size) if (matches(i, preset)) out.add(index.uids[i])
        return out.toLongArray()
    }

    fun bytesFor(preset: MailPreset): Long {
        var b = 0L
        for (i in 0 until index.size) if (matches(i, preset)) b += index.sizes[i]
        return b
    }

    fun countFor(preset: MailPreset): Int {
        var c = 0
        for (i in 0 until index.size) if (matches(i, preset)) c++
        return c
    }

    fun uidsForSenders(senderIdx: Set<Int>): LongArray {
        val out = ArrayList<Long>()
        for (i in 0 until index.size) {
            if (index.senders[i] in senderIdx && index.flags[i] and MailFlag.FLAGGED == 0) out.add(index.uids[i])
        }
        return out.toLongArray()
    }

    fun bytesOf(uids: Collection<Long>): Long {
        var b = 0L
        for (u in uids) { val p = index.position(u); if (p >= 0) b += index.sizes[p] }
        return b
    }

    fun afterDeleting(uids: Collection<Long>, movedToTrash: Boolean): MailReport {
        val removed = uids.toHashSet()
        val bytes = bytesOf(removed)
        return MailReport(
            account, isGmail, scanFolder, trash, spam, index.without(removed),
            trashCount + if (movedToTrash) removed.size else 0,
            trashBytes + if (movedToTrash) bytes else 0,
            spamCount, spamBytes,
            big.filter { it.uid !in removed }, todayDay,
        )
    }

    fun afterEmptying(): MailReport = MailReport(account, isGmail, scanFolder, trash, spam, index, 0, 0, 0, 0, big, todayDay)

    companion object {
        const val BIG_BYTES = 5 * 1000 * 1000
        const val OLD_DAYS = 365 * 2
    }
}

/** Varre a caixa de e-mail lendo só cabeçalhos: rápido e sem baixar anexos. */
object MailScanner {
    private const val BATCH = 500
    const val DEFAULT_MAX_MESSAGES = 150_000

    fun scan(
        client: ImapClient,
        account: String,
        cancel: CancelToken = CancelToken(),
        maxMessages: Int = DEFAULT_MAX_MESSAGES,
        todayDay: Int = (System.currentTimeMillis() / 86_400_000L).toInt(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MailReport {
        val folders = client.listFolders()
        val scanFolder = folders.firstOrNull { it.role == FolderRole.ALL }
            ?: folders.firstOrNull { it.role == FolderRole.INBOX }
            ?: throw ImapException("Não encontrei a caixa de entrada dessa conta", ImapException.Kind.PROTOCOL)
        val trash = folders.firstOrNull { it.role == FolderRole.TRASH }
        val spam = folders.firstOrNull { it.role == FolderRole.SPAM }

        val info = client.select(scanFolder, readOnly = true)
        val first = maxOf(1, info.exists - maxMessages + 1)
        val total = info.exists - first + 1
        val builder = MailIndexBuilder()
        val big = PriorityQueue<BigMessage>(compareBy { it.size })
        var done = 0
        var start = first
        while (start <= info.exists && total > 0) {
            if (cancel.cancelled) break
            val end = minOf(info.exists, start + BATCH - 1)
            client.fetchHeaders(start, end) { h ->
                val (name, addr) = HeaderBlock.parseAddress(h.from)
                val sender = addr.ifBlank { "(desconhecido)" }
                var f = 0
                if (h.seen) f = f or MailFlag.SEEN
                if (h.flagged) f = f or MailFlag.FLAGGED
                if (h.hasUnsubscribe) f = f or MailFlag.UNSUB
                builder.add(h.uid, h.size, h.epochDay, sender, name, f)
                if (h.size >= 2_000_000) {
                    big.add(BigMessage(h.uid, h.size, name.ifBlank { sender }, h.subject, h.epochDay))
                    if (big.size > 300) big.poll()
                }
            }
            done = end - first + 1
            onProgress(done, total)
            start = end + 1
        }
        var index = builder.build()
        if (cancel.cancelled) throw ImapException("Análise cancelada", ImapException.Kind.PROTOCOL)

        if (client.isGmail) index = markGmailCategories(client, index)

        // Lixeira e spam: só tamanhos
        var trashCount = 0; var trashBytes = 0L; var spamCount = 0; var spamBytes = 0L
        if (trash != null) {
            val ti = client.select(trash, readOnly = true)
            val r = client.sumSizes(ti.exists); trashCount = r.first; trashBytes = r.second
        }
        if (spam != null) {
            val si = client.select(spam, readOnly = true)
            val r = client.sumSizes(si.exists); spamCount = r.first; spamBytes = r.second
        }
        return MailReport(
            account, client.isGmail, scanFolder, trash, spam, index,
            trashCount, trashBytes, spamCount, spamBytes,
            big.sortedByDescending { it.size }, todayDay,
        )
    }

    private fun markGmailCategories(client: ImapClient, index: MailIndex): MailIndex {
        val flags = index.flags.copyOf()
        for ((query, bit) in listOf(
            "category:promotions" to MailFlag.PROMO,
            "category:social" to MailFlag.SOCIAL,
            "category:updates" to MailFlag.UPDATES,
            "category:forums" to MailFlag.FORUMS,
        )) {
            val uids = try { client.gmailSearch(query) } catch (_: ImapException) { continue }
            for (u in uids) {
                val p = index.position(u)
                if (p >= 0) flags[p] = flags[p] or bit
            }
        }
        return MailIndex(index.uids, index.sizes, index.days, index.senders, flags, index.senderTable)
    }
}

/** Ações destrutivas. Cada uma usa uma conexão nova e já autenticada. */
object MailActions {
    /** Move para a lixeira (ou apaga direto se a conta não tem lixeira). Devolve true se foi para a lixeira. */
    fun delete(client: ImapClient, report: MailReport, uids: LongArray, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Boolean {
        if (uids.isEmpty()) return report.trash != null
        client.select(report.scanFolder, readOnly = false)
        val chunks = uids.toList().chunked(500)
        var done = 0
        for (c in chunks) {
            val arr = c.toLongArray()
            if (report.trash != null) client.moveToFolder(arr, report.trash) else client.deleteForever(arr)
            done += c.size
            onProgress(done, uids.size)
        }
        return report.trash != null
    }

    /** Esvazia lixeira e spam (apaga de vez). */
    fun emptyTrashAndSpam(client: ImapClient, report: MailReport) {
        for (f in listOfNotNull(report.trash, report.spam)) {
            val info = client.select(f, readOnly = false)
            client.emptySelectedFolder(info.exists)
        }
    }
}
