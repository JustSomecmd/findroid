package dev.jdtech.jellyfin.settings.presentation.playerbuttons

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.PlayerButton
import dev.jdtech.jellyfin.settings.domain.PlayerButtonConfig
import dev.jdtech.jellyfin.settings.domain.PlayerButtonLayout
import dev.jdtech.jellyfin.settings.domain.PlayerButtonPlacement
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class PlayerButtonsViewModel @Inject constructor(private val appPreferences: AppPreferences) :
    ViewModel() {
    private val _buttons =
        MutableStateFlow(
            PlayerButtonLayout.parse(appPreferences.getValue(appPreferences.playerButtons))
        )
    val buttons = _buttons.asStateFlow()

    fun reorder(order: List<PlayerButton>) {
        val byButton = _buttons.value.associateBy { it.button }
        save(order.mapNotNull { byButton[it] })
    }

    fun setPlacement(button: PlayerButton, placement: PlayerButtonPlacement) {
        save(_buttons.value.map { if (it.button == button) it.copy(placement = placement) else it })
    }

    fun reset() {
        save(PlayerButtonLayout.default)
    }

    private fun save(configs: List<PlayerButtonConfig>) {
        _buttons.value = configs
        appPreferences.setValue(appPreferences.playerButtons, PlayerButtonLayout.serialize(configs))
    }
}
