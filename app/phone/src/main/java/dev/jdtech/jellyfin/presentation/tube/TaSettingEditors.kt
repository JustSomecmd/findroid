package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings

// Editors for TubeArchivist settings that are strings in a special format, such as a yt-dlp
// format. Options are picked by hand and the string sent to TubeArchivist is built as they are
// picked, shown at the bottom. Each can also be edited by hand.

/**
 * A dialog that builds a value. [built] is what will be sent, null for "not set". With [hand] on,
 * the value is typed instead.
 */
@Composable
private fun TaBuilderDialog(
    title: String,
    built: String?,
    noneLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
    startByHand: Boolean = false,
    content: @Composable () -> Unit,
) {
    var byHand by rememberSaveable { mutableStateOf(startByHand) }
    var handText by rememberSaveable { mutableStateOf(built.orEmpty()) }
    val value = if (byHand) handText.trim().takeIf { it.isNotEmpty() } else built
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // What will be sent, at the top, changing as options are picked below
                TaBuiltValue(value = value, noneLabel = noneLabel, large = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                top = MaterialTheme.spacings.small,
                                bottom = MaterialTheme.spacings.small,
                            ),
                ) {
                    Text(
                        text = stringResource(CoreR.string.ta_edit_by_hand),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = byHand,
                        onCheckedChange = { checked ->
                            if (checked) handText = built.orEmpty()
                            byHand = checked
                        },
                    )
                }
                if (byHand) {
                    OutlinedTextField(
                        value = handText,
                        onValueChange = { handText = it },
                        label = { Text(stringResource(CoreR.string.ta_value_by_hand)) },
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    content()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(CoreR.string.ta_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
    )
}

/** The value as it will be sent to TubeArchivist. */
@Composable
fun TaBuiltValue(value: String?, noneLabel: String, large: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(
                    if (large) MaterialTheme.spacings.medium else MaterialTheme.spacings.small
                )
        ) {
            Text(
                text = stringResource(CoreR.string.ta_sent_to_server),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val base =
                if (large) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyMedium
            Text(
                text = value ?: noneLabel,
                style = if (value != null) base.copy(fontFamily = FontFamily.Monospace) else base,
                color =
                    if (value != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A label above a row of chips, one of which is picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TaChipGroup(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = MaterialTheme.spacings.small)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun TaToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// yt-dlp format (-f)

private data class FormatChoice(
    val height: Int? = null,
    val video: String? = null,
    val audio: String? = null,
    val fallback: Boolean = true,
)

private val videoCodecFilters =
    mapOf("avc1" to "[vcodec*=avc1]", "vp9" to "[vcodec~='^vp0?9']", "av01" to "[vcodec*=av01]")
private val audioCodecFilters = mapOf("mp4a" to "[acodec*=mp4a]", "opus" to "[acodec*=opus]")

private fun buildFormat(choice: FormatChoice): String? {
    if (choice.height == null && choice.video == null && choice.audio == null) return null
    val height = choice.height?.let { "[height<=$it]" }.orEmpty()
    val video = choice.video?.let { videoCodecFilters[it] }.orEmpty()
    val audio = choice.audio?.let { audioCodecFilters[it] }.orEmpty()
    val main = "bestvideo$height$video+bestaudio$audio"
    if (!choice.fallback) return main
    // When the picked codecs aren't there, take the best there is at that height
    return if (video.isNotEmpty() || audio.isNotEmpty()) {
        "$main/bestvideo$height+bestaudio/best$height"
    } else {
        "$main/best$height"
    }
}

/**
 * The choices found in a format, also one written by hand, so they show as picked. Null when
 * nothing in it is one of the options.
 */
private fun readFormat(value: String?): FormatChoice? {
    if (value.isNullOrBlank()) return FormatChoice()
    val height = Regex("""height\s*<=\??\s*(\d+)""").find(value)?.groupValues?.get(1)?.toIntOrNull()
    val video =
        Regex("""vcodec[^\]]*?(avc1|avc|h264|vp0\?9|vp0?9|av01|av1)""")
            .find(value)
            ?.groupValues
            ?.get(1)
            ?.let { codec ->
                when {
                    codec.startsWith("av0") || codec == "av1" -> "av01"
                    codec.startsWith("vp") -> "vp9"
                    else -> "avc1"
                }
            }
    val audio =
        Regex("""acodec[^\]]*?(mp4a|aac|m4a|opus)""").find(value)?.groupValues?.get(1)?.let { codec
            ->
            if (codec == "opus") "opus" else "mp4a"
        }
    if (height == null && video == null && audio == null) return null
    return FormatChoice(
        height = height,
        video = video,
        audio = audio,
        fallback = value.contains("/"),
    )
}

/** Builds a yt-dlp format such as bestvideo[height<=1080][vcodec*=avc1]+bestaudio. */
@Composable
fun TaFormatDialog(
    title: String,
    current: String?,
    noneLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = remember(current) { readFormat(current) }
    var choice by remember { mutableStateOf(parsed ?: FormatChoice()) }
    TaBuilderDialog(
        title = title,
        // The current value stays as it is until something is changed
        built = if (choice == (parsed ?: FormatChoice())) current else buildFormat(choice),
        noneLabel = noneLabel,
        onSave = onSave,
        onDismiss = onDismiss,
        startByHand = parsed == null,
    ) {
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_max_quality),
            options =
                listOf<Pair<Int?, String>>(null to stringResource(CoreR.string.ta_build_best)) +
                    listOf(2160, 1440, 1080, 720, 480, 360).map { it to "${it}p" },
            selected = choice.height,
            onSelect = { choice = choice.copy(height = it) },
        )
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_video_codec),
            options =
                listOf(
                    null to stringResource(CoreR.string.ta_build_any),
                    "avc1" to "H.264",
                    "vp9" to "VP9",
                    "av01" to "AV1",
                ),
            selected = choice.video,
            onSelect = { choice = choice.copy(video = it) },
        )
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_audio_codec),
            options =
                listOf(
                    null to stringResource(CoreR.string.ta_build_any),
                    "mp4a" to "AAC",
                    "opus" to "Opus",
                ),
            selected = choice.audio,
            onSelect = { choice = choice.copy(audio = it) },
        )
        TaToggleRow(
            label = stringResource(CoreR.string.ta_build_fallback),
            checked = choice.fallback,
            onCheckedChange = { choice = choice.copy(fallback = it) },
        )
        Text(
            text = stringResource(CoreR.string.ta_build_format_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// yt-dlp format sorting (-S)

private data class SortChoice(
    val res: Int? = null,
    val fps: Int? = null,
    val video: String? = null,
    val audio: String? = null,
    val smaller: Boolean = false,
)

private fun buildSort(choice: SortChoice): String? =
    listOfNotNull(
            choice.res?.let { "res:$it" },
            choice.fps?.let { "fps:$it" },
            choice.video?.let { "vcodec:$it" },
            choice.audio?.let { "acodec:$it" },
            "+size".takeIf { choice.smaller },
        )
        .joinToString(",")
        .ifEmpty { null }

/** The choices found in a format sort, so they show as picked. Null when none are options. */
private fun readSort(value: String?): SortChoice? {
    if (value.isNullOrBlank()) return SortChoice()
    var choice = SortChoice()
    var found = false
    value
        .split(",")
        .map { it.trim() }
        .forEach { token ->
            val (field, setting) = token.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            when (field) {
                "res" -> setting?.toIntOrNull()?.let { choice = choice.copy(res = it) }
                "fps" -> setting?.toIntOrNull()?.let { choice = choice.copy(fps = it) }
                "vcodec",
                "codec" ->
                    setting?.substringBefore(":")?.let { codec ->
                        choice =
                            choice.copy(
                                video =
                                    when {
                                        codec.startsWith("av0") || codec == "av1" -> "av01"
                                        codec.startsWith("vp") -> "vp9"
                                        else -> "h264"
                                    }
                            )
                    }
                "acodec" ->
                    setting?.let { codec ->
                        choice = choice.copy(audio = if (codec == "opus") "opus" else "aac")
                    }
                "+size" -> choice = choice.copy(smaller = true)
                else -> return@forEach
            }
            found = true
        }
    return choice.takeIf { found }
}

/** Builds a yt-dlp format sort such as res:1080,vcodec:h264. */
@Composable
fun TaFormatSortDialog(
    title: String,
    current: String?,
    noneLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = remember(current) { readSort(current) }
    var choice by remember { mutableStateOf(parsed ?: SortChoice()) }
    TaBuilderDialog(
        title = title,
        // The current value stays as it is until something is changed
        built = if (choice == (parsed ?: SortChoice())) current else buildSort(choice),
        noneLabel = noneLabel,
        onSave = onSave,
        onDismiss = onDismiss,
        startByHand = parsed == null,
    ) {
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_prefer_quality),
            options =
                withCurrent(
                    listOf<Pair<Int?, String>>(null to stringResource(CoreR.string.ta_build_best)) +
                        listOf(2160, 1440, 1080, 720, 480).map { it to "${it}p" },
                    choice.res,
                ) {
                    "${it}p"
                },
            selected = choice.res,
            onSelect = { choice = choice.copy(res = it) },
        )
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_prefer_fps),
            options =
                withCurrent(
                    listOf<Pair<Int?, String>>(null to stringResource(CoreR.string.ta_build_any)) +
                        listOf(60, 30).map { it to "$it fps" },
                    choice.fps,
                ) {
                    "$it fps"
                },
            selected = choice.fps,
            onSelect = { choice = choice.copy(fps = it) },
        )
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_prefer_video_codec),
            options =
                withCurrent(
                    listOf(
                        null to stringResource(CoreR.string.ta_build_any),
                        "h264" to "H.264",
                        "vp9" to "VP9",
                        "av01" to "AV1",
                    ),
                    choice.video,
                ) {
                    it
                },
            selected = choice.video,
            onSelect = { choice = choice.copy(video = it) },
        )
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_prefer_audio_codec),
            options =
                withCurrent(
                    listOf(
                        null to stringResource(CoreR.string.ta_build_any),
                        "aac" to "AAC",
                        "opus" to "Opus",
                    ),
                    choice.audio,
                ) {
                    it
                },
            selected = choice.audio,
            onSelect = { choice = choice.copy(audio = it) },
        )
        TaToggleRow(
            label = stringResource(CoreR.string.ta_build_smaller_files),
            checked = choice.smaller,
            onCheckedChange = { choice = choice.copy(smaller = it) },
        )
    }
}

/** Adds the current value to the options when it isn't one of them, so it can stay picked. */
private fun <T> withCurrent(
    options: List<Pair<T, String>>,
    current: T,
    label: (T & Any) -> String,
): List<Pair<T, String>> {
    if (current == null || options.any { it.first == current }) return options
    return options + (current to label(current))
}

// Languages

private val languages =
    listOf(
        "en" to "English",
        "nl" to "Nederlands",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "pt" to "Português",
        "pl" to "Polski",
        "sv" to "Svenska",
        "da" to "Dansk",
        "no" to "Norsk",
        "fi" to "Suomi",
        "tr" to "Türkçe",
        "ru" to "Русский",
        "uk" to "Українська",
        "ar" to "العربية",
        "hi" to "हिन्दी",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
    )

/**
 * Picks languages by their codes, such as en,nl. [multiple] allows more than one, kept in the order
 * they were picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaLanguagesDialog(
    title: String,
    current: String?,
    multiple: Boolean,
    noneLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = remember {
        current
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
            .toMutableStateList()
    }
    var other by rememberSaveable { mutableStateOf("") }
    val options =
        languages + picked.filter { code -> languages.none { it.first == code } }.map { it to it }
    TaBuilderDialog(
        title = title,
        built = picked.joinToString(",").ifEmpty { null },
        noneLabel = noneLabel,
        onSave = onSave,
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            options.forEach { (code, name) ->
                FilterChip(
                    selected = code in picked,
                    onClick = {
                        if (code in picked) {
                            picked.remove(code)
                        } else {
                            if (!multiple) picked.clear()
                            picked.add(code)
                        }
                    },
                    label = { Text(if (code == name) code else "$name ($code)") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = other,
                onValueChange = { other = it.trim() },
                label = { Text(stringResource(CoreR.string.ta_build_other_language)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (other.isNotEmpty() && other !in picked) {
                        if (!multiple) picked.clear()
                        picked.add(other)
                    }
                    other = ""
                },
                enabled = other.isNotEmpty(),
            ) {
                Text(stringResource(CoreR.string.add))
            }
        }
    }
}

// Comments to save: max-comments,max-parents,max-replies,max-replies-per-thread,max-depth

private val commentParts =
    listOf(
        CoreR.string.ta_build_comments_total to listOf(1000L, 500L, 200L, 100L, 50L),
        CoreR.string.ta_build_comments_top to listOf(100L, 50L, 20L, 10L),
        CoreR.string.ta_build_comments_replies to listOf(100L, 50L, 10L, 0L),
        CoreR.string.ta_build_comments_per_thread to listOf(50L, 20L, 10L, 5L, 0L),
        CoreR.string.ta_build_comments_depth to listOf(3L, 2L, 1L),
    )

/** Builds how many comments to save, such as all,100,all,30,all. */
@Composable
fun TaCommentsDialog(
    title: String,
    current: String?,
    noneLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentParts = current?.split(",")?.map { it.trim() }
    val parsed = currentParts?.takeIf { parts ->
        parts.size <= commentParts.size && parts.all { it == "all" || it.toLongOrNull() != null }
    }
    var enabled by remember { mutableStateOf(!current.isNullOrBlank()) }
    // Null is all
    val initialValues = remember {
        commentParts.indices.map { index -> parsed?.getOrNull(index)?.toLongOrNull() }
    }
    val values = remember { initialValues.toMutableStateList() }
    val unchanged = enabled == !current.isNullOrBlank() && values.toList() == initialValues
    TaBuilderDialog(
        title = title,
        // The current value stays as it is until something is changed
        built =
            when {
                unchanged -> current
                !enabled -> null
                else -> values.joinToString(",") { it?.toString() ?: "all" }
            },
        noneLabel = noneLabel,
        onSave = onSave,
        onDismiss = onDismiss,
        startByHand = current != null && parsed == null,
    ) {
        TaToggleRow(
            label = stringResource(CoreR.string.ta_build_save_comments),
            checked = enabled,
            onCheckedChange = { enabled = it },
        )
        if (enabled) {
            commentParts.forEachIndexed { index, (label, presets) ->
                TaChipGroup(
                    label = stringResource(label),
                    options =
                        withCurrent(
                            listOf<Pair<Long?, String>>(
                                null to stringResource(CoreR.string.ta_build_all)
                            ) + presets.map { it to it.toString() },
                            values[index],
                        ) {
                            it.toString()
                        },
                    selected = values[index],
                    onSelect = { values[index] = it },
                )
            }
        }
    }
}

// Schedules: minute hour weekday

private enum class ScheduleKind {
    OFF,
    DAILY,
    HOURS,
    DAYS,
}

private val weekdays = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
private val weekdayNumbers =
    mapOf(
        "0" to "sun",
        "1" to "mon",
        "2" to "tue",
        "3" to "wed",
        "4" to "thu",
        "5" to "fri",
        "6" to "sat",
        "7" to "sun",
    )

private data class ScheduleChoice(
    val kind: ScheduleKind,
    val hour: Int = 8,
    val minute: Int = 0,
    val everyHours: Int = 6,
    val days: List<String> = emptyList(),
)

private fun buildSchedule(choice: ScheduleChoice): String? =
    when (choice.kind) {
        ScheduleKind.OFF -> null
        ScheduleKind.DAILY -> "${choice.minute} ${choice.hour} *"
        ScheduleKind.HOURS -> "${choice.minute} */${choice.everyHours} *"
        ScheduleKind.DAYS ->
            if (choice.days.isEmpty()) null
            else
                "${choice.minute} ${choice.hour} " +
                    weekdays.filter { it in choice.days }.joinToString(",")
    }

private fun parseSchedule(value: String?): ScheduleChoice? {
    if (value.isNullOrBlank()) return ScheduleChoice(ScheduleKind.OFF)
    val fields = value.trim().split(Regex("""\s+"""))
    if (fields.size != 3) return null
    val minute = fields[0].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val (hourField, dayField) = fields[1] to fields[2]
    if (hourField.startsWith("*/") && dayField == "*") {
        val every = hourField.removePrefix("*/").toIntOrNull() ?: return null
        return ScheduleChoice(ScheduleKind.HOURS, minute = minute, everyHours = every)
    }
    val hour = hourField.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    if (dayField == "*") return ScheduleChoice(ScheduleKind.DAILY, hour = hour, minute = minute)
    val days =
        dayField.split(",").map { day ->
            val name = day.trim().lowercase()
            weekdayNumbers[name] ?: name.take(3).takeIf { it in weekdays } ?: return null
        }
    return ScheduleChoice(ScheduleKind.DAYS, hour = hour, minute = minute, days = days)
}

/** Builds when a task runs, such as 0 8 * for every day at 8:00. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaScheduleDialog(
    title: String,
    current: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = remember(current) { parseSchedule(current) }
    var choice by remember { mutableStateOf(parsed ?: ScheduleChoice(ScheduleKind.DAILY)) }
    val timeState =
        rememberTimePickerState(
            initialHour = choice.hour,
            initialMinute = choice.minute,
            is24Hour = true,
        )
    // Every few hours only needs the minutes past the hour, picked below
    val withTime =
        if (choice.kind == ScheduleKind.HOURS) choice
        else choice.copy(hour = timeState.hour, minute = timeState.minute)
    TaBuilderDialog(
        title = title,
        built = if (withTime == parsed) current else buildSchedule(withTime),
        noneLabel = stringResource(CoreR.string.ta_not_scheduled),
        onSave = onSave,
        onDismiss = onDismiss,
        startByHand = parsed == null,
    ) {
        TaChipGroup(
            label = stringResource(CoreR.string.ta_build_runs),
            options =
                listOf(
                    ScheduleKind.OFF to stringResource(CoreR.string.ta_build_never),
                    ScheduleKind.DAILY to stringResource(CoreR.string.ta_build_daily),
                    ScheduleKind.HOURS to stringResource(CoreR.string.ta_build_every_hours),
                    ScheduleKind.DAYS to stringResource(CoreR.string.ta_build_on_days),
                ),
            selected = choice.kind,
            onSelect = { choice = choice.copy(kind = it) },
        )
        when (choice.kind) {
            ScheduleKind.OFF -> Unit
            ScheduleKind.HOURS -> {
                TaChipGroup(
                    label = stringResource(CoreR.string.ta_build_every),
                    options =
                        withCurrent(
                            listOf(1, 2, 3, 4, 6, 8, 12).map { it to "$it h" },
                            choice.everyHours,
                        ) {
                            "$it h"
                        },
                    selected = choice.everyHours,
                    onSelect = { choice = choice.copy(everyHours = it) },
                )
                TaChipGroup(
                    label = stringResource(CoreR.string.ta_build_at_minute),
                    options =
                        withCurrent(
                            listOf(0, 15, 30, 45).map { it to ":%02d".format(it) },
                            choice.minute,
                        ) {
                            ":%02d".format(it)
                        },
                    selected = choice.minute,
                    onSelect = { choice = choice.copy(minute = it) },
                )
            }
            ScheduleKind.DAILY,
            ScheduleKind.DAYS -> {
                if (choice.kind == ScheduleKind.DAYS) {
                    Text(
                        text = stringResource(CoreR.string.ta_build_days),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                    ) {
                        weekdays.forEach { day ->
                            FilterChip(
                                selected = day in choice.days,
                                onClick = {
                                    choice =
                                        choice.copy(
                                            days =
                                                if (day in choice.days) choice.days - day
                                                else choice.days + day
                                        )
                                },
                                label = { Text(day.replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(CoreR.string.ta_build_time),
                    style = MaterialTheme.typography.titleSmall,
                )
                TimeInput(state = timeState)
            }
        }
    }
}

// Numbers

/**
 * Picks a number from common values or types one. [noneLabel] is for no value, such as "Use the
 * global setting".
 */
@Composable
fun TaNumberDialog(
    title: String,
    current: Long?,
    presets: List<Long>,
    noneLabel: String?,
    unit: String?,
    onSave: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    var typed by rememberSaveable { mutableStateOf("") }
    val format: (Long) -> String = { number -> if (unit != null) "$number $unit" else "$number" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TaChipGroup(
                    label = stringResource(CoreR.string.ta_build_pick),
                    options =
                        withCurrent(
                            listOfNotNull<Pair<Long?, String>>(noneLabel?.let { null to it }) +
                                presets.map { it to format(it) },
                            value,
                        ) {
                            format(it)
                        },
                    selected = value,
                    onSelect = {
                        value = it
                        typed = ""
                    },
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text ->
                        typed = text.filter { it.isDigit() }
                        typed.toLongOrNull()?.let { value = it }
                    },
                    label = { Text(stringResource(CoreR.string.ta_build_other_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MaterialTheme.spacings.medium))
                TaBuiltValue(
                    value = value?.toString(),
                    noneLabel = noneLabel ?: stringResource(CoreR.string.ta_not_set),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value != null || noneLabel != null) {
                Text(stringResource(CoreR.string.ta_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
    )
}

/** Picks one of a few values. */
@Composable
fun <T> TaChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    current: T,
    onSave: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    FilterChip(
                        selected = value == current,
                        onClick = { onSave(value) },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
    )
}
