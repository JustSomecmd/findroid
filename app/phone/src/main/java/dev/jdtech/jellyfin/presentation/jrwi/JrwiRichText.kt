package dev.jdtech.jellyfin.presentation.jrwi

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.text.HtmlCompat
import java.util.Locale

/**
 * A post's or comment's text as the archive's web app shows it: each paragraph and heading apart,
 * [gap] between them, headings in capitals and larger, lists with their bullets or numbers, and
 * links in the accent colour. Without [html], the plain [text] is split into paragraphs at its
 * empty lines.
 */
@Composable
internal fun JrwiHtmlText(
    html: String?,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    gap: Dp = JRWI_PARAGRAPH_GAP,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val paragraphs =
        remember(html, text, linkColor) {
            if (html != null) {
                jrwiHtmlParagraphs(html, TextLinkStyles(style = SpanStyle(color = linkColor)))
            } else {
                text
                    .split(BLANK_LINE)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { JrwiParagraph(AnnotatedString(it)) }
            }
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        paragraphs.forEach { paragraph ->
            val scale = paragraph.heading
            Text(
                text = paragraph.text,
                style =
                    if (scale == null) style
                    else
                        style.copy(
                            fontSize = style.fontSize * scale,
                            lineHeight = style.fontSize * (scale * HEADING_LINE_HEIGHT),
                        ),
            )
        }
    }
}

/** One paragraph of a post, or a heading with its size against the text's. */
internal data class JrwiParagraph(val text: AnnotatedString, val heading: Float? = null)

/**
 * Splits the HTML into its paragraphs. Android's parser reads it, keeping an empty line between
 * blocks and a single line break between list items, as the web app's margins do; pictures in the
 * text are left out, as the post's pictures are shown under it.
 */
internal fun jrwiHtmlParagraphs(html: String, links: TextLinkStyles): List<JrwiParagraph> {
    val prepared = markListItems(html.replace(IMAGE_TAG, ""))
    val spanned = HtmlCompat.fromHtml(prepared, HtmlCompat.FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM)
    val (text, headings) = withHeadings(spanned)
    val annotated = spanned.toAnnotatedString(text, links)
    val paragraphs = mutableListOf<JrwiParagraph>()
    var start = 0
    fun add(end: Int) {
        var from = start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (from < to) {
            val heading = headings.firstOrNull { it.start <= from && it.end >= to }
            paragraphs += JrwiParagraph(annotated.subSequence(from, to), heading?.scale)
        }
    }
    BLANK_LINE.findAll(text).forEach { breakAt ->
        add(breakAt.range.first)
        start = breakAt.range.last + 1
    }
    add(text.length)
    return paragraphs
}

private class Heading(val start: Int, val end: Int, val scale: Float)

/**
 * The text with its headings in capitals, as the web app shows h1 to h4, and where the headings
 * are. Android's parser marks a heading with a size and bold over the same letters; the sizes are
 * those of the web app's stylesheet.
 */
private fun withHeadings(spanned: Spanned): Pair<String, List<Heading>> {
    val chars = spanned.toString().toCharArray()
    val headings = mutableListOf<Heading>()
    spanned.getSpans(0, spanned.length, RelativeSizeSpan::class.java).forEach { span ->
        val level = ANDROID_HEADING_SIZES.indexOf(span.sizeChange)
        if (level < 0) return@forEach
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        val bold =
            spanned.getSpans(start, end, StyleSpan::class.java).any {
                it.style == Typeface.BOLD &&
                    spanned.getSpanStart(it) == start &&
                    spanned.getSpanEnd(it) == end
            }
        if (!bold || start < 0 || end <= start) return@forEach
        headings += Heading(start, end, WEB_HEADING_SIZES[level])
        if (level < UPPERCASE_LEVELS) {
            val upper = String(chars, start, end - start).uppercase(Locale.getDefault())
            // Only where it keeps its length, so the styles stay on their letters
            if (upper.length == end - start) upper.toCharArray().copyInto(chars, start)
        }
    }
    return String(chars) to headings
}

private fun Spanned.toAnnotatedString(text: String, links: TextLinkStyles): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val spanned = this@toAnnotatedString
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            when (span) {
                is StyleSpan ->
                    when (span.style) {
                        Typeface.BOLD ->
                            addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                        Typeface.ITALIC ->
                            addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                        Typeface.BOLD_ITALIC ->
                            addStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontStyle = FontStyle.Italic,
                                ),
                                start,
                                end,
                            )
                    }
                is UnderlineSpan ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is StrikethroughSpan ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                is URLSpan -> span.url?.let { addLink(LinkAnnotation.Url(it, links), start, end) }
                // Headings are sized as whole paragraphs; <big> and <small> keep their size
                is RelativeSizeSpan ->
                    if (span.sizeChange !in ANDROID_HEADING_SIZES) {
                        addStyle(SpanStyle(fontSize = span.sizeChange.em), start, end)
                    }
            }
        }
    }

/**
 * Puts each list item's bullet, or its number in a numbered list, in front of its text, as the web
 * app shows them; Android's parser only indents them.
 */
private fun markListItems(html: String): String {
    // A paragraph right inside an item would put the bullet on a line of its own
    val items = html.replace(ITEM_PARAGRAPH_START, "$1").replace(ITEM_PARAGRAPH_END, "</li>")
    // For each list open around this point: whether it's numbered, and how many items it has had
    val lists = ArrayDeque<IntArray>()
    return LIST_TAG.replace(items) { match ->
        val closing = match.groupValues[1] == "/"
        when (match.groupValues[2].lowercase()) {
            "ul",
            "ol" -> {
                if (closing) lists.removeLastOrNull()
                else {
                    val numbered = match.groupValues[2].equals("ol", ignoreCase = true)
                    lists.addLast(intArrayOf(if (numbered) 1 else 0, 0))
                }
                match.value
            }
            else ->
                if (closing) match.value
                else {
                    val list = lists.lastOrNull()
                    val mark =
                        if (list != null && list[0] == 1) {
                            list[1]++
                            "${list[1]}. "
                        } else {
                            "• "
                        }
                    match.value + mark
                }
        }
    }
}

/** The space between paragraphs, as the web app's 0.8 rem under each. */
internal val JRWI_PARAGRAPH_GAP = 12.dp

/** Between a comment's paragraphs, as the web app's 0.4 rem. */
internal val JRWI_COMMENT_PARAGRAPH_GAP = 6.dp

// Two line breaks or more, with nothing but spaces between them: where a paragraph ends
private val BLANK_LINE = Regex("""\n[ \t ]*\n\s*""")

// Pictures in the text, shown under the post instead
private val IMAGE_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)

// A list or a list item opening or closing
private val LIST_TAG = Regex("""<(/?)(ul|ol|li)\b[^>]*>""", RegexOption.IGNORE_CASE)

// A paragraph opening right inside a list item, and closing right before its end
private val ITEM_PARAGRAPH_START = Regex("""(<li\b[^>]*>)\s*<p\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ITEM_PARAGRAPH_END = Regex("""</p>\s*</li>""", RegexOption.IGNORE_CASE)

// The sizes Android's parser gives h1 to h6
private val ANDROID_HEADING_SIZES = listOf(1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1f)

// The web app's: 2, 1.5, 1.1 and 1 rem for h1 to h4, against text of 1 rem
private val WEB_HEADING_SIZES = listOf(2f, 1.5f, 1.1f, 1f, 1f, 1f)

// The web app shows h1 to h4 in capitals
private const val UPPERCASE_LEVELS = 4

// The web app's line height for headings
private const val HEADING_LINE_HEIGHT = 1.15f
