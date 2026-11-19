package dev.jdtech.tubedroid.desktop

/** The pages of the app. Those without anything to show first are the sections in the rail. */
sealed interface Route {
    data object Home : Route

    data object Channels : Route

    data object Playlists : Route

    data object Downloads : Route

    data object Search : Route

    data object Stats : Route

    data object Queue : Route

    data object JellyfinHome : Route

    data object JellyfinMovies : Route

    data object JellyfinShows : Route

    data object Settings : Route

    data class Channel(val channelId: String) : Route

    data class Video(val videoId: String, val playlistId: String? = null) : Route

    data class Playlist(val playlistId: String) : Route

    /** A movie or an episode on Jellyfin. */
    data class JellyfinItem(val itemId: String) : Route

    /** A show on Jellyfin, with its seasons. */
    data class JellyfinShow(val seriesId: String) : Route
}

/** Where a section sits in the rail: with the app's own pages, the server's, or Jellyfin's. */
enum class RailGroup {
    TUBEARCHIVIST,
    SERVER,
    JELLYFIN,
    END,
}

/** The sections in the rail, each with its own pages to go back through. */
enum class Section(val root: Route, val label: String, val icon: String, val group: RailGroup) {
    HOME(Route.Home, "Home", "ic_home", RailGroup.TUBEARCHIVIST),
    CHANNELS(Route.Channels, "Channels", "ic_users", RailGroup.TUBEARCHIVIST),
    PLAYLISTS(Route.Playlists, "Playlists", "ic_list_video", RailGroup.TUBEARCHIVIST),
    DOWNLOADS(Route.Downloads, "Downloads", "ic_download", RailGroup.TUBEARCHIVIST),
    SEARCH(Route.Search, "Search", "ic_search", RailGroup.TUBEARCHIVIST),
    STATS(Route.Stats, "Stats", "ic_gauge", RailGroup.SERVER),
    QUEUE(Route.Queue, "Queue", "ic_list_plus", RailGroup.SERVER),
    JELLYFIN(Route.JellyfinHome, "Jellyfin", "ic_tv", RailGroup.JELLYFIN),
    MOVIES(Route.JellyfinMovies, "Movies", "ic_film", RailGroup.JELLYFIN),
    SHOWS(Route.JellyfinShows, "Shows", "ic_library", RailGroup.JELLYFIN),
    SETTINGS(Route.Settings, "Settings", "ic_settings", RailGroup.END),
}
