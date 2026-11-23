package dev.jdtech.jellyfin.jrwi

import java.io.File

/**
 * Where the picture viewer gets a PDF's pages from (decision 19): the pictures the archiver draws
 * of its pages, or a copy on the phone that Android draws there, such as the one kept with a
 * download.
 */
sealed interface JrwiPdfSource {
    /** [pages] pages, page n at [pageUrl] with `{n}` replaced, as the archiver's preview gives. */
    data class Archiver(val pageUrl: String, val pages: Int) : JrwiPdfSource {
        fun page(number: Int): String = pageUrl.replace("{n}", number.toString())
    }

    data class Phone(val file: File) : JrwiPdfSource
}

/**
 * A file the archive only lists by its address, as a post's `attachments` and `documents` do, as
 * the file inspector and the picture viewer take it: its type from its name, its path under
 * /files/, and for a PDF its preview's address.
 */
fun JrwiFile.asPostFile(role: String): JrwiPostFile {
    val type = jrwiTypeFromName(name.ifBlank { url })
    val path =
        url.substringBefore('?')
            .removePrefix("/")
            .takeIf { it.startsWith("files/") }
            ?.removePrefix("files/")
    return JrwiPostFile(
        name = name,
        role = role,
        type = type,
        size = size,
        url = url,
        path = path?.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") },
        preview = if (type == "pdf" && path != null) "/api/preview/$path" else null,
    )
}

/** What a file is by its name, as the archive's `type` says it: only pictures and PDFs here. */
fun jrwiTypeFromName(name: String): String =
    when (name.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "bmp",
        "avif",
        "heic",
        "heif" -> "image"
        "pdf" -> "pdf"
        else -> "other"
    }
