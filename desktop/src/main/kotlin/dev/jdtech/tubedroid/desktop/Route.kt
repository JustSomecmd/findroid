package dev.jdtech.tubedroid.desktop

/** The pages of the app. The first five are the sections in the rail on the left. */
sealed interface Route {
    data object Home : Route

    data object Channels : Route

    data object Downloads : Route

    data object Search : Route

    data object Settings : Route

    data class Channel(val channelId: String) : Route

    data class Video(val videoId: String, val playlistId: String? = null) : Route

    data class Playlist(val playlistId: String) : Route
}

/** The sections in the rail, each with its own pages to go back through. */
enum class Section(val root: Route, val label: String, val icon: String) {
    HOME(Route.Home, "Home", "ic_home"),
    CHANNELS(Route.Channels, "Channels", "ic_users"),
    DOWNLOADS(Route.Downloads, "Downloads", "ic_download"),
    SEARCH(Route.Search, "Search", "ic_search"),
    SETTINGS(Route.Settings, "Settings", "ic_settings"),
}
