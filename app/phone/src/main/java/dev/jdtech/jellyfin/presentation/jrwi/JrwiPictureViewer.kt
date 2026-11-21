package dev.jdtech.jellyfin.presentation.jrwi

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.jdtech.jellyfin.core.R as CoreR
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A post's pictures to show full screen, from the one at [start]. */
data class JrwiPictures(val paths: List<String>, val start: Int)

/**
 * A post's pictures full screen, as the archiver's picture viewer: swipe between them, pinch or tap
 * twice to zoom in, drag a zoomed picture to look around it, tap once to hide the buttons, and go
 * back or tap the X to close. Nothing is saved on the phone unless the save button is tapped.
 */
@Composable
fun JrwiPictureViewer(pictures: JrwiPictures, onClose: () -> Unit, onSave: (String) -> Unit) {
    val paths = pictures.paths
    if (paths.isEmpty()) return
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pages =
            rememberPagerState(initialPage = pictures.start.coerceIn(0, paths.lastIndex)) {
                paths.size
            }
        var zoomed by remember { mutableStateOf(false) }
        var buttonsShown by remember { mutableStateOf(true) }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pages,
                // A zoomed picture's drags look around it rather than turn to the next one
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val current = page == pages.currentPage
                ZoomablePicture(
                    path = paths[page],
                    current = current,
                    onZoomedChange = { if (current) zoomed = it },
                    onTap = { buttonsShown = !buttonsShown },
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
                    if (paths.size > 1) {
                        Text(
                            text = "${pages.currentPage + 1} / ${paths.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onSave(paths[pages.currentPage]) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_download),
                            contentDescription = stringResource(CoreR.string.jrwi_picture_save),
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

/**
 * One picture, shown whole, that can be zoomed into around the fingers or a double tap and moved
 * about while zoomed, without its edges coming away from the screen's.
 */
@Composable
private fun ZoomablePicture(
    path: String,
    current: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var pictureSize by remember { mutableStateOf(Size.Unspecified) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

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
        val fit =
            if (pictureSize.isSpecified && pictureSize.width > 0 && pictureSize.height > 0) {
                min(width / pictureSize.width, height / pictureSize.height)
            } else {
                0f
            }
        val shownWidth = if (fit > 0f) pictureSize.width * fit else width
        val shownHeight = if (fit > 0f) pictureSize.height * fit else height
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
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        if (loading) CircularProgressIndicator(color = Color.White)
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_MILLIS = 250

/**
 * Saves a picture to the phone's Pictures folder, under JRWI, through Android's download manager,
 * which shows it in the notifications. [url] is the picture's full address with the token.
 */
fun saveJrwiPicture(context: Context, url: String?, path: String) {
    if (url == null) return
    val name = pictureFileName(path)
    try {
        val request =
            DownloadManager.Request(url.toUri())
                .setTitle(name)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "JRWI/$name")
        context.getSystemService(DownloadManager::class.java).enqueue(request)
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
