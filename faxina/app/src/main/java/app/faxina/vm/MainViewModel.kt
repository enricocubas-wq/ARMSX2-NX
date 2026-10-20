package app.faxina.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.faxina.core.CancelToken
import app.faxina.core.CleanGroup
import app.faxina.core.DeleteResult
import app.faxina.core.Deleter
import app.faxina.core.DirNode
import app.faxina.core.Duplicates
import app.faxina.core.FileCategory
import app.faxina.core.FileEntry
import app.faxina.core.GroupId
import app.faxina.core.ScanOptions
import app.faxina.core.ScanProgress
import app.faxina.core.ScanResult
import app.faxina.core.Scanner
import app.faxina.data.AppItem
import app.faxina.data.AppsRepo
import app.faxina.data.ApkInspector
import app.faxina.data.MediaRefresh
import app.faxina.data.Perms
import app.faxina.data.StorageInfo
import app.faxina.data.StorageUsage
import kotlinx.coroutines.CancellationException
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

class ProgressUi(
    val files: Long = 0,
    val bytes: Long = 0,
    val dir: String = "",
    val phase: ScanProgress.Phase = ScanProgress.Phase.WALK,
)

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

sealed interface DeleteUi {
    data object Idle : DeleteUi
    data class Running(val done: Int, val total: Int) : DeleteUi
}

class SelStats(val totalBytes: Long, val totalCount: Int, val foundBytes: Long, val perGroup: Map<GroupId, Pair<Int, Long>>) {
    fun count(id: GroupId) = perGroup[id]?.first ?: 0
    fun bytes(id: GroupId) = perGroup[id]?.second ?: 0L
}

sealed interface AppsUi {
    data object Loading : AppsUi
    data class Ready(val items: List<AppItem>, val hasStats: Boolean) : AppsUi
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = ctx.getSharedPreferences("faxina", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ navegação
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    /** true quando a última navegação foi "para frente" (define a animação de transição). */
    var forward: Boolean = true
        private set

    fun go(s: Screen) {
        forward = true
        _stack.update { it + s }
    }

    fun replace(s: Screen) {
        forward = true
        _stack.update { it.dropLast(1) + s }
    }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        forward = false
        _stack.update { it.dropLast(1) }
        return true
    }

    fun home() {
        forward = false
        _stack.value = listOf(Screen.Home)
    }

    // ------------------------------------------------------------ permissões
    private val _storageGranted = MutableStateFlow(Perms.hasStorage(ctx))
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()
    private val _usageGranted = MutableStateFlow(Perms.hasUsageAccess(ctx))
    val usageGranted: StateFlow<Boolean> = _usageGranted.asStateFlow()

    fun refreshPermissions() {
        val s = Perms.hasStorage(ctx)
        val u = Perms.hasUsageAccess(ctx)
        _storageGranted.value = s
        if (u != _usageGranted.value) {
            _usageGranted.value = u
            loadAppsBytes()
        }
        refreshUsage()
        if (s && _scan.value == ScanUi.Idle) startScan()
    }

    // ------------------------------------------------------------ resumo do armazenamento
    private val _usage = MutableStateFlow(StorageInfo.usage())
    val usage: StateFlow<StorageUsage> = _usage.asStateFlow()
    private val _appsBytes = MutableStateFlow<Long?>(null)
    val appsBytes: StateFlow<Long?> = _appsBytes.asStateFlow()

    private val _lifetimeFreed = MutableStateFlow(prefs.getLong("freed_total", 0))
    val lifetimeFreed: StateFlow<Long> = _lifetimeFreed.asStateFlow()

    fun refreshUsage() {
        _usage.value = StorageInfo.usage()
    }

    private fun loadAppsBytes() {
        if (!_usageGranted.value) return
        viewModelScope.launch { _appsBytes.value = AppsRepo.totalAppsBytes(ctx) }
    }

    // ------------------------------------------------------------ varredura
    private val _scan = MutableStateFlow<ScanUi>(ScanUi.Idle)
    val scan: StateFlow<ScanUi> = _scan.asStateFlow()
    private val _progress = MutableStateFlow(ProgressUi())
    val progress: StateFlow<ProgressUi> = _progress.asStateFlow()
    private val _dup = MutableStateFlow<DupUi>(DupUi.Idle)
    val dup: StateFlow<DupUi> = _dup.asStateFlow()

    private val _bytesByCategory = MutableStateFlow<Map<FileCategory, Long>>(emptyMap())
    val bytesByCategory: StateFlow<Map<FileCategory, Long>> = _bytesByCategory.asStateFlow()

    private val _tree = MutableStateFlow<List<DirNode>>(emptyList())
    val tree: StateFlow<List<DirNode>> = _tree.asStateFlow()
    private val _treeVersion = MutableStateFlow(0)
    val treeVersion: StateFlow<Int> = _treeVersion.asStateFlow()

    private val _groups = MutableStateFlow<List<CleanGroup>>(emptyList())
    val groups: StateFlow<List<CleanGroup>> = _groups.asStateFlow()

    private var scanJob: Job? = null
    private var dupJob: Job? = null
    private var scanToken: CancelToken? = null
    private var dupToken: CancelToken? = null

    fun startScan() {
        if (scanJob?.isActive == true || !_storageGranted.value) return
        dupToken?.cancel()
        dupJob?.cancel()
        _dup.value = DupUi.Idle
        val token = CancelToken().also { scanToken = it }
        _scan.value = ScanUi.Running
        _progress.value = ProgressUi()
        scanJob = viewModelScope.launch {
            val progress = ScanProgress()
            val ticker = launch {
                while (isActive) {
                    _progress.value = ProgressUi(progress.files.sum(), progress.bytes.sum(), progress.currentDir, progress.phase)
                    delay(100)
                }
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    Scanner(ScanOptions(StorageInfo.scanRoots(ctx)), progress).scan(token)
                }
                if (token.cancelled) {
                    _scan.value = ScanUi.Idle
                    return@launch
                }
                val groups = withContext(Dispatchers.IO) { splitApks(result.groups.values.toList()) }
                _groups.value = groups
                _bytesByCategory.value = result.bytesByCategory
                _tree.value = result.tree
                _treeVersion.update { it + 1 }
                _selected.value = defaultSelection(groups)
                _progress.value = ProgressUi(result.totalFiles, result.totalBytes, "", ScanProgress.Phase.DONE)
                _scan.value = ScanUi.Done
                refreshUsage()
                startDuplicates(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scan.value = ScanUi.Idle
                _toast.value = "Não consegui analisar o armazenamento: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                ticker.cancel()
            }
        }
    }

    private fun startDuplicates(result: ScanResult) {
        if (result.dupCandidates.isEmpty()) {
            _dup.value = DupUi.Done
            return
        }
        val token = CancelToken().also { dupToken = it }
        _dup.value = DupUi.Running(0)
        val toHash = result.dupCandidates.entries.sumOf { (size, list) -> size * list.size }.coerceAtLeast(1)
        dupJob = viewModelScope.launch {
            val progress = ScanProgress()
            val ticker = launch {
                while (isActive) {
                    val pct = (progress.hashedBytes.get() * 100 / toHash).toInt().coerceIn(0, 99)
                    _dup.value = DupUi.Running(pct)
                    delay(200)
                }
            }
            try {
                val found = withContext(Dispatchers.IO) { Duplicates.find(result.dupCandidates, progress, token) }
                if (token.cancelled) return@launch
                val group = Duplicates.flatten(found)
                if (group.items.isNotEmpty()) {
                    _groups.update { it.filter { g -> g.id != GroupId.DUPLICATES } + group }
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

    /** APKs já instalados são "lixo seguro"; os demais ficam para revisão. */
    private fun splitApks(groups: List<CleanGroup>): List<CleanGroup> {
        val apks = groups.firstOrNull { it.id == GroupId.APK_OTHER } ?: return groups
        val pm = ctx.packageManager
        val done = ArrayList<FileEntry>()
        val other = ArrayList<FileEntry>()
        for (e in apks.items) {
            if (ApkInspector.isAlreadyInstalled(pm, e.path) == true) done.add(e) else other.add(e)
        }
        val rest = groups.filter { it.id != GroupId.APK_OTHER }.toMutableList()
        if (done.isNotEmpty()) rest.add(CleanGroup(GroupId.APK_DONE, done))
        if (other.isNotEmpty()) rest.add(CleanGroup(GroupId.APK_OTHER, other))
        return rest
    }

    private fun defaultSelection(groups: List<CleanGroup>): Set<String> {
        val out = HashSet<String>()
        for (g in groups) if (g.risk == app.faxina.core.Risk.SAFE) g.items.forEach { out.add(it.path) }
        return out
    }

    // ------------------------------------------------------------ seleção
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    val selStats: StateFlow<SelStats> = combine(_groups, _selected) { gs, sel ->
        val per = HashMap<GroupId, Pair<Int, Long>>()
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
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SelStats(0, 0, 0, emptyMap()))

    fun toggle(path: String) = _selected.update { if (path in it) it - path else it + path }

    fun setGroup(id: GroupId, on: Boolean) {
        val g = _groups.value.firstOrNull { it.id == id } ?: return
        val paths = g.items.filter { !it.keep }.map { it.path }
        _selected.update { if (on) it + paths else it - paths.toSet() }
    }

    fun setPaths(paths: Collection<String>, on: Boolean) {
        _selected.update { if (on) it + paths else it - paths.toSet() }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    // ------------------------------------------------------------ remoção
    private val _delete = MutableStateFlow<DeleteUi>(DeleteUi.Idle)
    val delete: StateFlow<DeleteUi> = _delete.asStateFlow()
    private val _lastFreed = MutableStateFlow(0L)
    val lastFreed: StateFlow<Long> = _lastFreed.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun dismissToast() { _toast.value = null }

    private val _lastFailed = MutableStateFlow(0)
    val lastFailed: StateFlow<Int> = _lastFailed.asStateFlow()

    /** Apaga tudo que está marcado na revisão. */
    fun deleteSelected() {
        val sel = _selected.value
        val seen = HashSet<String>()
        val entries = ArrayList<FileEntry>()
        for (g in _groups.value) for (e in g.items) if (!e.keep && e.path in sel && seen.add(e.path)) entries.add(e)
        performDelete(entries, goToDone = true)
    }

    fun deleteEntries(entries: List<FileEntry>) = performDelete(entries, goToDone = false)

    private fun performDelete(entries: List<FileEntry>, goToDone: Boolean) {
        if (entries.isEmpty() || _delete.value !is DeleteUi.Idle) return
        // se uma pasta e algo dentro dela foram marcados, só a pasta precisa ser apagada
        val dirs = entries.filter { it.isDir }.map { it.path + "/" }
        val effective = entries.filter { e -> dirs.none { d -> e.path.startsWith(d) } }
        _delete.value = DeleteUi.Running(0, effective.size)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                Deleter.delete(effective) { done, total -> _delete.value = DeleteUi.Running(done, total) }
            }
            applyDeletion(effective, result)
            _lastFreed.value = result.freedBytes
            _lastFailed.value = result.failed.size
            _lifetimeFreed.value += result.freedBytes
            prefs.edit().putLong("freed_total", _lifetimeFreed.value).apply()
            refreshUsage()
            _delete.value = DeleteUi.Idle
            if (goToDone) {
                forward = true
                _stack.value = listOf(Screen.Home, Screen.Done)
            } else {
                _toast.value = if (result.freedBytes > 0) "${app.faxina.core.formatBytes(result.freedBytes)} liberados" else "Nada foi apagado"
            }
        }
    }

    private suspend fun applyDeletion(requested: List<FileEntry>, result: DeleteResult) = withContext(Dispatchers.Default) {
        val gone = result.deleted.toHashSet()
        MediaRefresh.forgetDeleted(ctx, requested.filter { !it.isDir && it.path in gone }.map { it.path })
        val roots = _tree.value
        for (e in requested) if (e.path in gone) removeFromTree(roots, e.path, e.size)
        val under = requested.filter { it.isDir && it.path in gone }.map { it.path + "/" }
        val cleaned = _groups.value.mapNotNull { g ->
            var items = g.items.filter { it.path !in gone && under.none { d -> it.path.startsWith(d) } }
            if (g.id == GroupId.DUPLICATES) items = dropFinishedSets(items)
            if (items.none { !it.keep }) null else CleanGroup(g.id, items)
        }
        _groups.value = cleaned
        _selected.update { sel -> sel.filter { it !in gone && under.none { d -> it.startsWith(d) } }.toSet() }
        _treeVersion.update { it + 1 }
    }

    /** Conjuntos de duplicados em que só sobrou a cópia mantida deixam de existir. */
    private fun dropFinishedSets(items: List<FileEntry>): List<FileEntry> {
        val live = items.groupBy { it.setId }.filter { (_, v) -> v.size >= 2 }.keys
        return items.filter { it.setId in live }
    }

    private fun removeFromTree(roots: List<DirNode>, path: String, size: Long) {
        for (r in roots) {
            if (!path.startsWith(r.path + "/")) continue
            var node = r
            node.size = (node.size - size).coerceAtLeast(0)
            while (true) {
                val child = node.children.firstOrNull { path == it.path || path.startsWith(it.path + "/") } ?: break
                if (child.path == path) {
                    node.children.remove(child)
                    break
                }
                child.size = (child.size - size).coerceAtLeast(0)
                node = child
            }
            return
        }
    }

    // ------------------------------------------------------------ apps
    private val _apps = MutableStateFlow<AppsUi>(AppsUi.Loading)
    val apps: StateFlow<AppsUi> = _apps.asStateFlow()

    fun loadApps() {
        _apps.value = AppsUi.Loading
        viewModelScope.launch {
            val stats = Perms.hasUsageAccess(ctx)
            _usageGranted.value = stats
            val items = AppsRepo.load(ctx, stats)
            _apps.value = AppsUi.Ready(items, stats)
        }
    }

    // ------------------------------------------------------------ atalhos
    /** Abre uma tela que precisa de varredura pronta; se ainda não houver, mostra o progresso primeiro. */
    fun openWithScan(target: Screen) {
        when (_scan.value) {
            ScanUi.Done -> go(target)
            ScanUi.Running -> go(Screen.Scan(target))
            ScanUi.Idle -> {
                startScan()
                go(Screen.Scan(target))
            }
        }
    }

    fun rescan() {
        if (_scan.value == ScanUi.Running) return
        _scan.value = ScanUi.Idle
        startScan()
    }

    fun cancelScan() {
        scanToken?.cancel()
        dupToken?.cancel()
    }

    override fun onCleared() {
        scanToken?.cancel()
        dupToken?.cancel()
    }

    init {
        if (_storageGranted.value) {
            startScan()
            loadAppsBytes()
        }
    }

    fun childFilesOf(path: String): List<FileEntry> {
        val list = File(path).listFiles() ?: return emptyList()
        return list.filter { it.isFile }.map { FileEntry(it.path, it.length(), it.lastModified()) }.sortedByDescending { it.size }
    }
}
