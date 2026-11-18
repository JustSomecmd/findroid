package dev.jdtech.jellyfin.presentation.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.darkScheme
import dev.jdtech.jellyfin.presentation.theme.lightScheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect

@Composable
fun SettingsSelectDialog(
    preference: PreferenceSelect,
    options: List<Pair<String?, String>>,
    onUpdate: (value: String?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    // Colors next to the options, when the preference has them
    val swatches =
        preference.optionColors?.let { colorsRes ->
            val values = stringArrayResource(preference.optionValues)
            val colors = stringArrayResource(colorsRes)
            values.zip(colors).toMap()
        }

    BaseDialog(
        title = stringResource(preference.nameStringResource),
        onDismiss = onDismissRequest,
    ) {
        if (lazyListState.canScrollBackward) {
            HorizontalDivider()
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = MaterialTheme.spacings.default),
        ) {
            items(items = options, key = { it.first ?: "null" }) { option ->
                SettingsSelectDialogItem(
                    option = option,
                    isSelected = option.first == preference.value,
                    onSelect = onUpdate,
                    swatch = option.first?.let { swatches?.get(it) }?.let { parseSwatch(it) },
                    showSwatch = swatches != null,
                )
            }
        }
    }
}

@Composable
private fun SettingsSelectDialogItem(
    option: Pair<String?, String>,
    isSelected: Boolean,
    onSelect: (String?) -> Unit,
    swatch: Color? = null,
    showSwatch: Boolean = false,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onSelect(option.first) }
                .padding(horizontal = MaterialTheme.spacings.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = { onSelect(option.first) })
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
        if (showSwatch) {
            // Without a color of its own, the option is the app's original color
            val original =
                if (MaterialTheme.colorScheme.background.luminance() < 0.5f) darkScheme.primary
                else lightScheme.primary
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(swatch ?: original))
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
        }
        Text(text = option.second, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview
@Composable
private fun SettingsSelectDialogPreview() {
    FindroidTheme {
        SettingsSelectDialog(
            preference =
                PreferenceSelect(
                    nameStringResource = SettingsR.string.settings_preferred_audio_language,
                    iconDrawableId = CoreR.drawable.ic_speaker,
                    backendPreference = Preference("", ""),
                    options = SettingsR.array.languages,
                    optionValues = SettingsR.array.languages_values,
                ),
            options = listOf("a" to "Option A", "b" to "Option B", "c" to "Option C"),
            onUpdate = {},
            onDismissRequest = {},
        )
    }
}

private fun parseSwatch(hex: String): Color? =
    try {
        if (hex.isBlank()) null else Color(android.graphics.Color.parseColor(hex))
    } catch (_: IllegalArgumentException) {
        null
    }
