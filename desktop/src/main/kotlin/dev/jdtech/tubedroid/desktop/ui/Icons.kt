package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The phone app's icons, read from its Android vector drawables (copied in at build time), so both
 * apps share one set. Only what those files use is read: paths, groups, fills and strokes.
 */
object AppIcons {
    private val cache = ConcurrentHashMap<String, ImageVector>()

    fun get(name: String): ImageVector = cache.getOrPut(name) { load(name) }

    private fun load(name: String): ImageVector {
        val stream =
            AppIcons::class.java.getResourceAsStream("/icons/$name.xml")
                ?: error("No icon called $name")
        val root = stream.use {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            factory.newDocumentBuilder().parse(it).documentElement
        }
        val builder =
            ImageVector.Builder(
                name = name,
                defaultWidth = root.dp("width", 24f).dp,
                defaultHeight = root.dp("height", 24f).dp,
                viewportWidth = root.float("viewportWidth", 24f),
                viewportHeight = root.float("viewportHeight", 24f),
            )
        addChildren(builder, root)
        return builder.build()
    }

    private fun addChildren(builder: ImageVector.Builder, parent: Element) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            when (node.localName ?: node.tagName) {
                "path" -> addPath(builder, node)
                "group" -> {
                    builder.addGroup(
                        rotate = node.float("rotation", 0f),
                        pivotX = node.float("pivotX", 0f),
                        pivotY = node.float("pivotY", 0f),
                        scaleX = node.float("scaleX", 1f),
                        scaleY = node.float("scaleY", 1f),
                        translationX = node.float("translateX", 0f),
                        translationY = node.float("translateY", 0f),
                    )
                    addChildren(builder, node)
                    builder.clearGroup()
                }
            }
        }
    }

    private fun addPath(builder: ImageVector.Builder, node: Element) {
        val data = node.attr("pathData") ?: return
        val strokeWidth = node.float("strokeWidth", 0f)
        builder.addPath(
            pathData = addPathNodes(data),
            pathFillType =
                if (node.attr("fillType") == "evenOdd") PathFillType.EvenOdd
                else PathFillType.NonZero,
            fill = node.attr("fillColor")?.let(::parseColor)?.let(::SolidColor),
            stroke =
                node
                    .attr("strokeColor")
                    ?.let(::parseColor)
                    ?.takeIf { strokeWidth > 0f }
                    ?.let(::SolidColor),
            strokeLineWidth = strokeWidth,
            strokeLineCap =
                when (node.attr("strokeLineCap")) {
                    "round" -> StrokeCap.Round
                    "square" -> StrokeCap.Square
                    else -> StrokeCap.Butt
                },
            strokeLineJoin =
                when (node.attr("strokeLineJoin")) {
                    "round" -> StrokeJoin.Round
                    "bevel" -> StrokeJoin.Bevel
                    else -> StrokeJoin.Miter
                },
        )
    }

    /**
     * A color from the file, or null for none. Theme colors count as white, as icons are tinted.
     */
    private fun parseColor(value: String): Color? {
        if (!value.startsWith("#")) return Color.White
        val hex = value.removePrefix("#")
        val argb =
            when (hex.length) {
                3 -> "FF" + hex.map { "$it$it" }.joinToString("")
                4 -> hex.map { "$it$it" }.joinToString("")
                6 -> "FF$hex"
                8 -> hex
                else -> return null
            }
        val color = Color(argb.toLong(16).toInt())
        return color.takeIf { it.alpha > 0f }
    }

    private fun Element.attr(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    private fun Element.float(name: String, default: Float): Float =
        attr(name)?.toFloatOrNull() ?: default

    private fun Element.dp(name: String, default: Float): Float =
        attr(name)?.removeSuffix("dp")?.toFloatOrNull() ?: default

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}

/** One of the app's icons in the current content color, or [tint]. */
@Composable
fun AppIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val vector = remember(name) { AppIcons.get(name) }
    Icon(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

/** An icon in its own colors, such as TubeArchivist's logo. */
@Composable
fun AppImage(name: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val vector = remember(name) { AppIcons.get(name) }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/**
 * Winchivist's logo: the ring of TubeArchivist's logo with a screen in the middle, white on dark
 * themes and in the text color on light ones.
 */
@Composable
fun WinchivistLogo(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(modifier = modifier) {
        AppImage(
            name = "ic_ta_logo_ring",
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
        AppIcon(
            name = "ic_winchivist_screen",
            contentDescription = "Winchivist",
            tint = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.matchParentSize(),
        )
    }
}
