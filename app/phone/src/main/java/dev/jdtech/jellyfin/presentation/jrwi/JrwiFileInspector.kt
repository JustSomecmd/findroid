package dev.jdtech.jellyfin.presentation.jrwi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiPreview
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.formatBytes
import java.io.Closeable
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a file is, as a word: "PDF", "Spreadsheet" and so on, as the web app's inspector says. */
@Composable
fun jrwiFileTypeName(type: String): String =
    stringResource(
        when (type) {
            "pdf" -> CoreR.string.jrwi_file_pdf
            "table" -> CoreR.string.jrwi_file_table
            "document" -> CoreR.string.jrwi_file_document
            "text" -> CoreR.string.jrwi_file_text
            "archive" -> CoreR.string.jrwi_file_archive
            "image" -> CoreR.string.jrwi_file_image
            "audio" -> CoreR.string.jrwi_file_audio
            "video" -> CoreR.string.jrwi_file_video
            "youtube" -> CoreR.string.jrwi_file_youtube
            "link" -> CoreR.string.jrwi_file_link
            else -> CoreR.string.jrwi_file_other
        }
    )

/** An icon for a file's type. */
fun jrwiFileIcon(type: String): Int =
    when (type) {
        "pdf",
        "document",
        "text" -> CoreR.drawable.ic_logs
        "table" -> CoreR.drawable.ic_database
        "archive" -> CoreR.drawable.ic_library
        "image" -> CoreR.drawable.ic_eye
        "audio" -> CoreR.drawable.ic_headphones
        "video" -> CoreR.drawable.ic_film
        "youtube" -> CoreR.drawable.ic_youtube
        "link" -> CoreR.drawable.ic_external_link
        else -> CoreR.drawable.ic_download
    }

/** A file's details under its name: its type and size, or that it isn't in the archive. */
@Composable
fun jrwiFileDetail(file: JrwiPostFile): String {
    val parts = mutableListOf(jrwiFileTypeName(file.type))
    if (!file.saved && file.type == "link") {
        parts += stringResource(CoreR.string.jrwi_file_not_saved)
    }
    file.size?.let { parts += formatBytes(it) }
    return parts.joinToString(" · ")
}

/**
 * A file of a post, full screen, showing what's inside it as the archiver's file inspector does:
 * PDFs page by page, spreadsheets sheet by sheet, Word and PowerPoint files as their text, text
 * files and what's in a zip. Nothing is saved on the phone unless the save button is tapped.
 *
 * [loadPreview] gives what's inside, from the archiver or kept with a download; [fileOnPhone] gives
 * the file itself on the phone, for drawing a PDF there; [hasLocalCopy] says one was kept with the
 * download. [fileUrl] gives the archiver's address with the token, for saving; another app gets the
 * file itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiFileInspector(
    file: JrwiPostFile,
    hasLocalCopy: Boolean,
    loadPreview: suspend (JrwiPostFile) -> JrwiPreview,
    fileOnPhone: suspend (JrwiPostFile) -> File,
    fileUrl: (String?) -> String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pictures by remember { mutableStateOf<JrwiPictures?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // In the archiver's own colours, like the page it opens from
        JrwiTheme {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = file.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text =
                                        listOfNotNull(
                                                jrwiFileTypeName(file.type),
                                                file.mime,
                                                file.size?.let { formatBytes(it) },
                                            )
                                            .joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_x),
                                    contentDescription =
                                        stringResource(CoreR.string.jrwi_picture_close),
                                )
                            }
                        },
                        actions = {
                            if (file.url != null) {
                                // Through a copy in the app, so the other app gets no token
                                IconButton(
                                    onClick = {
                                        openJrwiFileElsewhere(context, scope, file, fileOnPhone)
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_external_link),
                                        contentDescription =
                                            stringResource(CoreR.string.jrwi_file_open),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        saveFromJrwi(
                                            context,
                                            fileUrl(file.url),
                                            file.name,
                                            Environment.DIRECTORY_DOWNLOADS,
                                        )
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_download),
                                        contentDescription =
                                            stringResource(CoreR.string.jrwi_file_save),
                                    )
                                }
                            }
                            file.source?.let { source ->
                                IconButton(onClick = { openUrl(context, source) }) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_globe),
                                        contentDescription =
                                            stringResource(CoreR.string.jrwi_file_source),
                                    )
                                }
                            }
                        },
                        colors = jrwiTopBarColors(),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (file.type) {
                            "pdf" ->
                                JrwiPdf(
                                    file = file,
                                    hasLocalCopy = hasLocalCopy,
                                    loadPreview = loadPreview,
                                    fileOnPhone = fileOnPhone,
                                    onPage = { pictures = it },
                                )
                            "table",
                            "document",
                            "text",
                            "archive" -> JrwiPreviewBody(file, loadPreview)
                            else -> InspectorNote(stringResource(CoreR.string.jrwi_file_no_preview))
                        }
                    }
                }
            }
            pictures?.let { shown ->
                JrwiPictureViewer(
                    pictures = shown,
                    onClose = { pictures = null },
                    fileUrl = fileUrl,
                )
            }
        }
    }
}

/** A preview that's loading, ready or couldn't be had. */
private sealed interface PreviewState {
    data object Loading : PreviewState

    data class Ready(val preview: JrwiPreview) : PreviewState

    data class Failed(val reason: String) : PreviewState
}

@Composable
private fun rememberPreview(
    file: JrwiPostFile,
    loadPreview: suspend (JrwiPostFile) -> JrwiPreview,
): PreviewState {
    val state by
        produceState<PreviewState>(PreviewState.Loading, file) {
            value =
                try {
                    PreviewState.Ready(loadPreview(file))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PreviewState.Failed(e.message ?: e.javaClass.simpleName)
                }
        }
    return state
}

/** A spreadsheet, document, text file or zip, from the archiver's preview. */
@Composable
private fun JrwiPreviewBody(
    file: JrwiPostFile,
    loadPreview: suspend (JrwiPostFile) -> JrwiPreview,
) {
    when (val state = rememberPreview(file, loadPreview)) {
        PreviewState.Loading -> Loading()
        is PreviewState.Failed ->
            InspectorNote(stringResource(CoreR.string.jrwi_file_preview_failed, state.reason))
        is PreviewState.Ready ->
            when (state.preview.type) {
                "table" -> JrwiTable(state.preview)
                "document" -> JrwiDocument(state.preview)
                "text" -> JrwiText(state.preview)
                "archive" -> JrwiArchive(state.preview)
                else -> InspectorNote(stringResource(CoreR.string.jrwi_file_no_preview))
            }
    }
}

// PDFs

/**
 * A PDF page by page: drawn on the phone from the copy kept with a download, or else the pages the
 * archiver draws as pictures, which open in the picture viewer to zoom into. When the archiver
 * can't draw its pages, the PDF is fetched and drawn on the phone.
 */
@Composable
private fun JrwiPdf(
    file: JrwiPostFile,
    hasLocalCopy: Boolean,
    loadPreview: suspend (JrwiPostFile) -> JrwiPreview,
    fileOnPhone: suspend (JrwiPostFile) -> File,
    onPage: (JrwiPictures) -> Unit,
) {
    if (hasLocalCopy) {
        PdfOnPhone(file, fileOnPhone)
        return
    }
    when (val state = rememberPreview(file, loadPreview)) {
        PreviewState.Loading -> Loading()
        is PreviewState.Failed -> PdfOnPhone(file, fileOnPhone)
        is PreviewState.Ready -> {
            val pageUrl = state.preview.pageUrl
            val pages = state.preview.pages ?: 0
            if (pageUrl == null || pages <= 0) {
                PdfOnPhone(file, fileOnPhone)
            } else {
                val urls =
                    remember(pageUrl, pages) {
                        (1..pages).map { pageUrl.replace("{n}", it.toString()) }
                    }
                LazyColumn(
                    contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        InspectorText(
                            pluralStringResource(CoreR.plurals.jrwi_file_pages, pages, pages)
                        )
                    }
                    itemsIndexed(urls) { index, url ->
                        PdfPage(index + 1) {
                            var aspect by remember { mutableFloatStateOf(A4) }
                            AsyncImage(
                                model = jrwiUrl(url),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                onSuccess = {
                                    val size = it.painter.intrinsicSize
                                    if (size.width > 0 && size.height > 0) {
                                        aspect = size.width / size.height
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .aspectRatio(aspect)
                                        .background(PAGE_BACKGROUND)
                                        .clickable {
                                            // The PDF's pages, saved named after it
                                            val name = file.name.substringBeforeLast('.')
                                            onPage(
                                                JrwiPictures(
                                                    urls.mapIndexed { number, url ->
                                                        JrwiShown.Picture(
                                                            url,
                                                            saveName =
                                                                "$name - page ${number + 1}.png",
                                                        )
                                                    },
                                                    index,
                                                )
                                            )
                                        },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A PDF drawn on the phone with Android's renderer, from a copy on the phone. */
@Composable
private fun PdfOnPhone(file: JrwiPostFile, fileOnPhone: suspend (JrwiPostFile) -> File) {
    val opened by
        produceState<Result<PdfPages>?>(null, file) {
            value =
                try {
                    Result.success(PdfPages.open(fileOnPhone(file)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
        }
    val result = opened
    if (result == null) {
        Loading()
        return
    }
    val pdf = result.getOrNull()
    if (pdf == null) {
        val reason = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        InspectorNote(stringResource(CoreR.string.jrwi_file_pdf_failed, reason.orEmpty()))
        return
    }
    DisposableEffect(pdf) { onDispose { pdf.close() } }
    // Sharp on the screen, and no bigger than the archiver's own page pictures
    val width = min(LocalWindowInfo.current.containerSize.width.coerceAtLeast(1), MAX_PAGE_WIDTH)
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            InspectorText(
                pluralStringResource(CoreR.plurals.jrwi_file_pages, pdf.pageCount, pdf.pageCount)
            )
        }
        items(pdf.pageCount) { index ->
            PdfPage(index + 1) {
                val page by
                    produceState<ImageBitmap?>(null, pdf, index) {
                        value = pdf.render(index, width)
                    }
                val drawn = page
                if (drawn == null) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth().aspectRatio(A4).background(PAGE_BACKGROUND)
                    )
                } else {
                    Image(
                        bitmap = drawn,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(number: Int, page: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall)) {
        page()
        Text(
            text = stringResource(CoreR.string.jrwi_file_page, number),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A PDF open with Android's renderer, which draws one page at a time, so pages are drawn one after
 * another, and it's closed once no page is being drawn.
 */
internal class PdfPages
private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    private val lock = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** One page as a picture [width] pixels wide, on white as on paper; null once closed. */
    suspend fun render(index: Int, width: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock null
                renderer.openPage(index).use { page ->
                    val height =
                        (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt()
                    val bitmap =
                        Bitmap.createBitmap(width, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        }

    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                renderer.close()
                descriptor.close()
            }
        }
    }

    companion object {
        suspend fun open(file: File): PdfPages =
            withContext(Dispatchers.IO) {
                val descriptor =
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfPages(descriptor, PdfRenderer(descriptor))
                } catch (e: Exception) {
                    descriptor.close()
                    throw e
                }
            }
    }
}

// Spreadsheets, documents, text and zips

/** Every sheet's rows, one sheet at a time, with row numbers as the web app shows them. */
@Composable
private fun JrwiTable(preview: JrwiPreview) {
    val sheets = preview.sheets
    var shown by remember(preview) { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        preview.note?.let { InspectorText(it) }
        if (sheets.isEmpty()) {
            if (preview.note == null)
                InspectorText(stringResource(CoreR.string.jrwi_file_table_empty))
            return@Column
        }
        if (sheets.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                itemsIndexed(sheets) { index, sheet ->
                    val selected = index == shown
                    Surface(
                        shape = JrwiShape,
                        color =
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor =
                            if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { shown = index },
                    ) {
                        Text(
                            text =
                                sheet.name.ifBlank {
                                    stringResource(CoreR.string.jrwi_file_sheet, index + 1)
                                },
                            style = MaterialTheme.typography.labelLarge,
                            modifier =
                                Modifier.padding(
                                    horizontal = MaterialTheme.spacings.medium,
                                    vertical = MaterialTheme.spacings.small,
                                ),
                        )
                    }
                }
            }
        }
        val sheet = sheets[shown.coerceIn(0, sheets.lastIndex)]
        if (sheet.rows.isEmpty()) {
            InspectorText(stringResource(CoreR.string.jrwi_file_sheet_empty))
            return@Column
        }
        // Every row as wide as the widest, so they all scroll sideways together
        val columns = remember(sheet) { sheet.rows.maxOf { it.size } }
        val sideways = rememberScrollState()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(sheet.rows) { index, row ->
                Row(modifier = Modifier.horizontalScroll(sideways)) {
                    TableCell(
                        text = (index + 1).toString(),
                        width = 44.dp,
                        muted = true,
                    )
                    for (column in 0 until columns) {
                        TableCell(text = row.getOrNull(column).orEmpty(), width = 160.dp)
                    }
                }
                HorizontalDivider(color = JrwiColors.Line)
            }
            if (sheet.truncated) {
                item { InspectorText(stringResource(CoreR.string.jrwi_file_truncated_table)) }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else JrwiColors.BodyText,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** A Word or PowerPoint file's headings, paragraphs, list items and table rows. */
@Composable
private fun JrwiDocument(preview: JrwiPreview) {
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier = Modifier.fillMaxSize(),
    ) {
        preview.note?.let { note -> item { InspectorText(note, padded = false) } }
        if (preview.blocks.isEmpty() && preview.note == null) {
            item {
                InspectorText(stringResource(CoreR.string.jrwi_file_document_empty), padded = false)
            }
        }
        items(preview.blocks) { block ->
            when (block.kind) {
                "heading" ->
                    Text(
                        text = block.text.orEmpty(),
                        style =
                            when (block.level ?: 1) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                    )
                "item" ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                    ) {
                        Text("•", color = JrwiColors.BodyText)
                        Text(text = block.text.orEmpty(), color = JrwiColors.BodyText)
                    }
                "row" ->
                    Text(
                        text = block.cells.joinToString("  |  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = JrwiColors.BodyText,
                    )
                else -> Text(text = block.text.orEmpty(), color = JrwiColors.BodyText)
            }
        }
    }
}

/** A text file as it is, never as a page, in pieces so a long one scrolls smoothly. */
@Composable
private fun JrwiText(preview: JrwiPreview) {
    val pieces =
        remember(preview) {
            preview.text.orEmpty().lines().chunked(TEXT_LINES).map { it.joinToString("\n") }
        }
    SelectionContainer {
        LazyColumn(
            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pieces) { piece ->
                Text(
                    text = piece,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = JrwiColors.BodyText,
                )
            }
            if (preview.truncated) {
                item {
                    InspectorText(
                        stringResource(CoreR.string.jrwi_file_truncated_text),
                        padded = false,
                    )
                }
            }
        }
    }
}

/** What's in a zip, with each file's size. */
@Composable
private fun JrwiArchive(preview: JrwiPreview) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        preview.note?.let { note -> item { InspectorText(note) } }
        item {
            InspectorText(
                pluralStringResource(
                    CoreR.plurals.jrwi_file_entries,
                    preview.entries.size,
                    preview.entries.size,
                ) + if (preview.truncated) "+" else ""
            )
        }
        items(preview.entries) { entry ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacings.medium,
                            vertical = MaterialTheme.spacings.small,
                        ),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.BodyText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text =
                        if (entry.folder) stringResource(CoreR.string.jrwi_file_folder)
                        else entry.size?.let { formatBytes(it) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        CircularProgressIndicator()
    }
}

@Composable
private fun InspectorNote(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InspectorText(text: String, padded: Boolean = true) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (padded) Modifier.padding(MaterialTheme.spacings.medium) else Modifier,
    )
}

/**
 * Saves a file from the archiver to the phone's [directory], under JRWI, through Android's download
 * manager, which shows it in the notifications. [url] is its full address with the token, which
 * goes to the download manager as a header.
 */
fun saveFromJrwi(context: Context, url: String?, name: String, directory: String) {
    if (url == null) return
    val fileName = safeFileName(name)
    try {
        enqueueJrwiDownload(context, url, fileName, directory, "JRWI/$fileName")
        Toast.makeText(
                context,
                context.getString(CoreR.string.jrwi_file_saving, fileName, directory),
                Toast.LENGTH_SHORT,
            )
            .show()
    } catch (e: Exception) {
        Toast.makeText(context, e.localizedMessage ?: fileName, Toast.LENGTH_LONG).show()
    }
}

// The usual shape of a page, until it's drawn
private const val A4 = 1f / 1.414f
private const val MAX_PAGE_WIDTH = 1400
private const val TEXT_LINES = 40
private val PAGE_BACKGROUND = androidx.compose.ui.graphics.Color.White
