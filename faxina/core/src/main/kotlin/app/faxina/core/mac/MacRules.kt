package app.faxina.core.mac

import app.faxina.core.extensionOf

/** Pastas que são "pacotes" no macOS: parecem um arquivo no Finder, mas são pastas cheias de peças. */
internal val PACKAGE_EXT = setOf(
    "app", "photoslibrary", "musiclibrary", "tvlibrary", "imovielibrary", "fcpbundle", "logicx", "band",
    "sparsebundle", "sparseimage", "pvm", "vmwarevm", "xcodeproj", "xcworkspace", "playground", "framework",
    "bundle", "kext", "plugin", "rtfd", "xcarchive", "pages", "numbers", "key", "dmgpart",
)

/** Pacotes grandes que o usuário pode querer apagar inteiros (máquinas virtuais, arquivos do Xcode). */
internal val BIG_PACKAGE_EXT = setOf("pvm", "vmwarevm", "sparsebundle", "sparseimage", "xcarchive")

/** Pastas de projetos de código: contam no tamanho, mas nunca viram sugestão de arquivo solto. */
private val DEV_DIRS = setOf("node_modules", ".git", "pods", "venv", ".venv", "__pycache__", "site-packages", ".idea", ".gradle")

/** Caches de apps (Electron/Chromium) dentro de ~/Library/Application Support/<App>/... */
private val ELECTRON_CACHE_DIRS = setOf(
    "cache", "code cache", "gpucache", "cacheddata", "cachedextensionvsixs", "shadercache",
    "grshadercache", "dawncache", "dawnwebgpucache", "cachestorage",
)

private val SCREENSHOT_PREFIXES = listOf(
    "screenshot ", "screen shot ", "screen recording ", "captura de tela", "gravação de tela", "gravacao de tela",
    "bildschirmfoto", "capture d’écran", "capture d'écran", "captura de pantalla",
)

internal val INSTALLER_EXT = setOf("dmg", "pkg", "iso", "xip", "mpkg")

private const val DAY_MS = 24L * 3600 * 1000

internal fun isScreenshotName(lower: String): Boolean = SCREENSHOT_PREFIXES.any { lower.startsWith(it) }

/** Locais conhecidos de cache de desenvolvimento que se refazem sozinhos. */
internal val DEV_SAFE_PATHS = listOf(
    "Library/Developer/Xcode/DerivedData",
    "Library/Developer/CoreSimulator/Caches",
    "Library/Caches/Homebrew",
    "Library/Caches/CocoaPods",
    "Library/Caches/pip",
    "Library/Caches/Yarn",
    "Library/Caches/typescript",
    "Library/Caches/go-build",
    "Library/Caches/org.swift.swiftpm",
    ".npm/_cacache",
)

/** Também se refazem, mas custam tempo/rede para baixar de novo: ficam para o usuário decidir. */
internal val DEV_HEAVY_PATHS = listOf(
    ".gradle/caches",
    ".m2/repository",
    ".cargo/registry",
    ".android/cache",
    "Library/Developer/Xcode/iOS DeviceSupport",
    "Library/Developer/Xcode/watchOS DeviceSupport",
    "Library/Developer/Xcode/Archives",
    "Library/Developer/CoreSimulator/Devices",
)

internal enum class Skip { NONE, CLOUD }

/** Estado herdado enquanto descemos a pasta pessoal. */
internal class MCtx(
    val name: String,
    val rel: String,
    val depth: Int,
    val top: String,
    val hidden: Boolean,
    val inLibrary: Boolean,
    val trash: Boolean,
    /** Dentro de um pacote (.app, .photoslibrary...): nada ali vira sugestão. */
    val opaque: Boolean,
    val devDir: Boolean,
    val downloads: Boolean,
    val desktop: Boolean,
    val junk: MacGroupId?,
    val junkTop: Boolean,
    /** Extensão se esta pasta é um pacote. */
    val pkgExt: String?,
    val skip: Skip,
) {
    /** Arquivos soltos aqui não viram sugestão (grandes/duplicados/instaladores...). */
    val noCollect: Boolean get() = hidden || inLibrary || trash || opaque || devDir || pkgExt != null

    fun child(childName: String): MCtx {
        val lower = childName.lowercase()
        val childRel = if (rel.isEmpty()) childName else "$rel/$childName"
        val childDepth = depth + 1
        val childTop = if (depth == 0) lower else top
        val childInLibrary = inLibrary || (depth == 0 && lower == "library")
        val childTrash = trash || (depth == 0 && lower == ".trash")
        val ext = extensionOf(lower)
        val isPkg = ext in PACKAGE_EXT
        var j = junk
        var jt = false
        if (j == null && !opaque) {
            j = when {
                childInLibrary && childRel.startsWith("Library/Application Support/") && childDepth in 4..7 -> when (lower) {
                    in ELECTRON_CACHE_DIRS -> MacGroupId.APP_CACHES
                    "logs" -> MacGroupId.LOGS
                    else -> null
                }
                lower == "__macosx" && !childInLibrary -> MacGroupId.TEMP_FILES
                else -> null
            }
            jt = j != null
        }
        val cloud = childRel == "Library/Mobile Documents" || childRel == "Library/CloudStorage"
        return MCtx(
            name = childName, rel = childRel, depth = childDepth, top = childTop,
            hidden = hidden || childName.startsWith('.'),
            inLibrary = childInLibrary, trash = childTrash,
            opaque = opaque || pkgExt != null,
            devDir = devDir || lower in DEV_DIRS,
            downloads = downloads || (depth == 0 && lower == "downloads"),
            desktop = desktop || (depth == 0 && lower == "desktop"),
            junk = j, junkTop = jt,
            pkgExt = if (isPkg) ext else null,
            skip = if (cloud) Skip.CLOUD else Skip.NONE,
        )
    }

    companion object {
        fun root() = MCtx(
            name = "", rel = "", depth = 0, top = "", hidden = false, inLibrary = false, trash = false,
            opaque = false, devDir = false, downloads = false, desktop = false, junk = null, junkTop = false,
            pkgExt = null, skip = Skip.NONE,
        )
    }
}

internal fun fileTempGroup(lower: String, modified: Long, now: Long): MacGroupId? {
    val ext = extensionOf(lower)
    if (ext == "tmp" || ext == "temp") return MacGroupId.TEMP_FILES
    if ((ext == "crdownload" || ext == "part" || ext == "partial" || ext == "download") && now - modified > 3 * DAY_MS) {
        return MacGroupId.TEMP_FILES
    }
    return null
}
