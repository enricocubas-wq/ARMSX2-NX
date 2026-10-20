package app.faxina.core

private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "arw", "cr2", "nef", "tif", "tiff", "avif", "jfif")
private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "3g2", "webm", "m4v", "ts", "mpg", "mpeg", "vob", "m2ts")
private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma", "mid", "midi", "oga", "mka")
private val DOCUMENT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "epub", "mobi")
private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso")
private val APK = setOf("apk", "apks", "xapk", "apkm")

fun extensionOf(name: String): String {
    val i = name.lastIndexOf('.')
    return if (i < 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
}

fun categoryOf(name: String): FileCategory {
    val e = extensionOf(name)
    return when {
        e.isEmpty() -> FileCategory.OTHER
        e in IMAGE -> FileCategory.IMAGE
        e in VIDEO -> FileCategory.VIDEO
        e in AUDIO -> FileCategory.AUDIO
        e in DOCUMENT -> FileCategory.DOCUMENT
        e in ARCHIVE -> FileCategory.ARCHIVE
        e in APK -> FileCategory.APK
        else -> FileCategory.OTHER
    }
}
