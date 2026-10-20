package app.faxina.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.faxina.core.CancelToken
import app.faxina.core.mail.ImapClient
import app.faxina.core.mail.ImapConfig
import app.faxina.core.mail.ImapException
import app.faxina.core.mail.MailActions
import app.faxina.core.mail.MailFlag
import app.faxina.core.mail.MailPreset
import app.faxina.core.mail.MailReport
import app.faxina.core.mail.MailScanner
import app.faxina.core.mail.Security
import app.faxina.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Provider(val label: String, val host: String, val port: Int, val helpUrl: String?) {
    GMAIL("Gmail", "imap.gmail.com", 993, "https://myaccount.google.com/apppasswords"),
    OUTLOOK("Outlook / Hotmail", "outlook.office365.com", 993, "https://account.live.com/proofs/AppPassword"),
    YAHOO("Yahoo", "imap.mail.yahoo.com", 993, "https://login.yahoo.com/account/security/app-passwords"),
    ICLOUD("iCloud", "imap.mail.me.com", 993, "https://appleid.apple.com/account/manage"),
    UOL("UOL", "imap.uol.com.br", 993, null),
    OTHER("Outro", "", 993, null);

    companion object {
        fun detect(email: String): Provider {
            val d = email.substringAfter('@', "").lowercase()
            return when {
                d == "gmail.com" || d == "googlemail.com" -> GMAIL
                d in setOf("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.com.br", "hotmail.com.br") -> OUTLOOK
                d.startsWith("yahoo.") || d == "ymail.com" -> YAHOO
                d in setOf("icloud.com", "me.com", "mac.com") -> ICLOUD
                d == "uol.com.br" || d == "bol.com.br" -> UOL
                else -> OTHER
            }
        }
    }
}

class MailAccount(val email: String, val provider: Provider, val host: String, val port: Int, val security: Security)

sealed interface MailUi {
    data object NoAccount : MailUi
    data class Working(val message: String, val done: Int = 0, val total: Int = 0) : MailUi
    data class Ready(val report: MailReport) : MailUi
    data class Failed(val message: String, val authProblem: Boolean) : MailUi
}

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = ctx.getSharedPreferences("faxina_mail", Context.MODE_PRIVATE)
    private val secure = SecureStore(ctx)

    private val _ui = MutableStateFlow<MailUi>(MailUi.NoAccount)
    val ui: StateFlow<MailUi> = _ui.asStateFlow()

    private val _account = MutableStateFlow<MailAccount?>(loadAccount())
    val account: StateFlow<MailAccount?> = _account.asStateFlow()

    /** Mensagem de resultado da última limpeza (para o banner da tela). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _presets = MutableStateFlow<Set<MailPreset>>(emptySet())
    val presets: StateFlow<Set<MailPreset>> = _presets.asStateFlow()
    private val _senders = MutableStateFlow<Set<Int>>(emptySet())
    val senders: StateFlow<Set<Int>> = _senders.asStateFlow()
    private val _bigPicked = MutableStateFlow<Set<Long>>(emptySet())
    val bigPicked: StateFlow<Set<Long>> = _bigPicked.asStateFlow()

    private var token: CancelToken? = null

    private var started = false

    /** Chamado ao abrir a tela: se já existe conta salva, analisa sem pedir nada de novo. */
    fun onOpen() {
        if (started) return
        started = true
        if (_account.value != null && secure.get(KEY_PASS) != null) scan()
    }

    private fun loadAccount(): MailAccount? {
        val email = prefs.getString("email", null) ?: return null
        val provider = runCatching { Provider.valueOf(prefs.getString("provider", "OTHER")!!) }.getOrDefault(Provider.OTHER)
        val host = prefs.getString("host", "") ?: ""
        val port = prefs.getInt("port", 993)
        val sec = runCatching { Security.valueOf(prefs.getString("security", "SSL")!!) }.getOrDefault(Security.SSL)
        return MailAccount(email, provider, host, port, sec)
    }

    fun connect(email: String, password: String, provider: Provider, customHost: String, customPort: Int) {
        val cleanEmail = email.trim()
        val cleanPass = password.filter { !it.isWhitespace() } // senhas de app do Google vêm com espaços
        val host = if (provider == Provider.OTHER) customHost.trim() else provider.host
        val port = if (provider == Provider.OTHER) customPort else provider.port
        if (!cleanEmail.contains('@') || cleanPass.isEmpty() || host.isEmpty()) {
            _ui.value = MailUi.Failed("Preencha o e-mail, a senha de app e (se for \"Outro\") o servidor IMAP.", false)
            return
        }
        val acc = MailAccount(cleanEmail, provider, host, port, Security.SSL)
        prefs.edit()
            .putString("email", acc.email).putString("provider", provider.name)
            .putString("host", host).putInt("port", port).putString("security", acc.security.name)
            .apply()
        try {
            secure.put(KEY_PASS, cleanPass)
        } catch (e: Exception) {
            _ui.value = MailUi.Failed("Este celular não conseguiu guardar a senha com segurança (${e.message ?: e.javaClass.simpleName}).", false)
            return
        }
        _account.value = acc
        scan()
    }

    fun scan() {
        val acc = _account.value ?: return
        val pass = secure.get(KEY_PASS) ?: run { _ui.value = MailUi.NoAccount; return }
        val tk = CancelToken().also { token?.cancel(); token = it }
        _notice.value = null
        _ui.value = MailUi.Working("Conectando em ${acc.host}…")
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    ImapClient(ImapConfig(acc.host, acc.port, acc.email, pass, acc.security)).use { c ->
                        c.connect()
                        _ui.value = MailUi.Working("Entrando na conta…")
                        c.login()
                        _ui.value = MailUi.Working("Lendo cabeçalhos…")
                        MailScanner.scan(c, acc.email, tk) { done, total ->
                            _ui.value = MailUi.Working("Analisando e-mails", done, total)
                        }
                    }
                }
                _presets.value = recommended(report)
                _senders.value = emptySet()
                _bigPicked.value = emptySet()
                _ui.value = MailUi.Ready(report)
            } catch (e: ImapException) {
                _ui.value = MailUi.Failed(friendly(e, acc), e.kind == ImapException.Kind.AUTH)
            } catch (e: Exception) {
                _ui.value = MailUi.Failed("Algo deu errado: ${e.message ?: e.javaClass.simpleName}", false)
            }
        }
    }

    private fun friendly(e: ImapException, acc: MailAccount): String = when (e.kind) {
        ImapException.Kind.AUTH -> when (acc.provider) {
            Provider.GMAIL -> "O Gmail recusou a senha. Ele não aceita a senha normal: é preciso criar uma \"senha de app\" (16 letras) na sua conta Google, com a verificação em duas etapas ligada."
            else -> "O servidor recusou o login. Confira o e-mail e use uma senha de app, não a senha normal."
        } + (e.message?.takeIf { it.isNotBlank() }?.let { "\n\nServidor: $it" } ?: "")
        ImapException.Kind.NETWORK -> "Sem conexão com o servidor de e-mail. ${e.message ?: ""}".trim()
        else -> e.message ?: "Erro ao falar com o servidor de e-mail."
    }

    private fun recommended(r: MailReport): Set<MailPreset> {
        val out = LinkedHashSet<MailPreset>()
        if (r.isGmail) {
            if (r.countFor(MailPreset.PROMOTIONS) > 0) out.add(MailPreset.PROMOTIONS)
            if (r.countFor(MailPreset.SOCIAL) > 0) out.add(MailPreset.SOCIAL)
        }
        if (r.countFor(MailPreset.NEWSLETTERS) > 0) out.add(MailPreset.NEWSLETTERS)
        return out
    }

    // ------------------------------------------------------------ seleção
    fun togglePreset(p: MailPreset) = _presets.update { if (p in it) it - p else it + p }
    fun toggleSender(i: Int) = _senders.update { if (i in it) it - i else it + i }
    fun toggleBig(uid: Long) = _bigPicked.update { if (uid in it) it - uid else it + uid }

    fun selectedUids(report: MailReport): Set<Long> {
        val out = HashSet<Long>()
        for (p in _presets.value) report.uidsFor(p).forEach { out.add(it) }
        if (_senders.value.isNotEmpty()) report.uidsForSenders(_senders.value).forEach { out.add(it) }
        // mensagens grandes escolhidas à mão; estrela continua protegida
        for (u in _bigPicked.value) {
            val p = report.index.position(u)
            if (p >= 0 && report.index.flags[p] and MailFlag.FLAGGED == 0) out.add(u)
        }
        return out
    }

    // ------------------------------------------------------------ ações
    fun delete(report: MailReport, permanent: Boolean) {
        val acc = _account.value ?: return
        val pass = secure.get(KEY_PASS) ?: return
        val uids = selectedUids(report)
        if (uids.isEmpty() && !(permanent && (report.trashCount + report.spamCount) > 0)) return
        _ui.value = MailUi.Working("Apagando…", 0, uids.size.coerceAtLeast(1))
        viewModelScope.launch {
            try {
                var updated = report
                val freed = withContext(Dispatchers.IO) {
                    ImapClient(ImapConfig(acc.host, acc.port, acc.email, pass, acc.security)).use { c ->
                        c.connect(); c.login()
                        val bytes = report.bytesOf(uids)
                        val toTrash = MailActions.delete(c, report, uids.toLongArray()) { d, t ->
                            _ui.value = MailUi.Working("Apagando…", d, t)
                        }
                        updated = report.afterDeleting(uids, toTrash)
                        var total = if (toTrash) 0L else bytes
                        if (permanent) {
                            _ui.value = MailUi.Working("Esvaziando lixeira e spam…")
                            total = bytes + updated.trashBytes + updated.spamBytes
                            MailActions.emptyTrashAndSpam(c, updated)
                            updated = updated.afterEmptying()
                        }
                        total
                    }
                }
                _presets.value = emptySet(); _senders.value = emptySet(); _bigPicked.value = emptySet()
                _ui.value = MailUi.Ready(updated)
                _notice.value = if (permanent || freed > 0) {
                    "Pronto! ${uids.size} e-mails apagados — ${app.faxina.core.formatBytes(freed)} liberados."
                } else {
                    "${uids.size} e-mails foram para a lixeira. Esvazie a lixeira para liberar o espaço de vez."
                }
            } catch (e: ImapException) {
                _ui.value = MailUi.Failed(friendly(e, acc), e.kind == ImapException.Kind.AUTH)
            } catch (e: Exception) {
                _ui.value = MailUi.Failed("Algo deu errado: ${e.message ?: e.javaClass.simpleName}", false)
            }
        }
    }

    fun emptyTrash(report: MailReport) {
        val acc = _account.value ?: return
        val pass = secure.get(KEY_PASS) ?: return
        _ui.value = MailUi.Working("Esvaziando lixeira e spam…")
        viewModelScope.launch {
            try {
                val freed = report.trashBytes + report.spamBytes
                withContext(Dispatchers.IO) {
                    ImapClient(ImapConfig(acc.host, acc.port, acc.email, pass, acc.security)).use { c ->
                        c.connect(); c.login()
                        MailActions.emptyTrashAndSpam(c, report)
                    }
                }
                _ui.value = MailUi.Ready(report.afterEmptying())
                _notice.value = "Lixeira e spam esvaziados — ${app.faxina.core.formatBytes(freed)} liberados."
            } catch (e: ImapException) {
                _ui.value = MailUi.Failed(friendly(e, acc), e.kind == ImapException.Kind.AUTH)
            } catch (e: Exception) {
                _ui.value = MailUi.Failed("Algo deu errado: ${e.message ?: e.javaClass.simpleName}", false)
            }
        }
    }

    fun dismissNotice() { _notice.value = null }

    fun signOut() {
        token?.cancel()
        secure.remove(KEY_PASS)
        prefs.edit().clear().apply()
        _account.value = null
        _presets.value = emptySet(); _senders.value = emptySet(); _bigPicked.value = emptySet()
        _ui.value = MailUi.NoAccount
    }

    /** Volta ao formulário (ex.: senha errada) sem apagar o e-mail digitado. */
    fun editAccount() {
        _ui.value = MailUi.NoAccount
    }

    override fun onCleared() {
        token?.cancel()
    }

    private companion object {
        const val KEY_PASS = "mail_password"
    }
}
