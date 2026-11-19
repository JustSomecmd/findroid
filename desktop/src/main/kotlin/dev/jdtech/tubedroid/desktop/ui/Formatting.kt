package dev.jdtech.tubedroid.desktop.ui

import dev.jdtech.jellyfin.tubearchivist.TaApiException
import java.io.IOException
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** 1234 as 1.2K, 3400000 as 3.4M. */
fun formatCount(count: Long): String =
    when {
        count >= 1_000_000_000 -> trimDecimal(count / 1_000_000_000.0) + "B"
        count >= 1_000_000 -> trimDecimal(count / 1_000_000.0) + "M"
        count >= 1_000 -> trimDecimal(count / 1_000.0) + "K"
        else -> count.toString()
    }

private fun trimDecimal(value: Double): String =
    if (value >= 100) value.toLong().toString()
    else String.format(Locale.getDefault(), "%.1f", value).trimEnd('0').trimEnd('.', ',')

/** 5890 as 5,890. */
fun formatNumber(count: Long): String = NumberFormat.getIntegerInstance().format(count)

/** Seconds as 4:05 or 1:02:03. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

/** A long playing time by its two largest units: 137d 10h, 19h 19m, 45m or 30s. */
fun formatPlaytime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/**
 * A size as TubeArchivist shows it: 643.0 GB in metric units (powers of 1000), or 598.9 GiB in
 * binary ones (powers of 1024).
 */
fun formatSize(bytes: Long, metric: Boolean): String {
    val step = if (metric) 1000.0 else 1024.0
    if (abs(bytes) < step) return "$bytes B"
    val units =
        if (metric) listOf("kB", "MB", "GB", "TB", "PB", "EB")
        else listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= step
        unit++
    } while (round(abs(value) * 10) / 10 >= step && unit < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun parseDate(iso: String?): OffsetDateTime? =
    try {
        iso?.let { OffsetDateTime.parse(it) }
    } catch (e: Exception) {
        null
    }

/** "3 days ago", "2 months ago". */
fun relativeDate(iso: String?): String? {
    val date = parseDate(iso) ?: return null
    val days = Duration.between(date.toInstant(), Instant.now()).toDays()
    fun ago(count: Long, unit: String) = "$count $unit${if (count == 1L) "" else "s"} ago"
    return when {
        days < 1 -> "Today"
        days < 2 -> "Yesterday"
        days < 7 -> ago(days, "day")
        days < 31 -> ago(days / 7, "week")
        days < 365 -> ago(days / 30, "month")
        else -> ago(days / 365, "year")
    }
}

/** "18 Nov 2026" in the computer's own date format. */
fun fullDate(iso: String?): String? {
    val date = parseDate(iso) ?: return null
    return date.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

/** A date TubeArchivist sends as text or as seconds since 1970. */
fun anyDate(value: String?): String? {
    if (value == null) return null
    fullDate(value)?.let {
        return it
    }
    val seconds = value.toLongOrNull() ?: return value
    return fullDate(Instant.ofEpochSecond(seconds).atOffset(java.time.ZoneOffset.UTC).toString())
}

/** What went wrong, in words for the page. */
fun errorText(e: Throwable): String =
    when (e) {
        is TaApiException ->
            when (e.code) {
                401,
                403 -> "TubeArchivist didn't accept the token. Check it in Settings."
                404 -> "TubeArchivist doesn't have this."
                else -> "TubeArchivist answered with error ${e.code}."
            }
        is IOException -> "Can't reach TubeArchivist. Check the address in Settings."
        else -> e.message ?: e.toString()
    }
