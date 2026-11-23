package dev.jdtech.jellyfin.presentation.jrwi

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPdfSource
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.asPostFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * What the picture viewer goes through, from the one at [start]: a post's pictures, and the files
 * it carries that are pictures or PDFs, a PDF page by page.
 */
data class JrwiPictures(val items: List<JrwiShown>, val start: Int)

/** Pictures alone, by their archiver paths or the phone's copies. */
fun jrwiPictures(paths: List<String>, start: Int): JrwiPictures =
    JrwiPictures(paths.map { JrwiShown.Picture(it) }, start)

/** One thing the picture viewer shows. */
sealed interface JrwiShown {
    /**
     * A picture, read from [path]: its archiver path or the phone's copy. [file] is set for a
     * picture the post carries as a file, which is saved under its own name.
     */
    data class Picture(
        val path: String,
        val file: JrwiPostFile? = null,
        val saveName: String? = null,
    ) : JrwiShown

    /** A PDF, shown page by page once the viewer gets to it. */
    data class Pdf(val file: JrwiPostFile) : JrwiShown
}

/**
 * A post's pictures, then its attachments, then its documents, as the post lists them, those that
 * are pictures or PDFs: what the picture viewer goes through for a post, as the web app's does
 * (decision 19). [files] gives each file's type and path as the archiver lists them; without them,
 * as from the feed, the type goes by the file's name. [showFrom] is where a file is read from, such
 * as the copy kept with a download.
 */
fun jrwiViewerItems(
    post: JrwiItem,
    files: List<JrwiPostFile>? = null,
    showFrom: (JrwiPostFile) -> String? = { it.url },
): List<JrwiShown> {
    val listed = files.orEmpty().filter { it.url != null }.associateBy { it.url }
    val carried =
        (post.attachments.map { listed[it.url] ?: it.asPostFile("attachment") } +
                post.documents.map { listed[it.url] ?: it.asPostFile("document") })
            .distinctBy { it.url }
    return post.images.map { JrwiShown.Picture(it.url) } +
        carried.mapNotNull { file ->
            when (file.type) {
                "image" -> showFrom(file)?.let { JrwiShown.Picture(it, file) }
                "pdf" -> JrwiShown.Pdf(file)
                else -> null
            }
        }
}

/** Where [file] is among what the viewer goes through, or -1 when it isn't. */
fun List<JrwiShown>.indexOfFile(file: JrwiPostFile): Int = indexOfFirst {
    when (it) {
        is JrwiShown.Picture -> it.file?.url != null && it.file.url == file.url
        is JrwiShown.Pdf -> it.file.url == file.url
    }
}

/**
 * A page of the viewer: a picture, or a PDF's page, which is a placeholder until it's looked up.
 */
private sealed interface ViewerPage {
    /** Which of the viewer's things it belongs to. */
    val item: Int
    val key: String

    data class Picture(override val item: Int, val shown: JrwiShown.Picture) : ViewerPage {
        override val key: String
            get() = "picture:$item"
    }

    data class PdfPage(
        override val item: Int,
        val file: JrwiPostFile,
        val number: Int,
        val count: Int? = null,
        val source: JrwiPdfSource? = null,
        val failed: Boolean = false,
    ) : ViewerPage {
        override val key: String
            get() = "pdf:$item:$number"
    }
}

/**
 * A post's pictures and PDFs full screen, as the archiver's picture viewer: swipe between them, or
 * tap the arrows at the sides; pinch or tap twice to zoom in, drag a zoomed picture to look around
 * it, tap once to hide the buttons, and go back or tap the X to close. A PDF is looked up when the
 * viewer gets to it ([pdfSource]) and goes in page by page; its label gives its name and the page.
 * The save button keeps the picture, or the PDF itself, on the phone, and [onOpenElsewhere] opens a
 * file in another app.
 */
@Composable
fun JrwiPictureViewer(
    pictures: JrwiPictures,
    onClose: () -> Unit,
    fileUrl: (String?) -> String?,
    pdfSource: (suspend (JrwiPostFile) -> JrwiPdfSource)? = null,
    onOpenElsewhere: ((JrwiPostFile) -> Unit)? = null,
) {
    val items = pictures.items
    if (items.isEmpty()) return
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // A PDF has one page here until it's looked up, so the places of what's before it hold
        val pageList =
            remember(items) {
                mutableStateListOf<ViewerPage>().apply {
                    items.forEachIndexed { index, shown ->
                        add(
                            when (shown) {
                                is JrwiShown.Picture -> ViewerPage.Picture(index, shown)
                                is JrwiShown.Pdf ->
                                    ViewerPage.PdfPage(index, shown.file, number = 1)
                            }
                        )
                    }
                }
            }
        val pages =
            rememberPagerState(initialPage = pictures.start.coerceIn(0, items.lastIndex)) {
                pageList.size
            }
        val scope = rememberCoroutineScope()
        // PDFs drawn on the phone, open while the viewer is, closed with it
        val opened = remember { mutableStateMapOf<String, PdfPages>() }
        DisposableEffect(Unit) { onDispose { opened.values.forEach { it.close() } } }
        // Looks a PDF up once the viewer is on it or next to it after it, so what's shown keeps its
        // place as the PDF's pages go in
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(pages, attempt) {
            snapshotFlow { pages.currentPage }
                .collect { current ->
                    for (index in listOf(current, current + 1)) {
                        val page = pageList.getOrNull(index) as? ViewerPage.PdfPage ?: continue
                        // Not looked up yet: one being looked up has a count of -1
                        if (page.count != null || page.failed || pdfSource == null) continue
                        launch { lookUpPdf(page, pageList, pdfSource, opened) }
                    }
                }
        }
        var zoomed by remember { mutableStateOf(false) }
        var buttonsShown by remember { mutableStateOf(true) }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pages,
                // A zoomed picture's drags look around it rather than turn to the next one
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { pageList.getOrNull(it)?.key ?: "page:$it" },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val current = index == pages.currentPage
                val onZoomedChange = { value: Boolean -> if (current) zoomed = value }
                val onTap = { buttonsShown = !buttonsShown }
                when (val page = pageList.getOrNull(index)) {
                    is ViewerPage.Picture ->
                        ZoomablePicture(
                            path = page.shown.path,
                            current = current,
                            onZoomedChange = onZoomedChange,
                            onTap = onTap,
                        )
                    is ViewerPage.PdfPage ->
                        when (val source = page.source) {
                            is JrwiPdfSource.Archiver ->
                                ZoomablePicture(
                                    path = source.page(page.number),
                                    current = current,
                                    onZoomedChange = onZoomedChange,
                                    onTap = onTap,
                                )
                            is JrwiPdfSource.Phone ->
                                opened[source.file.path]?.let { pdf ->
                                    ZoomablePdfPage(
                                        pdf = pdf,
                                        index = page.number - 1,
                                        current = current,
                                        onZoomedChange = onZoomedChange,
                                        onTap = onTap,
                                    )
                                }
                            null ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier.fillMaxSize().pointerInput(Unit) {
                                            detectTapGestures(onTap = { onTap() })
                                        },
                                ) {
                                    if (page.failed || pdfSource == null) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text =
                                                    stringResource(
                                                        CoreR.string.jrwi_viewer_pdf_failed,
                                                        page.file.name,
                                                    ),
                                                color = Color.White,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(24.dp),
                                            )
                                            if (pdfSource != null) {
                                                TextButton(
                                                    onClick = {
                                                        retryPdf(page, pageList)
                                                        attempt++
                                                    }
                                                ) {
                                                    Text(
                                                        stringResource(CoreR.string.retry),
                                                        color = Color.White,
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        CircularProgressIndicator(color = Color.White)
                                    }
                                }
                        }
                    null -> Unit
                }
            }
            val shown = pageList.getOrNull(pages.currentPage)
            // The arrows at the sides, for going on without swiping
            AnimatedVisibility(
                visible = buttonsShown && pages.currentPage > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).sideInsets(),
            ) {
                ViewerArrow(
                    icon = CoreR.drawable.ic_chevron_left,
                    contentDescription = stringResource(CoreR.string.jrwi_viewer_previous),
                    onClick = { scope.launch { pages.animateScrollToPage(pages.currentPage - 1) } },
                )
            }
            AnimatedVisibility(
                visible = buttonsShown && pages.currentPage < pageList.lastIndex,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).sideInsets(),
            ) {
                ViewerArrow(
                    icon = CoreR.drawable.ic_chevron_right,
                    contentDescription = stringResource(CoreR.string.jrwi_viewer_next),
                    onClick = { scope.launch { pages.animateScrollToPage(pages.currentPage + 1) } },
                )
            }
            AnimatedVisibility(
                visible = buttonsShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                                )
                            )
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                                )
                            )
                            .padding(4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        // Which of the post's pictures and files, as the web app counts them
                        if (items.size > 1 && shown != null) {
                            Text(
                                text = "${shown.item + 1} / ${items.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        viewerLabel(shown)?.let { label ->
                            Text(
                                text = label,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val file =
                        when (shown) {
                            is ViewerPage.Picture -> shown.shown.file
                            is ViewerPage.PdfPage -> shown.file
                            null -> null
                        }
                    if (file != null && onOpenElsewhere != null) {
                        IconButton(onClick = { onOpenElsewhere(file) }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_external_link),
                                contentDescription = stringResource(CoreR.string.jrwi_file_open),
                                tint = Color.White,
                            )
                        }
                    }
                    IconButton(onClick = { shown?.let { save(context, it, fileUrl) } }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_download),
                            contentDescription =
                                stringResource(
                                    if (shown is ViewerPage.PdfPage) CoreR.string.jrwi_file_save
                                    else CoreR.string.jrwi_picture_save
                                ),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_x),
                            contentDescription = stringResource(CoreR.string.jrwi_picture_close),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Clear of a notch at the side, as the buttons at the top are. */
@Composable
private fun Modifier.sideInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/** A round arrow on a dark circle at the side of the screen, to go to the one before or after. */
@Composable
private fun ViewerArrow(icon: Int, contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier.padding(8.dp).size(48.dp).clip(CircleShape).background(ARROW_BACKGROUND),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White,
        )
    }
}

/** A file's name, with the page of a PDF: "Map.pdf · page 2 of 5". */
@Composable
private fun viewerLabel(page: ViewerPage?): String? =
    when (page) {
        is ViewerPage.Picture -> page.shown.file?.name?.takeIf { it.isNotBlank() }
        is ViewerPage.PdfPage ->
            page.count
                ?.takeIf { it > 0 }
                ?.let {
                    stringResource(CoreR.string.jrwi_viewer_page, page.file.name, page.number, it)
                } ?: page.file.name
        null -> null
    }

/**
 * Looks a PDF up, and puts its pages in its placeholder's place: the archiver's pictures of them,
 * or a copy on the phone, which is opened to draw them.
 */
private suspend fun lookUpPdf(
    page: ViewerPage.PdfPage,
    pageList: SnapshotStateList<ViewerPage>,
    pdfSource: suspend (JrwiPostFile) -> JrwiPdfSource,
    opened: SnapshotStateMap<String, PdfPages>,
) {
    // Asked once, however often the viewer passes it meanwhile
    val loading = page.copy(source = null, failed = false, count = -1)
    val at = pageList.indexOf(page).takeIf { it >= 0 } ?: return
    pageList[at] = loading
    val count =
        try {
            when (val source = pdfSource(page.file)) {
                is JrwiPdfSource.Archiver -> source to source.pages
                is JrwiPdfSource.Phone -> {
                    val pdf = opened[source.file.path] ?: PdfPages.open(source.file)
                    opened[source.file.path] = pdf
                    source to pdf.pageCount
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    val place = pageList.indexOf(loading).takeIf { it >= 0 } ?: return
    if (count == null || count.second <= 0) {
        pageList[place] = page.copy(failed = true)
        return
    }
    val (source, pages) = count
    val all =
        (1..pages).map { number -> ViewerPage.PdfPage(page.item, page.file, number, pages, source) }
    pageList.removeAt(place)
    pageList.addAll(place, all)
}

/** Makes a PDF that couldn't be looked up a placeholder again, to be asked for once more. */
private fun retryPdf(page: ViewerPage.PdfPage, pageList: SnapshotStateList<ViewerPage>) {
    val at = pageList.indexOf(page).takeIf { it >= 0 } ?: return
    pageList[at] = page.copy(failed = false, count = null, source = null)
}

/** Keeps what's shown on the phone: the picture, or a PDF itself. */
private fun save(context: Context, page: ViewerPage, fileUrl: (String?) -> String?) {
    when (page) {
        is ViewerPage.Picture -> {
            val file = page.shown.file
            if (file != null) {
                saveFromJrwi(context, fileUrl(file.url), file.name, Environment.DIRECTORY_PICTURES)
            } else {
                saveJrwiPicture(
                    context,
                    fileUrl(page.shown.path),
                    page.shown.path,
                    page.shown.saveName,
                )
            }
        }
        is ViewerPage.PdfPage ->
            saveFromJrwi(
                context,
                fileUrl(page.file.url),
                page.file.name,
                Environment.DIRECTORY_DOWNLOADS,
            )
    }
}

/** One picture from [path], shown whole, that can be zoomed into. */
@Composable
private fun ZoomablePicture(
    path: String,
    current: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var pictureSize by remember { mutableStateOf(Size.Unspecified) }
    var loading by remember { mutableStateOf(true) }
    Zoomable(
        current = current,
        pictureSize = pictureSize,
        onZoomedChange = onZoomedChange,
        onTap = onTap,
        overlay = { if (loading) CircularProgressIndicator(color = Color.White) },
    ) { modifier ->
        val context = LocalPlatformContext.current
        // Sharp enough to zoom into, without decoding a huge picture at full size
        val window = LocalWindowInfo.current.containerSize
        val longest = max(window.width, window.height).coerceAtLeast(1) * 2
        val url = jrwiUrl(path)
        val request =
            remember(url, longest) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(longest, longest)
                    .precision(Precision.INEXACT)
                    .build()
            }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onLoading = { loading = true },
            onSuccess = {
                loading = false
                pictureSize = it.painter.intrinsicSize
            },
            onError = { loading = false },
            modifier = modifier,
        )
    }
}

/** A page of a PDF on the phone, drawn by Android at twice the screen's width to zoom into. */
@Composable
private fun ZoomablePdfPage(
    pdf: PdfPages,
    index: Int,
    current: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val window = LocalWindowInfo.current.containerSize
    val width = (window.width.coerceAtLeast(1) * 2).coerceAtMost(MAX_PDF_WIDTH)
    val page by
        produceState<ImageBitmap?>(null, pdf, index, width) { value = pdf.render(index, width) }
    val drawn = page
    Zoomable(
        current = current,
        pictureSize =
            drawn?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Unspecified,
        onZoomedChange = onZoomedChange,
        onTap = onTap,
        overlay = { if (drawn == null) CircularProgressIndicator(color = Color.White) },
    ) { modifier ->
        if (drawn != null) {
            Image(
                bitmap = drawn,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier,
            )
        }
    }
}

/**
 * Something shown whole, that can be zoomed into around the fingers or a double tap and moved about
 * while zoomed, without its edges coming away from the screen's. [pictureSize] is its own size, for
 * how far it can move; [content] draws it with the modifier that zooms and moves it.
 */
@Composable
private fun Zoomable(
    current: Boolean,
    pictureSize: Size,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    overlay: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val size by rememberUpdatedState(pictureSize)

    val zoomedChange by rememberUpdatedState(onZoomedChange)
    LaunchedEffect(Unit) {
        snapshotFlow { scale > 1f }.distinctUntilChanged().collect { zoomedChange(it) }
    }
    // A picture swiped away is whole again when it comes back
    LaunchedEffect(current) {
        if (!current) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    // How far the picture can move each way at [zoom] before an edge comes away from the screen's
    fun limits(zoom: Float): Offset {
        val width = box.width.toFloat()
        val height = box.height.toFloat()
        val own = size
        val fit =
            if (own.isSpecified && own.width > 0 && own.height > 0) {
                min(width / own.width, height / own.height)
            } else {
                0f
            }
        val shownWidth = if (fit > 0f) own.width * fit else width
        val shownHeight = if (fit > 0f) own.height * fit else height
        return Offset(
            max(0f, (shownWidth * zoom - width) / 2f),
            max(0f, (shownHeight * zoom - height) / 2f),
        )
    }

    fun keptIn(value: Offset, zoom: Float): Offset {
        val limit = limits(zoom)
        return Offset(value.x.coerceIn(-limit.x, limit.x), value.y.coerceIn(-limit.y, limit.y))
    }

    // Where the picture goes so the spot under [focus] stays under it as the zoom changes to [zoom]
    fun offsetAround(focus: Offset, zoom: Float): Offset {
        val fromCenter = focus - Offset(box.width / 2f, box.height / 2f)
        return fromCenter - (fromCenter - offset) * (zoom / scale)
    }

    fun animateTo(zoom: Float, to: Offset) {
        val fromScale = scale
        val fromOffset = offset
        scope.launch {
            animate(0f, 1f, animationSpec = tween(ZOOM_MILLIS)) { fraction, _ ->
                scale = fromScale + (zoom - fromScale) * fraction
                offset = lerp(fromOffset, to, fraction)
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.fillMaxSize()
                .onSizeChanged { box = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { spot ->
                            if (scale > 1f) {
                                animateTo(1f, Offset.Zero)
                            } else {
                                animateTo(
                                    DOUBLE_TAP_ZOOM,
                                    keptIn(offsetAround(spot, DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM),
                                )
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            // One finger on a whole picture is left to the pager, to swipe on
                            if (fingers > 1 || scale > 1f) {
                                val zoom = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val focus = event.calculateCentroid(useCurrent = true)
                                val moved =
                                    if (focus.isSpecified) offsetAround(focus, zoom) else offset
                                scale = zoom
                                offset = keptIn(moved + event.calculatePan(), zoom)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
    ) {
        content(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
        overlay()
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_MILLIS = 250

// A PDF's page drawn on the phone is no wider than this, as the archiver's are 1400
private const val MAX_PDF_WIDTH = 2800
private val ARROW_BACKGROUND = Color.Black.copy(alpha = 0.45f)

/**
 * Saves a picture to the phone's Pictures folder, under JRWI, through Android's download manager,
 * which shows it in the notifications. [url] is the picture's full address with the token, which
 * goes to the download manager as a header.
 */
fun saveJrwiPicture(context: Context, url: String?, path: String, saveName: String? = null) {
    if (url == null) return
    val name = safeFileName(saveName ?: pictureFileName(path))
    try {
        enqueueJrwiDownload(context, url, name, Environment.DIRECTORY_PICTURES, "JRWI/$name")
        Toast.makeText(
                context,
                context.getString(CoreR.string.jrwi_picture_saving, name),
                Toast.LENGTH_SHORT,
            )
            .show()
    } catch (e: Exception) {
        Toast.makeText(context, e.localizedMessage ?: name, Toast.LENGTH_LONG).show()
    }
}

/** A name for a saved picture: the post's ID and the picture's own name, such as 290-01.webp. */
private fun pictureFileName(path: String): String {
    val parts = path.substringBefore('?').trim('/').split('/').map { Uri.decode(it) }
    val name = parts.lastOrNull().orEmpty().ifBlank { "picture" }
    val post = parts.indexOf("posts").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) }
    return if (post != null && post != name) "$post-$name" else name
}
