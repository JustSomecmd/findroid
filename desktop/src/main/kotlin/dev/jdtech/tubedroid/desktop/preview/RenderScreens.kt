package dev.jdtech.tubedroid.desktop.preview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.tubearchivist.TaBiggestChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannelNav
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaCountStats
import dev.jdtech.jellyfin.tubearchivist.TaDownloadDay
import dev.jdtech.jellyfin.tubearchivist.TaDownloadItem
import dev.jdtech.jellyfin.tubearchivist.TaDownloadStats
import dev.jdtech.jellyfin.tubearchivist.TaPage
import dev.jdtech.jellyfin.tubearchivist.TaPaginate
import dev.jdtech.jellyfin.tubearchivist.TaPing
import dev.jdtech.jellyfin.tubearchivist.TaPlayer
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistEntry
import dev.jdtech.jellyfin.tubearchivist.TaSearchResponse
import dev.jdtech.jellyfin.tubearchivist.TaSearchResults
import dev.jdtech.jellyfin.tubearchivist.TaStats
import dev.jdtech.jellyfin.tubearchivist.TaSubtitle
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.tubearchivist.TaVideoStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoTotals
import dev.jdtech.jellyfin.tubearchivist.TaWatchPart
import dev.jdtech.jellyfin.tubearchivist.TaWatchStats
import dev.jdtech.tubedroid.desktop.AppGraph
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.Section
import dev.jdtech.tubedroid.desktop.Shell
import dev.jdtech.tubedroid.desktop.ShellContent
import dev.jdtech.tubedroid.desktop.screens.ChannelModel
import dev.jdtech.tubedroid.desktop.screens.SearchModel
import dev.jdtech.tubedroid.desktop.screens.SettingsScreen
import dev.jdtech.tubedroid.desktop.screens.VideoModel
import dev.jdtech.tubedroid.desktop.ui.TubedroidTheme
import dev.jdtech.tubedroid.desktop.ui.installImageLoader
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws the pages, filled from a made-up TubeArchivist on this computer, into PNG files. The build
 * on GitHub runs it so the layout can be looked at without Windows.
 */
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "build/screens").also { it.mkdirs() }
    val server = SampleServer()
    val settings =
        AppSettings(Preferences.userRoot().node("tubedroid-preview")).apply {
            url = server.url
            token = "preview"
            downloadFolder = File(output, "downloads").path
        }
    val graph = AppGraph(settings)
    installImageLoader(graph.api)

    runBlocking(Dispatchers.Main) {
        val shell = Shell()
        val scene =
            ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) { ShellContent(shell) }
                }
            }
        var time = 0L

        suspend fun capture(name: String) {
            repeat(16) {
                scene.render(time)
                time += 200_000_000L
                delay(200)
            }
            val data = scene.render(time).encodeToData(EncodedImageFormat.PNG)
            if (data != null) File(output, "$name.png").writeBytes(data.bytes)
            println("Rendered $name")
        }

        capture("home")
        shell.select(Section.CHANNELS)
        capture("channels")
        shell.open(Route.Channel(SampleData.channels[0].id))
        capture("channel")
        shell.current.retain<ChannelModel>("channel") { error("no channel") }.onServer = true
        capture("channel-manage")
        shell.open(Route.Video(SampleData.videos[0].youtubeId))
        capture("video")
        shell.current.retain<VideoModel>("video") { error("no video") }.onServer = true
        capture("video-manage")
        shell.open(Route.Playlist(SampleData.playlists[0].id))
        capture("playlist")
        shell.select(Section.PLAYLISTS)
        capture("playlists")
        shell.select(Section.DOWNLOADS)
        capture("downloads")
        shell.select(Section.SEARCH)
        capture("search-empty")
        shell.current.retain<SearchModel>("search") { error("no search") }.onQueryChange("sample")
        capture("search")
        shell.select(Section.STATS)
        capture("stats")
        shell.select(Section.QUEUE)
        capture("queue")
        shell.select(Section.SETTINGS)
        capture("settings")
        scene.close()

        val firstRun =
            ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
                CompositionLocalProvider(LocalApp provides graph) {
                    TubedroidTheme(accent = settings.accent) {
                        SettingsScreen(firstRun = true, onDone = {})
                    }
                }
            }
        repeat(4) {
            firstRun.render(time)
            time += 200_000_000L
            delay(100)
        }
        firstRun.render(time).encodeToData(EncodedImageFormat.PNG)?.let {
            File(output, "first-run.png").writeBytes(it.bytes)
        }
        firstRun.close()
    }
    server.stop()
    exitProcess(0)
}

/** Made-up videos, channels and playlists, as TubeArchivist would send them. */
private object SampleData {
    private val names =
        listOf(
            "Northern Lights Workshop",
            "Byte Sized Builds",
            "The Slow Kitchen",
            "Field Notes Outdoors",
            "Analog Synth Lab",
            "Retro Repair Bench",
        )

    val channels = names.mapIndexed { index, name ->
        TaChannel(
            id = "UC$index",
            name = name,
            subs = 12_000L * (index + 1) * (index + 3),
            subscribed = index % 3 != 2,
            description = "A channel about ${name.lowercase()}. New videos every week.",
            thumbUrl = "/cache/channels/UC$index-avatar.jpg",
            bannerUrl = "/cache/channels/UC$index-banner.jpg",
            tvArtUrl = "/cache/channels/UC$index-tvart.jpg",
            lastRefresh = "2026-11-18T08:00:00",
            active = true,
        )
    }

    val videos =
        (0 until 24).map { index ->
            val channel = channels[index % channels.size]
            TaVideo(
                youtubeId = "video$index",
                title =
                    listOf(
                        "Building a cabin by hand, part ${index + 1}",
                        "Restoring a 1984 synthesiser",
                        "Bread with three ingredients",
                        "A week of winter camping",
                        "Fixing a broken cassette deck",
                        "Making a desk from one board",
                    )[index % 6],
                description =
                    "In this video we go through the whole process step by step.\n\n" +
                        "Chapters:\n0:00 Start\n2:30 Materials\n10:12 Building\n\nhttps://example.com/notes",
                published = "2026-11-${(18 - index % 17).toString().padStart(2, '0')}",
                dateDownloaded = 1_763_500_000L - index * 86_400L,
                mediaSize = 400_000_000L + index * 37_000_000L,
                thumbUrl = "/cache/videos/video$index.jpg",
                type = "videos",
                commentCount = 12,
                channel = channel,
                player =
                    TaPlayer(
                        watched = index % 4 == 3,
                        duration = 600.0 + index * 95,
                        durationStr = null,
                        progress = if (index % 5 == 1) 40.0 else null,
                        position = if (index % 5 == 1) 300.0 else null,
                    ),
                stats =
                    TaStats(viewCount = 120_000L * (index + 1), likeCount = 4_000L * (index + 1)),
                mediaUrl = "/media/${channel.id}/video$index.mp4",
                subtitles =
                    listOf(TaSubtitle(lang = "en", name = "English", source = "user", ext = "vtt")),
                active = true,
            )
        }

    val playlists =
        (0 until 6).map { index ->
            val channel = channels[index % channels.size]
            TaPlaylist(
                id = "PL$index",
                name = if (index == 0) "Watch later" else "Best of ${channel.name}",
                channelName = if (index == 0) null else channel.name,
                channelId = if (index == 0) null else channel.id,
                description = "A playlist",
                thumbUrl = "/cache/playlists/PL$index.jpg",
                subscribed = index % 2 == 1,
                type = if (index == 0) "custom" else "regular",
                entries =
                    videos.take(8).map {
                        TaPlaylistEntry(
                            youtubeId = it.youtubeId,
                            title = it.title,
                            downloaded = true,
                        )
                    },
            )
        }

    val queue =
        (0 until 8).map { index ->
            TaDownloadItem(
                youtubeId = "queued$index",
                title = "Upcoming video number ${index + 1}",
                channelName = channels[index % channels.size].name,
                duration = "12:3$index",
                published = "2026-11-1$index",
                status = "pending",
                thumbUrl = "/cache/videos/queued$index.jpg",
            )
        }

    val comments =
        (0 until 5).map { index ->
            TaComment(
                id = "c$index",
                text =
                    "This was the most useful video on the subject I've found. Thanks for the detail.",
                author = "@viewer$index",
                likeCount = 40L - index,
                timeText = "${index + 1} days ago",
            )
        }

    val videoStats =
        TaVideoStats(
            count = 4_382,
            mediaSize = 3_100_000_000_000L,
            duration = 11_870_000L,
            regular = TaVideoTotals(3_900, 2_900_000_000_000L, 11_000_000L),
            shorts = TaVideoTotals(400, 40_000_000_000L, 20_000L),
            streams = TaVideoTotals(82, 160_000_000_000L, 850_000L),
            active = TaVideoTotals(4_200, 3_000_000_000_000L, 11_500_000L),
            inactive = TaVideoTotals(182, 100_000_000_000L, 370_000L),
        )
}

/** Answers like TubeArchivist's API, with [SampleData], and draws a picture for every image. */
private class SampleServer {
    private val json = Json { encodeDefaults = true }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (type, body) =
                if (path.startsWith("/cache/")) {
                    "image/png" to picture(path)
                } else {
                    "application/json" to (answer(path) ?: "{}").toByteArray()
                }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun <T> page(serializer: KSerializer<T>, items: List<T>): String =
        json.encodeToString(
            TaPage.serializer(serializer),
            TaPage(items, TaPaginate(1, 1, items.size)),
        )

    private fun <T> list(serializer: KSerializer<T>, items: List<T>): String =
        json.encodeToString(ListSerializer(serializer), items)

    private fun answer(path: String): String? {
        val parts = path.trim('/').split('/')
        if (parts.firstOrNull() != "api") return null
        return when (parts.getOrNull(1)) {
            "ping" -> json.encodeToString(TaPing.serializer(), TaPing("pong", "v0.5.8"))
            "video" ->
                when {
                    parts.size == 2 -> page(TaVideo.serializer(), SampleData.videos)
                    parts.getOrNull(3) == "comment" ->
                        list(TaComment.serializer(), SampleData.comments)
                    parts.getOrNull(3) == "similar" ->
                        list(TaVideo.serializer(), SampleData.videos.drop(6).take(6))
                    else ->
                        SampleData.videos
                            .firstOrNull { it.youtubeId == parts[2] }
                            ?.let { json.encodeToString(TaVideo.serializer(), it) }
                }
            "channel" ->
                when {
                    parts.size == 2 -> page(TaChannel.serializer(), SampleData.channels)
                    parts.getOrNull(3) == "nav" ->
                        json.encodeToString(
                            TaChannelNav.serializer(),
                            TaChannelNav(hasPlaylists = true, hasVideos = true, hasShorts = true),
                        )
                    parts.getOrNull(3) == "aggs" ->
                        """{"total_items":{"value":312},"total_size":{"value":250000000000},""" +
                            """"total_duration":{"value":402000}}"""
                    else ->
                        SampleData.channels
                            .firstOrNull { it.id == parts[2] }
                            ?.let { json.encodeToString(TaChannel.serializer(), it) }
                }
            "playlist" ->
                if (parts.size == 2) {
                    page(TaPlaylist.serializer(), SampleData.playlists)
                } else {
                    SampleData.playlists
                        .firstOrNull { it.id == parts[2] }
                        ?.let { json.encodeToString(TaPlaylist.serializer(), it) }
                }
            "download" -> page(TaDownloadItem.serializer(), SampleData.queue)
            "notification" -> "[]"
            "search" ->
                json.encodeToString(
                    TaSearchResponse.serializer(),
                    TaSearchResponse(
                        TaSearchResults(
                            videos = SampleData.videos.take(5),
                            channels = SampleData.channels.take(2),
                            playlists = SampleData.playlists.take(2),
                        )
                    ),
                )
            "stats" ->
                when (parts.getOrNull(2)) {
                    "video" -> json.encodeToString(TaVideoStats.serializer(), SampleData.videoStats)
                    "channel" ->
                        json.encodeToString(TaCountStats.serializer(), TaCountStats(48, 45, 31))
                    "playlist" ->
                        json.encodeToString(TaCountStats.serializer(), TaCountStats(64, 60, 12))
                    "download" ->
                        json.encodeToString(
                            TaDownloadStats.serializer(),
                            TaDownloadStats(8, 3, 6, 1, 1),
                        )
                    "watch" ->
                        json.encodeToString(
                            TaWatchStats.serializer(),
                            TaWatchStats(
                                TaWatchPart(1_200, 3_200_000, 0.27),
                                TaWatchPart(3_182, 8_670_000, 0.73),
                            ),
                        )
                    "downloadhist" ->
                        list(
                            TaDownloadDay.serializer(),
                            (0 until 10).map {
                                TaDownloadDay(
                                    "2026-11-${18 - it}",
                                    3L + it % 4,
                                    2_000_000_000L * (1 + it % 3),
                                )
                            },
                        )
                    "biggestchannels" ->
                        list(
                            TaBiggestChannel.serializer(),
                            SampleData.channels.mapIndexed { index, channel ->
                                TaBiggestChannel(
                                    channel.id,
                                    channel.name,
                                    900L - index * 120,
                                    2_000_000L - index * 250_000,
                                    600_000_000_000L - index * 80_000_000_000L,
                                )
                            },
                        )
                    else -> null
                }
            "user" -> """{"file_size_unit":"binary"}"""
            else -> null
        }
    }

    /** A gradient in a colour picked from the path, so each image looks different. */
    private fun picture(path: String): ByteArray {
        val banner = path.contains("banner")
        val square = path.contains("avatar")
        val width = if (banner) 1280 else if (square) 160 else 480
        val height = if (banner) 210 else if (square) 160 else 270
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val hue = (path.hashCode() and 0xFFFF) / 65535f
        val graphics = image.createGraphics()
        graphics.paint =
            GradientPaint(
                0f,
                0f,
                Color.getHSBColor(hue, 0.55f, 0.75f),
                width.toFloat(),
                height.toFloat(),
                Color.getHSBColor((hue + 0.15f) % 1f, 0.6f, 0.35f),
            )
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().use {
            ImageIO.write(image, "png", it)
            it.toByteArray()
        }
    }
}
