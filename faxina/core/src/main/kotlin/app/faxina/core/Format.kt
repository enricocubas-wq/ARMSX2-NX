package app.faxina.core

/**
 * Formata bytes em unidades decimais (1 GB = 1.000 MB), igual às Configurações do Android,
 * com vírgula decimal: "1,23 GB".
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes / 1000.0
    var i = 0
    while (value >= 1000 && i < units.lastIndex) {
        value /= 1000.0
        i++
    }
    val decimals = when {
        i <= 1 && value >= 10 -> 0 // KB e MB sem casas a partir de 10
        i <= 1 -> 1
        value >= 100 -> 0
        value >= 10 -> 1
        else -> 2
    }
    return fixed(value, decimals) + " " + units[i]
}

private fun fixed(v: Double, decimals: Int): String {
    if (decimals == 0) return Math.round(v).toString()
    val scale = if (decimals == 1) 10.0 else 100.0
    val r = Math.round(v * scale)
    val whole = r / scale.toLong()
    val frac = (r % scale.toLong()).toString().padStart(decimals, '0')
    return "$whole,$frac"
}
