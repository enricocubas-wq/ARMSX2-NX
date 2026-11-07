package app.faxina.mac

import app.faxina.core.CancelToken
import app.faxina.core.removeFromTree
import app.faxina.core.Deleter
import app.faxina.core.FileEntry
import app.faxina.core.Risk
import app.faxina.core.ScanProgress
import app.faxina.core.mac.MacApp
import app.faxina.core.mac.MacApps
import app.faxina.core.mac.MacGroup
import app.faxina.core.mac.MacGroupId
import app.faxina.core.mac.MacScanOptions
import app.faxina.core.mac.MacScanResult
import app.faxina.core.mac.MacScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Tab(val label: String) { HOME("Início"), CLEAN("Limpeza"), FOLDERS("Pastas"), APPS("Apps"), MAIL("E-mail") }

class ProgressUi(val files: Long = 0, val bytes: Long = 0, val dir: String = "")

sealed interface ScanUi {
    data object Idle : ScanUi
    data object Running : ScanUi
    data object Done : ScanUi
}

sealed interface DupUi {
    data object Idle : DupUi
    data class Running(val percent: Int) : DupUi
    data object Done : DupUi
}

class Busy(val message: String, val done: Int, val total: Int)

/** Resultado de uma limpeza, mostrado numa janela de resumo. */
class CleanReport(
    val freed: Long,
    /** Bytes que foram para a Lixeira e só voltam a ficar livres quando ela for esvaziada. */
    val inTrash: Long,
    val failed: Int,
    val trashEmptied: Boolean = false,
    val trashEmptyFailed: Boolean = false,
)

class SelStats(val totalBytes: Long, val totalCount: Int, val foundBytes: Long, val perGroup: Map<MacGroupId, Pair<Int, Long>>) {
    fun count(id: MacGroupId) = perGroup[id]?.first ?: 0
    fun bytes(id: MacGroupId) = perGroup[id]?.second ?: 0L
}

sealed interface AppsUi {
    data object Loading : AppsUi
    data class Ready(val items: List<MacApp>) : AppsUi
}

private class Planned(val entry: FileEntry, val permanent: Boolean)

class MacController(private val scope: CoroutineScope) {
    val home: File = Ops.home
    private val settings = Settings("app")

    // ------------------------------------------------------------ navegação
    private val _tab = MutableStateFlow(Tab.HOME)
    val tab: StateFlow<Tab> = _tab.asStateFlow()
    fun go(t: Tab) {
        _tab.value = t
        if (t == Tab.APPS && _apps.value is AppsUi.Loading) loadApps()
    }

    private val _active = MutableStateFlow<MacGroupId?>(null)
    val activeGroup: StateFlow<MacGroupId?> = _active.asStateFlow()
    fun setActive(id: MacGroupId?) { _active.value = id }
    fun openGroup(id: MacGroupId) {
        _active.value = id
        _tab.value = Tab.CLEAN
    }

    private val _folder = MutableStateFlow(home.path)
    val folder: StateFlow<String> = _folder.asStateFlow()
    fun openFolder(path: String) { _folder.value = path }
    fun folderUp() {
        val cur = _folder.value
        if (cur != home.path) _folder.value = File(cur).parent?.takeIf { it.startsWith(home.path) } ?: home.path
    }

    // ------------------------------------------------------------ disco e permissões
    private val _usage = MutableStateFlow(Ops.diskUsage())
    val usage: StateFlow<DiskUsage> = _usage.asStateFlow()
    fun refreshUsage() { _usage.value = Ops.diskUsage() }

    private val _fda = MutableStateFlow(Ops.hasFullDiskAccess())
    /** true = tem Acesso Total ao Disco; false = não tem; null = não dá para saber. */
    val fda: StateFlow<Boolean?> = _fda.asStateFlow()

    private val _lifetime = MutableStateFlow(settings.getLong("freed_total"))
    val lifetimeFreed: StateFlow<Long> = _lifetime.asStateFlow()

    private val _useTrash = MutableStateFlow(settings.get("use_trash") != "false")
    /** Itens "para revisar" vão para a Lixeira (dá para desfazer) em vez de serem apagados na hora. */
    val useTrash: StateFlow<Boolean> = _useTrash.asStateFlow()
    fun setUseTrash(on: Boolean) {
        _useTrash.value = on
        settings.put("use_trash", on.toString())
    }

    // ------------------------------------------------------------ varredura
    private val _scan = MutableStateFlow<ScanUi>(ScanUi.Idle)
    val scan: StateFlow<ScanUi> = _scan.asStateFlow()
    private val _progress = MutableStateFlow(ProgressUi())
    val progress: StateFlow<ProgressUi> = _progress.asStateFlow()
    private val _dup = MutableStateFlow<DupUi>(DupUi.Idle)
    val dup: StateFlow<DupUi> = _dup.asStateFlow()

    private val _result = MutableStateFlow<MacScanResult?>(null)
    val result: StateFlow<MacScanResult?> = _result.asStateFlow()
    private val _groups = MutableStateFlow<List<MacGroup>>(emptyList())
    val groups: StateFlow<List<MacGroup>> = _groups.asStateFlow()
    private val _treeVersion = MutableStateFlow(0)
    val treeVersion: StateFlow<Int> = _treeVersion.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun dismissToast() { _toast.value = null }

    private var scanJob: Job? = null
    private var dupJob: Job? = null
    private var scanToken: CancelToken? = null
    private var dupToken: CancelToken? = null

    fun startScan() {
        if (scanJob?.isActive == true) return
        dupToken?.cancel()
        dupJob?.cancel()
        _dup.value = DupUi.Idle
        val token = CancelToken().also { scanToken = it }
        _scan.value = ScanUi.Running
        _progress.value = ProgressUi()
        scanJob = scope.launch {
            val progress = ScanProgress()
            val ticker = launch {
                while (isActive) {
                    _progress.value = ProgressUi(progress.files.sum(), progress.bytes.sum(), progress.currentDir)
                    delay(120)
                }
            }
            try {
                val res = withContext(Dispatchers.IO) { MacScanner(MacScanOptions(home), progress).scan(token) }
                if (token.cancelled) {
                    _scan.value = ScanUi.Idle
                    return@launch
                }
                _result.value = res
                _groups.value = res.groups.values.toList()
                _selected.value = defaultSelection(_groups.value)
                _treeVersion.update { it + 1 }
                _progress.value = ProgressUi(res.totalFiles, res.homeBytes, "")
                _scan.value = ScanUi.Done
                if (_active.value == null) _active.value = _groups.value.maxByOrNull { it.bytes }?.id
                refreshUsage()
                startDuplicates(res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scan.value = ScanUi.Idle
                _toast.value = "Não consegui analisar o Mac: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                ticker.cancel()
            }
        }
    }

    fun rescan() {
        if (_scan.value == ScanUi.Running) return
        _scan.value = ScanUi.Idle
        startScan()
    }

    private fun startDuplicates(res: MacScanResult) {
        if (res.dupCandidates.isEmpty()) {
            _dup.value = DupUi.Done
            return
        }
        val token = CancelToken().also { dupToken = it }
        _dup.value = DupUi.Running(0)
        val toHash = res.dupCandidates.entries.sumOf { (size, list) -> size * list.size }.coerceAtLeast(1)
        dupJob = scope.launch {
            val progress = ScanProgress()
            val ticker = launch {
                while (isActive) {
                    _dup.value = DupUi.Running((progress.hashedBytes.get() * 100 / toHash).toInt().coerceIn(0, 99))
                    delay(250)
                }
            }
            try {
                val group = withContext(Dispatchers.IO) { MacScanner.findDuplicates(res, progress, token) }
                if (token.cancelled) return@launch
                if (group.items.isNotEmpty()) {
                    _groups.update { it.filter { g -> g.id != MacGroupId.DUPLICATES } + group }
                    _selected.update { it + group.items.filter { e -> !e.keep }.map { e -> e.path } }
                }
                _dup.value = DupUi.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _dup.value = DupUi.Done
            } finally {
                ticker.cancel()
            }
        }
    }

    private fun defaultSelection(groups: List<MacGroup>): Set<String> {
        val out = HashSet<String>()
        // A Lixeira fica de fora: o que está lá pode ser algo que a pessoa ainda quer recuperar.
        for (g in groups) if (g.risk == Risk.SAFE && g.id != MacGroupId.TRASH) g.items.forEach { out.add(it.path) }
        return out
    }

    // ------------------------------------------------------------ seleção
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    val selStats: StateFlow<SelStats> = combine(_groups, _selected) { gs, sel ->
        val per = HashMap<MacGroupId, Pair<Int, Long>>()
        val seen = HashSet<String>()
        val seenAll = HashSet<String>()
        var total = 0L
        var totalCount = 0
        var found = 0L
        for (g in gs) {
            var c = 0
            var b = 0L
            for (e in g.items) {
                if (e.keep) continue
                if (seenAll.add(e.path)) found += e.size
                if (e.path in sel) {
                    c++
                    b += e.size
                    if (seen.add(e.path)) { total += e.size; totalCount++ }
                }
            }
            per[g.id] = c to b
        }
        SelStats(total, totalCount, found, per)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, SelStats(0, 0, 0, emptyMap()))

    fun toggle(path: String) = _selected.update { if (path in it) it - path else it + path }

    fun setGroup(id: MacGroupId, on: Boolean) {
        val g = _groups.value.firstOrNull { it.id == id } ?: return
        val paths = g.items.filter { !it.keep }.map { it.path }
        _selected.update { if (on) it + paths else it - paths.toSet() }
    }

    fun selectSafeOnly() { _selected.value = defaultSelection(_groups.value) }

    // ------------------------------------------------------------ limpeza
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()
    private val _report = MutableStateFlow<CleanReport?>(null)
    val report: StateFlow<CleanReport?> = _report.asStateFlow()
    fun dismissReport() { _report.value = null }

    private fun plan(sel: Set<String>, only: MacGroupId?): List<Planned> {
        val byPath = LinkedHashMap<String, Planned>()
        for (g in _groups.value) {
            if (only != null && g.id != only) continue
            for (e in g.items) {
                if (e.keep || e.path !in sel) continue
                val permanent = g.risk == Risk.SAFE || !_useTrash.value
                val old = byPath[e.path]
                if (old == null || (permanent && !old.permanent)) byPath[e.path] = Planned(e, permanent)
            }
        }
        return byPath.values.toList()
    }

    /** (quantidade, bytes apagados na hora, bytes que vão para a Lixeira) do que está marcado. */
    fun planSummary(only: MacGroupId? = null): Triple<Int, Long, Long> {
        val p = plan(_selected.value, only)
        return Triple(p.size, p.filter { it.permanent }.sumOf { it.entry.size }, p.filter { !it.permanent }.sumOf { it.entry.size })
    }

    fun planSummaryFor(entries: List<FileEntry>): Triple<Int, Long, Long> {
        val permanent = !_useTrash.value
        val bytes = entries.sumOf { it.size }
        return Triple(entries.size, if (permanent) bytes else 0L, if (permanent) 0L else bytes)
    }

    /** Limpa o que está marcado (todas as gavetas, ou só a [only]). */
    fun cleanSelected(only: MacGroupId? = null) {
        runPlan(plan(_selected.value, only))
    }

    /** Remove itens escolhidos no explorador de pastas. */
    fun cleanEntries(entries: List<FileEntry>) {
        runPlan(entries.map { Planned(it, permanent = !_useTrash.value) })
    }

    private fun runPlan(planned: List<Planned>) {
        if (planned.isEmpty() || _busy.value != null) return
        // se uma pasta e algo dentro dela foram marcados, só a pasta precisa sair
        val dirs = planned.filter { it.entry.isDir }.map { it.entry.path + "/" }
        val effective = planned.filter { p -> dirs.none { d -> p.entry.path.startsWith(d) } }
        _busy.value = Busy("Limpando…", 0, effective.size)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { execute(effective) }
            applyRemoval(effective, outcome.gone)
            _report.value = CleanReport(outcome.freed, outcome.inTrash, outcome.failed, trashEmptyFailed = outcome.trashEmptyFailed)
            addLifetime(outcome.freed)
            refreshUsage()
            _busy.value = null
        }
    }

    private class Outcome(val gone: Set<String>, val freed: Long, val inTrash: Long, val failed: Int, val trashEmptyFailed: Boolean)

    private fun execute(planned: List<Planned>): Outcome {
        val gone = HashSet<String>()
        var freed = 0L
        var inTrash = 0L
        val trashPrefix = home.path + "/.Trash/"
        val failedTrashItems = ArrayList<Planned>()
        var done = 0
        for (p in planned) {
            val e = p.entry
            val ok = if (p.permanent) {
                val r = Deleter.delete(listOf(e))
                freed += r.freedBytes
                r.deleted.isNotEmpty()
            } else {
                Ops.moveToTrash(File(e.path)).also { moved -> if (moved) inTrash += e.size }
            }
            if (ok) {
                gone.add(e.path)
            } else if (e.path.startsWith(trashPrefix)) {
                failedTrashItems.add(p)
            }
            _busy.value = Busy("Limpando…", ++done, planned.size)
        }
        var emptyFailed = false
        if (failedTrashItems.isNotEmpty()) {
            // sem "Acesso Total ao Disco" o app não mexe na Lixeira direto: pede ao Finder
            val trashGroup = _groups.value.firstOrNull { it.id == MacGroupId.TRASH }
            val covered = trashGroup == null || trashGroup.items.all { it.path in gone || failedTrashItems.any { f -> f.entry.path == it.path } }
            if (covered && Ops.emptyTrash()) {
                for (f in failedTrashItems) { gone.add(f.entry.path); freed += f.entry.size }
                failedTrashItems.clear()
            } else {
                emptyFailed = true
            }
        }
        return Outcome(gone, freed, inTrash, planned.size - gone.size, emptyFailed)
    }

    private fun applyRemoval(requested: List<Planned>, gone: Set<String>) {
        val res = _result.value
        if (res != null) for (p in requested) if (p.entry.path in gone) removeFromTree(listOf(res.tree), p.entry.path, p.entry.size)
        val under = requested.filter { it.entry.isDir && it.entry.path in gone }.map { it.entry.path + "/" }
        _groups.value = _groups.value.mapNotNull { g ->
            var items = g.items.filter { it.path !in gone && under.none { d -> it.path.startsWith(d) } }
            if (g.id == MacGroupId.DUPLICATES) {
                val live = items.groupBy { it.setId }.filter { (_, v) -> v.size >= 2 }.keys
                items = items.filter { it.setId in live }
            }
            if (items.none { !it.keep }) null else MacGroup(g.id, items)
        }
        _selected.update { sel -> sel.filter { it !in gone && under.none { d -> it.startsWith(d) } }.toSet() }
        _treeVersion.update { it + 1 }
        val active = _active.value
        if (active != null && _groups.value.none { it.id == active }) _active.value = _groups.value.maxByOrNull { it.bytes }?.id
    }

    private fun addLifetime(bytes: Long) {
        if (bytes <= 0) return
        _lifetime.value += bytes
        settings.put("freed_total", _lifetime.value.toString())
    }

    /** Depois de mandar coisas para a Lixeira: esvazia de verdade para o espaço voltar. */
    fun emptyTrashNow() {
        val r = _report.value ?: return
        if (_busy.value != null) return
        _busy.value = Busy("Esvaziando a Lixeira…", 0, 0)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Ops.emptyTrash() }
            if (ok) {
                addLifetime(r.inTrash)
                _report.value = CleanReport(r.freed + r.inTrash, 0, r.failed, trashEmptied = true)
                _groups.update { it.filter { g -> g.id != MacGroupId.TRASH } }
            } else {
                _report.value = CleanReport(r.freed, r.inTrash, r.failed, trashEmptyFailed = true)
            }
            refreshUsage()
            _busy.value = null
        }
    }

    // ------------------------------------------------------------ apps
    private val _apps = MutableStateFlow<AppsUi>(AppsUi.Loading)
    val apps: StateFlow<AppsUi> = _apps.asStateFlow()

    fun loadApps() {
        _apps.value = AppsUi.Loading
        scope.launch {
            val list = withContext(Dispatchers.IO) { MacApps.load(home) }
            _apps.value = AppsUi.Ready(list)
        }
    }

    suspend fun leftoversFor(app: MacApp): List<FileEntry> = withContext(Dispatchers.IO) { MacApps.leftovers(home, app) }

    /** Manda o app e os restos escolhidos para a Lixeira. */
    fun uninstall(app: MacApp, leftovers: List<FileEntry>) {
        if (_busy.value != null) return
        val all = listOf(FileEntry(app.path, app.bytes, 0L, isDir = true)) + leftovers
        _busy.value = Busy("Movendo ${app.name} para a Lixeira…", 0, all.size)
        scope.launch {
            var moved = 0L
            var failed = 0
            val removed = HashSet<String>()
            withContext(Dispatchers.IO) {
                var done = 0
                for (e in all) {
                    if (Ops.moveToTrash(File(e.path))) { moved += e.size; removed.add(e.path) } else failed++
                    _busy.value = Busy("Movendo ${app.name} para a Lixeira…", ++done, all.size)
                }
            }
            if (app.path in removed) _apps.update { s -> if (s is AppsUi.Ready) AppsUi.Ready(s.items.filter { it.path != app.path }) else s }
            _report.value = CleanReport(0, moved, failed)
            refreshUsage()
            _busy.value = null
        }
    }

    fun childFilesOf(path: String): List<FileEntry> {
        val list = File(path).listFiles() ?: return emptyList()
        return list.filter { it.isFile }.map { FileEntry(it.path, it.length(), it.lastModified()) }.sortedByDescending { it.size }
    }

    // ------------------------------------------------------------ início
    init {
        startScan()
        scope.launch(Dispatchers.IO) {
            // Só faz sentido vigiar enquanto falta o acesso (depois de dado, o macOS pede para reabrir o app).
            while (isActive && _fda.value == false) {
                delay(2500)
                val now = Ops.hasFullDiskAccess()
                if (now != _fda.value) _fda.value = now
            }
        }
    }
}
