package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.AppImage
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.accentSeeds
import dev.jdtech.tubedroid.desktop.ui.errorText
import java.io.File
import javax.swing.JFileChooser
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The app's settings. On the first start it only asks for TubeArchivist's address and token, since
 * nothing else works without them. Doesn't use the page history, so it also works outside the
 * shell.
 */
@Composable
fun SettingsScreen(firstRun: Boolean, onDone: () -> Unit) {
    val settings = LocalApp.current.settings
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (firstRun) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 520.dp).padding(Spacings.large),
                ) {
                    AppImage(
                        name = "ic_ta_logo_mark",
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(Spacings.default))
                    Text("Welcome to Tubedroid", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Spacings.small))
                    Text(
                        text =
                            "Connect to your TubeArchivist server. The API token is on TubeArchivist's " +
                                "settings page, under Application.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacings.large))
                    ServerForm(settings = settings, onSaved = onDone)
                }
            }
        } else {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(title = "Settings", onBack = null)
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacings.default),
                        modifier =
                            Modifier.verticalScroll(scroll)
                                .padding(Spacings.default)
                                .widthIn(max = 760.dp)
                                .fillMaxWidth(),
                    ) {
                        SettingsSection("TubeArchivist") {
                            ServerForm(settings = settings, onSaved = onDone)
                        }
                        SettingsSection("Downloads") { DownloadSettings(settings) }
                        SettingsSection("Playback") { PlaybackSettings(settings) }
                        SettingsSection("Appearance") { AccentPicker(settings) }
                        SettingsSection("About") {
                            Text(
                                text = "Tubedroid for Windows ${appVersion()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Video playback by mpv (libmpv).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ColumnScrollbar(scroll)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier = Modifier.padding(Spacings.default),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

/** The address and token, saved only once TubeArchivist answers with them. */
@Composable
private fun ServerForm(settings: AppSettings, onSaved: () -> Unit) {
    val api = LocalApp.current.api
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(settings.url.orEmpty()) }
    var token by remember { mutableStateOf(settings.token.orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val changed =
        url.trim().trimEnd('/') != settings.url.orEmpty() ||
            token.trim() != settings.token.orEmpty()

    fun connect() {
        if (testing || url.isBlank() || token.isBlank()) return
        testing = true
        status = null
        scope.launch {
            try {
                val version = api.ping(url, token)
                settings.url = url.trim().trimEnd('/')
                settings.token = token.trim()
                failed = false
                status = "Connected" + (version?.let { " to TubeArchivist $it" } ?: "")
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status =
                    if (e is IllegalArgumentException) "That address isn't valid" else errorText(e)
            } finally {
                testing = false
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            placeholder = { Text("https://tube.example.com") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation =
                if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide" else "Show")
                }
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = ::connect,
                enabled = !testing && url.isNotBlank() && token.isNotBlank() && changed,
            ) {
                Text(if (settings.isConfigured) "Save" else "Connect")
            }
            Spacer(Modifier.width(Spacings.medium))
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadSettings(settings: AppSettings) {
    Text(
        text = "The download button saves videos straight to this folder.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = settings.downloadFolder,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                chooseFolder(settings.downloadFolder)?.let { settings.downloadFolder = it.path }
            }
        ) {
            Text("Change")
        }
        Spacer(Modifier.width(Spacings.small))
        TextButton(onClick = { openFolder(File(settings.downloadFolder)) }) { Text("Open") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettings(settings: AppSettings) {
    SwitchRow(
        title = "Autoplay",
        subtitle = "Plays the next video of a playlist when one ends",
        checked = settings.autoplay,
        onChange = { settings.autoplay = it },
    )
    ChoiceRow(
        title = "SponsorBlock",
        subtitle = "For videos TubeArchivist has SponsorBlock segments for",
        options =
            listOf(
                AppSettings.SPONSORBLOCK_BUTTON to "Show a skip button",
                AppSettings.SPONSORBLOCK_AUTO to "Skip automatically",
                AppSettings.SPONSORBLOCK_OFF to "Off",
            ),
        selected = settings.sponsorBlock,
        onSelect = { settings.sponsorBlock = it },
    )
    ChoiceRow(
        title = "Hardware decoding",
        subtitle = "Turn it off if videos show green frames or stutter",
        options = listOf("auto-safe" to "On", "auto-copy-safe" to "Compatible", "no" to "Off"),
        selected = settings.hardwareDecoding,
        onSelect = { settings.hardwareDecoding = it },
    )
    Column {
        Text("mpv settings", style = MaterialTheme.typography.bodyLarge)
        Text(
            text =
                "The player is mpv. Put an mpv.conf, input.conf or scripts in ${settings.mpvConfigFolder.path} " +
                    "to change it further, as with Jellyfin Media Player.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { openFolder(settings.mpvConfigFolder) }) { Text("Open folder") }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                onChange(!checked)
            },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(settings: AppSettings) {
    Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        verticalArrangement = Arrangement.spacedBy(Spacings.small),
    ) {
        accentSeeds.forEach { (name, color) ->
            val selected = settings.accent == name
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.clip(MaterialTheme.shapes.small)
                        .clickable { settings.accent = name }
                        .padding(Spacings.small),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                ) {
                    if (selected)
                        AppIcon(
                            name = "ic_check",
                            contentDescription = "Selected",
                            tint = androidx.compose.ui.graphics.Color.White,
                        )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        if (name == "tubearchivist") "TubeArchivist"
                        else name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun chooseFolder(current: String): File? {
    val chooser =
        JFileChooser(current).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Where to save videos"
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile
    else null
}

internal fun openFolder(folder: File) {
    runCatching {
        folder.mkdirs()
        java.awt.Desktop.getDesktop().open(folder)
    }
}

/** The installed version, which jpackage passes on, or "dev" when run from Gradle. */
private fun appVersion(): String =
    System.getProperty("jpackage.app-version") ?: "(development build)"
