package dev.jdtech.jellyfin

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalEpisodeTitleFirst
import dev.jdtech.jellyfin.presentation.utils.LocalHideEpisodeNumbers
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            // Follow the settings, so titles change as soon as they are switched
            var hideEpisodeNumbers by remember {
                mutableStateOf(appPreferences.getValue(appPreferences.hideEpisodeNumbers))
            }
            var episodeTitleFirst by remember {
                mutableStateOf(appPreferences.getValue(appPreferences.episodeTitleFirst))
            }
            var accentColor by remember {
                mutableStateOf(appPreferences.getValue(appPreferences.accentColor))
            }
            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        appPreferences.hideEpisodeNumbers.backendName ->
                            hideEpisodeNumbers =
                                appPreferences.getValue(appPreferences.hideEpisodeNumbers)
                        appPreferences.episodeTitleFirst.backendName ->
                            episodeTitleFirst =
                                appPreferences.getValue(appPreferences.episodeTitleFirst)
                        appPreferences.accentColor.backendName ->
                            accentColor = appPreferences.getValue(appPreferences.accentColor)
                    }
                }
                appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                        listener
                    )
                }
            }

            FindroidTheme(dynamicColor = state.isDynamicColors, accentColor = accentColor) {
                if (!state.isLoading) {
                    CompositionLocalProvider(
                        LocalOfflineMode provides state.isOfflineMode,
                        LocalHideEpisodeNumbers provides hideEpisodeNumbers,
                        LocalEpisodeTitleFirst provides episodeTitleFirst,
                    ) {
                        NavigationRoot(
                            hasServers = state.hasServers,
                            hasCurrentServer = state.hasCurrentServer,
                            hasCurrentUser = state.hasCurrentUser,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A video playing on in the background comes back as a small window
        PlayerActivity.reopenInPictureInPicture(this)
    }
}
