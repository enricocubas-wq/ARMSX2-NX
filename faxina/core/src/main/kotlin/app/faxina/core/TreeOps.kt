package app.faxina.core

/** Acha a pasta pelo caminho completo dentro da árvore (ou null). */
fun findDirNode(root: DirNode, path: String): DirNode? {
    if (path == root.path) return root
    if (!path.startsWith(root.path + "/")) return null
    var cur = root
    while (true) {
        val next = cur.children.firstOrNull { path == it.path || path.startsWith(it.path + "/") } ?: return null
        if (next.path == path) return next
        cur = next
    }
}

fun findDirNode(roots: List<DirNode>, path: String): DirNode? = roots.firstNotNullOfOrNull { findDirNode(it, path) }

/** Tira [size] bytes da pasta [path] e de todas as acima dela; se [path] for uma pasta da árvore, remove-a. */
fun removeFromTree(roots: List<DirNode>, path: String, size: Long) {
    val root = roots.firstOrNull { path.startsWith(it.path + "/") } ?: return
    var node = root
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
}
