package dev.jdtech.jellyfin.presentation.tube

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaSettingsViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaSchedule
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaSettingsScreen(navigateBack: () -> Unit, viewModel: TaSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showToken by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaSetting?>(null) }
    var editingSchedule by remember { mutableStateOf<Pair<String, String?>?>(null) }

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_SHORT).show()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.ta_tubearchivist)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(CoreR.string.ta_connection),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(CoreR.string.ta_connection_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text(stringResource(CoreR.string.ta_server_address)) },
                placeholder = { Text("http://192.168.1.10:8000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::onTokenChange,
                label = { Text(stringResource(CoreR.string.ta_api_token)) },
                singleLine = true,
                visualTransformation =
                    if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            painter =
                                painterResource(
                                    if (showToken) CoreR.drawable.ic_eye_off
                                    else CoreR.drawable.ic_eye
                                ),
                            contentDescription = null,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(stringResource(CoreR.string.ta_api_token_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            state.connectionError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.connected && state.connectionError == null) {
                Text(
                    text =
                        state.version?.let { stringResource(CoreR.string.ta_connected_version, it) }
                            ?: stringResource(CoreR.string.ta_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                Button(
                    onClick = { viewModel.connect() },
                    enabled = !state.checking && state.url.isNotBlank() && state.token.isNotBlank(),
                ) {
                    if (state.checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(CoreR.string.ta_connect))
                    }
                }
                if (state.connected) {
                    OutlinedButton(onClick = { viewModel.disconnect() }) {
                        Text(stringResource(CoreR.string.ta_disconnect))
                    }
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Text(
                text = stringResource(CoreR.string.ta_sponsorblock),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(CoreR.string.ta_sponsorblock_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                    "button" to CoreR.string.ta_sponsorblock_button,
                    "auto" to CoreR.string.ta_sponsorblock_auto,
                    "off" to CoreR.string.ta_sponsorblock_off,
                )
                .forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = state.sponsorBlock == mode,
                                    onClick = { viewModel.setSponsorBlock(mode) },
                                    role = Role.RadioButton,
                                ),
                    ) {
                        RadioButton(selected = state.sponsorBlock == mode, onClick = null)
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = MaterialTheme.spacings.small),
                        )
                    }
                }

            if (state.connected) {
                state.serverSettingsError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.config?.let { config ->
                    TaServerSettings(
                        config = config,
                        onChange = viewModel::setConfigValue,
                        onEdit = { editing = it },
                    )
                }
                if (state.config != null) {
                    TaSchedules(
                        schedules = state.schedules,
                        onEdit = { name, current -> editingSchedule = name to current },
                    )
                }
            }
        }
    }

    editing?.let { setting ->
        val config = state.config
        val current = config?.let { setting.read(it) }
        TaSettingEditDialog(
            setting = setting,
            current = current,
            onSave = { value ->
                viewModel.setConfigValue(setting.section, setting.key, value)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    editingSchedule?.let { (name, current) ->
        TaTextDialog(
            title = stringResource(scheduleLabel(name)),
            label = stringResource(CoreR.string.ta_schedule_hint),
            initial = current.orEmpty(),
            onSave = { text ->
                viewModel.setSchedule(name, text)
                editingSchedule = null
            },
            onDismiss = { editingSchedule = null },
        )
    }
}

private enum class TaSettingType {
    BOOL,
    INT,
    TEXT,
    CHOICE,
}

/** One of TubeArchivist's settings, as found in its config under [section] / [key]. */
private data class TaSetting(
    val section: String,
    val key: String,
    @param:StringRes val label: Int,
    val type: TaSettingType,
    @param:StringRes val hint: Int? = null,
    /** For CHOICE: the value (null for none) and its label. */
    val options: List<Pair<String?, Int>> = emptyList(),
) {
    fun read(config: JsonObject): JsonElement? = (config[section] as? JsonObject)?.get(key)
}

private val subscriptionSettings =
    listOf(
        TaSetting(
            "subscriptions",
            "channel_size",
            CoreR.string.ta_set_channel_size,
            TaSettingType.INT,
        ),
        TaSetting(
            "subscriptions",
            "live_channel_size",
            CoreR.string.ta_set_live_channel_size,
            TaSettingType.INT,
        ),
        TaSetting(
            "subscriptions",
            "shorts_channel_size",
            CoreR.string.ta_set_shorts_channel_size,
            TaSettingType.INT,
        ),
        TaSetting(
            "subscriptions",
            "playlist_size",
            CoreR.string.ta_set_playlist_size,
            TaSettingType.INT,
        ),
        TaSetting(
            "subscriptions",
            "auto_start",
            CoreR.string.ta_set_auto_start,
            TaSettingType.BOOL,
        ),
        TaSetting(
            "subscriptions",
            "extract_flat",
            CoreR.string.ta_set_extract_flat,
            TaSettingType.BOOL,
        ),
    )

private val downloadSettings =
    listOf(
        TaSetting("downloads", "limit_speed", CoreR.string.ta_set_limit_speed, TaSettingType.INT),
        TaSetting(
            "downloads",
            "sleep_interval",
            CoreR.string.ta_set_sleep_interval,
            TaSettingType.INT,
        ),
        TaSetting(
            "downloads",
            "autodelete_days",
            CoreR.string.ta_set_autodelete_days,
            TaSettingType.INT,
        ),
        TaSetting(
            "downloads",
            "format",
            CoreR.string.ta_set_format,
            TaSettingType.TEXT,
            hint = CoreR.string.ta_set_format_hint,
        ),
        TaSetting(
            "downloads",
            "format_sort",
            CoreR.string.ta_set_format_sort,
            TaSettingType.TEXT,
            hint = CoreR.string.ta_set_format_sort_hint,
        ),
        TaSetting(
            "downloads",
            "add_metadata",
            CoreR.string.ta_set_add_metadata,
            TaSettingType.BOOL,
        ),
        TaSetting(
            "downloads",
            "subtitle",
            CoreR.string.ta_set_subtitle,
            TaSettingType.TEXT,
            hint = CoreR.string.ta_set_subtitle_hint,
        ),
        TaSetting(
            "downloads",
            "subtitle_source",
            CoreR.string.ta_set_subtitle_source,
            TaSettingType.CHOICE,
            options =
                listOf(
                    null to CoreR.string.ta_option_none,
                    "user" to CoreR.string.ta_option_subtitle_user,
                    "auto" to CoreR.string.ta_option_subtitle_auto,
                ),
        ),
        TaSetting(
            "downloads",
            "subtitle_index",
            CoreR.string.ta_set_subtitle_index,
            TaSettingType.BOOL,
        ),
        TaSetting(
            "downloads",
            "comment_max",
            CoreR.string.ta_set_comment_max,
            TaSettingType.TEXT,
            hint = CoreR.string.ta_set_comment_max_hint,
        ),
        TaSetting(
            "downloads",
            "comment_sort",
            CoreR.string.ta_set_comment_sort,
            TaSettingType.CHOICE,
            options =
                listOf(
                    "top" to CoreR.string.ta_option_comments_top,
                    "new" to CoreR.string.ta_option_comments_new,
                ),
        ),
        TaSetting(
            "downloads",
            "integrate_sponsorblock",
            CoreR.string.ta_set_integrate_sponsorblock,
            TaSettingType.BOOL,
        ),
        TaSetting(
            "downloads",
            "integrate_ryd",
            CoreR.string.ta_set_integrate_ryd,
            TaSettingType.BOOL,
        ),
        TaSetting(
            "downloads",
            "extractor_lang",
            CoreR.string.ta_set_extractor_lang,
            TaSettingType.TEXT,
            hint = CoreR.string.ta_set_extractor_lang_hint,
        ),
    )

private val applicationSettings =
    listOf(
        TaSetting(
            "application",
            "enable_snapshot",
            CoreR.string.ta_set_enable_snapshot,
            TaSettingType.BOOL,
        )
    )

@Composable
private fun TaServerSettings(
    config: JsonObject,
    onChange: (section: String, key: String, value: JsonElement) -> Unit,
    onEdit: (TaSetting) -> Unit,
) {
    listOf(
            CoreR.string.ta_section_subscriptions to subscriptionSettings,
            CoreR.string.ta_section_downloads to downloadSettings,
            CoreR.string.ta_section_application to applicationSettings,
        )
        .forEach { (title, settings) ->
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            settings
                .filter { setting ->
                    (config[setting.section] as? JsonObject)?.containsKey(setting.key) == true
                }
                .forEach { setting ->
                    val value = setting.read(config)
                    if (setting.type == TaSettingType.BOOL) {
                        val checked = (value as? JsonPrimitive)?.booleanOrNull == true
                        TaSettingRow(
                            label = stringResource(setting.label),
                            value = null,
                            onClick = {
                                onChange(setting.section, setting.key, JsonPrimitive(!checked))
                            },
                            trailing = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = {
                                        onChange(setting.section, setting.key, JsonPrimitive(it))
                                    },
                                )
                            },
                        )
                    } else {
                        TaSettingRow(
                            label = stringResource(setting.label),
                            value = settingValueText(setting, value),
                            onClick = { onEdit(setting) },
                        )
                    }
                }
        }
}

@Composable
private fun TaSchedules(schedules: List<TaSchedule>, onEdit: (String, String?) -> Unit) {
    Spacer(Modifier.height(MaterialTheme.spacings.medium))
    Text(
        text = stringResource(CoreR.string.ta_section_schedules),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    val names =
        (listOf("update_subscribed", "download_pending") + schedules.map { it.name }).distinct()
    names.forEach { name ->
        val schedule = schedules.firstOrNull { it.name == name }
        TaSettingRow(
            label = stringResource(scheduleLabel(name)),
            value =
                schedule?.let { it.scheduleHuman ?: it.schedule }
                    ?: stringResource(CoreR.string.ta_not_scheduled),
            onClick = { onEdit(name, schedule?.schedule) },
        )
    }
}

@StringRes
private fun scheduleLabel(name: String): Int =
    when (name) {
        "update_subscribed" -> CoreR.string.ta_rescan_subscriptions
        "download_pending" -> CoreR.string.ta_start_downloading
        "check_reindex" -> CoreR.string.ta_task_reindex
        "thumbnail_check" -> CoreR.string.ta_task_thumbnails
        "run_backup" -> CoreR.string.ta_task_backup
        "version_check" -> CoreR.string.ta_task_version
        else -> CoreR.string.ta_task_other
    }

@Composable
private fun settingValueText(setting: TaSetting, value: JsonElement?): String {
    val primitive = value as? JsonPrimitive
    val content = if (primitive == null || primitive is JsonNull) null else primitive.contentOrNull
    if (setting.type == TaSettingType.CHOICE) {
        val option = setting.options.firstOrNull { it.first == content }
        if (option != null) return stringResource(option.second)
    }
    return content?.takeIf { it.isNotBlank() } ?: stringResource(CoreR.string.ta_not_set)
}

@Composable
private fun TaSettingRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = MaterialTheme.spacings.small),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun TaSettingEditDialog(
    setting: TaSetting,
    current: JsonElement?,
    onSave: (JsonElement) -> Unit,
    onDismiss: () -> Unit,
) {
    val primitive = current as? JsonPrimitive
    val currentText =
        if (primitive == null || primitive is JsonNull) null else primitive.contentOrNull
    if (setting.type == TaSettingType.CHOICE) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(setting.label)) },
            text = {
                Column {
                    setting.options.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = currentText == value,
                                        onClick = {
                                            onSave(value?.let { JsonPrimitive(it) } ?: JsonNull)
                                        },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = MaterialTheme.spacings.small),
                        ) {
                            RadioButton(selected = currentText == value, onClick = null)
                            Text(
                                text = stringResource(label),
                                modifier = Modifier.padding(start = MaterialTheme.spacings.small),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
            },
        )
        return
    }
    TaTextDialog(
        title = stringResource(setting.label),
        label =
            setting.hint?.let { stringResource(it) }
                ?: stringResource(CoreR.string.ta_empty_to_clear),
        initial = currentText.orEmpty(),
        numeric = setting.type == TaSettingType.INT,
        onSave = { text ->
            val trimmed = text.trim()
            val value =
                when {
                    trimmed.isEmpty() -> JsonNull
                    setting.type == TaSettingType.INT ->
                        trimmed.toLongOrNull()?.let { JsonPrimitive(it) } ?: return@TaTextDialog
                    else -> JsonPrimitive(trimmed)
                }
            onSave(value)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun TaTextDialog(
    title: String,
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    numeric: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(CoreR.string.ta_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
    )
}
