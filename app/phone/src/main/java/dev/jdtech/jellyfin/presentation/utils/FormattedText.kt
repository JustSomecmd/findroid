package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

// A tag such as <br>, <br/> or </i>, or an entity such as &amp; or &#39;
private val htmlPattern =
    Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^<>]*)?/?>|&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")

// A line break right after <br> or a block tag such as <p> or </p>, which already breaks the line
private val newlineAfterTagPattern =
    Regex(
        """(<br\s*/?>|</?(p|div|ul|ol|li|blockquote|h[1-6])(\s[^<>]*)?>)[ \t]*\r?\n""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Descriptions from the server sometimes contain HTML such as `<br>`, `<i>` or `&amp;`. Those are
 * shown formatted; plain text is shown as it is.
 */
@Composable
fun rememberFormattedText(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        if (!htmlPattern.containsMatchIn(text)) {
            AnnotatedString(text)
        } else {
            // HTML ignores plain line breaks, so they are turned into <br> to keep them
            val html =
                text
                    .replace(newlineAfterTagPattern, "$1")
                    .replace("\r\n", "\n")
                    .replace("\n", "<br>")
            AnnotatedString.fromHtml(
                    html,
                    linkStyles =
                        TextLinkStyles(
                            style =
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                )
                        ),
                )
                .trimmed()
        }
    }
}

/** Paragraph tags leave empty lines at the start and end. */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start == -1) return AnnotatedString("")
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return if (start == 0 && end == text.length) this else subSequence(start, end)
}
