package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaSchedule
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

data class TaSettingsState(
    val url: String = "",
    val token: String = "",
    val connected: Boolean = false,
    val checking: Boolean = false,
    val version: String? = null,
    val connectionError: String? = null,
    val sponsorBlock: String = "button",
    /** TubeArchivist's own settings, by section. Null until loaded. */
    val config: JsonObject? = null,
    val schedules: List<TaSchedule> = emptyList(),
    val serverSettingsError: String? = null,
)

@HiltViewModel
class TaSettingsViewModel
@Inject
constructor(private val appPreferences: AppPreferences, private val taApi: TaApi) : ViewModel() {
    private val _state =
        MutableStateFlow(
            TaSettingsState(
                url = taApi.baseUrl.orEmpty(),
                token = taApi.token.orEmpty(),
                connected = taApi.isConfigured,
                sponsorBlock = appPreferences.getValue(appPreferences.tubeArchivistSponsorBlock),
            )
        )
    val state = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        if (taApi.isConfigured) {
            checkConnection()
            loadServerSettings()
        }
    }

    fun onUrlChange(url: String) = _state.update { it.copy(url = url, connectionError = null) }

    fun onTokenChange(token: String) = _state.update {
        it.copy(token = token, connectionError = null)
    }

    /** Checks the address and token, and saves them if TubeArchivist accepts them. */
    fun connect() {
        val url = normalizeUrl(_state.value.url)
        val token = _state.value.token.trim()
        if (url.isEmpty() || token.isEmpty()) return
        _state.update { it.copy(url = url, checking = true, connectionError = null) }
        viewModelScope.launch {
            try {
                val version = taApi.ping(url, token)
                appPreferences.setValue(appPreferences.tubeArchivistUrl, url)
                appPreferences.setValue(appPreferences.tubeArchivistToken, token)
                _state.update { it.copy(checking = false, connected = true, version = version) }
                _messages.send(TaMessages.SAVED)
                loadServerSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, connectionError = e.errorText()) }
            }
        }
    }

    fun disconnect() {
        appPreferences.setValue(appPreferences.tubeArchivistUrl, null)
        appPreferences.setValue(appPreferences.tubeArchivistToken, null)
        _state.update {
            it.copy(
                connected = false,
                version = null,
                token = "",
                config = null,
                schedules = emptyList(),
            )
        }
    }

    fun setSponsorBlock(mode: String) {
        appPreferences.setValue(appPreferences.tubeArchivistSponsorBlock, mode)
        _state.update { it.copy(sponsorBlock = mode) }
    }

    fun loadServerSettings() {
        viewModelScope.launch {
            try {
                val config = taApi.getConfig()
                _state.update { it.copy(config = config, serverSettingsError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(serverSettingsError = e.errorText()) }
            }
            try {
                val schedules = taApi.getSchedules()
                _state.update { it.copy(schedules = schedules) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load schedules")
            }
        }
    }

    /** Changes one of TubeArchivist's settings, such as downloads / comment_max. */
    fun setConfigValue(section: String, key: String, value: JsonElement) {
        val before = _state.value.config
        // Shown right away, put back if TubeArchivist refuses it
        before?.let { config ->
            val sectionObject = config[section] as? JsonObject ?: JsonObject(emptyMap())
            val updated =
                JsonObject(config + (section to JsonObject(sectionObject + (key to value))))
            _state.update { it.copy(config = updated) }
        }
        viewModelScope.launch {
            try {
                val config = taApi.setConfigValue(section, key, value)
                _state.update { it.copy(config = config) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(config = before) }
                _messages.send(e.errorText())
            }
        }
    }

    /** Sets when a task runs, as minute hour weekday. Empty stops it from running on its own. */
    fun setSchedule(taskName: String, schedule: String) {
        viewModelScope.launch {
            try {
                if (schedule.isBlank()) taApi.deleteSchedule(taskName)
                else taApi.setSchedule(taskName, schedule.trim())
                _state.update { it.copy(schedules = taApi.getSchedules()) }
                _messages.send(TaMessages.SAVED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    private fun checkConnection() {
        viewModelScope.launch {
            try {
                val version = taApi.ping(taApi.baseUrl.orEmpty(), taApi.token.orEmpty())
                _state.update { it.copy(version = version, connectionError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connectionError = e.errorText()) }
            }
        }
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }
}
