# Tubedroid

Tubedroid is an Android app for [TubeArchivist](https://www.tubearchivist.com). You browse and manage your archive through TubeArchivist, and the videos play through Jellyfin, so downloads, watch progress and the player all work the way they do for the rest of your Jellyfin library.

It is also a full Jellyfin client for movies and shows. A switch in settings turns the TubeArchivist interface off.

This is a personal project. Releases are built automatically from the `custom` branch and published on the [Releases page](../../releases).

## What you need

- **TubeArchivist**, with an API token from its settings page. Tubedroid is built and tested against TubeArchivist v0.6.0.
- **Jellyfin 12.0 or newer**, with TubeArchivist's media folder added as a library. The TubeArchivist Jellyfin plugin is recommended. Without it, videos are matched by their file names, which TubeArchivist sets to the video and channel IDs.
- **Android 10 or newer.**
- For the server stats and the bot detection notifications, the TubeArchivist token has to belong to an admin account.

## TubeArchivist features

Most pages have a phone side and a server side. Swipe or tap the switch in the top bar to move between them; the colours change to show which side you are on.

**Home**
- Continue watching, for videos and for playlists. Tapping a playlist plays the next video in it; holding it opens the playlist.
- A carousel of random channels with their banners, and the latest videos.
- Swipe right for the server's stats: total videos, storage, playing time and watched share, then watch progress, video types, channels and playlists, the download queue, downloads over the last 8 days, and the biggest channels by videos, playing time or storage.

**Channels**
- Subscribed, All channels and Playlists tabs.
- Subscribe to channels and YouTube playlists, or make your own playlists.
- Channel pages show the banner, the number of videos, their playing time and their storage on the server, with tabs for videos, shorts, streams and playlists.
- The server side of a channel has its download settings, refresh buttons, and a list to select videos and reindex, redownload or delete them.

**Videos**
- Description, comments, watched state, favorite, save to a playlist, download to the phone, open in TubeArchivist's web interface.
- The server side can reindex, redownload or delete the video, each with a confirmation.

**Playlists**
- Play through a playlist in order. Home remembers where you left off.
- Download a whole playlist to the phone. The button shows when every video is on the phone.
- Playlists are cached, so they still open without a connection to TubeArchivist.

**Downloads**
- The phone's downloads and TubeArchivist's download queue side by side.
- Add videos, playlists or channels by link, start downloads now, ignore videos or put them back, and clear the queue.

**Also**
- Search across TubeArchivist.
- Change TubeArchivist's settings from the phone: subscriptions, download format (with a builder for yt-dlp formats), subtitles, comments, SponsorBlock, Return YouTube Dislike and schedules (with a builder for the timing).
- Watch progress is reported back to TubeArchivist.
- A notification when YouTube's bot detection stops a download or rescan. TubeArchivist is checked every 15 minutes.

## Player

- Plays with ExoPlayer. A cogwheel menu holds quality, speed, captions, the sleep timer, tunneled playback and a playback info page with the formats, decoders and dropped frames.
- Autoplay, with a card showing the next video and its thumbnail during the last seconds. It can be turned off from the player.
- A sleep timer that pauses after 10 to 90 minutes, fading the sound out first, or stops at the end of the video.
- Background playback. After 10 seconds in the background only the sound is streamed.
- Picture-in-picture with play, pause and a button to carry on in the background. Opening the app again brings the picture-in-picture window back.
- Choice of streaming quality, with Jellyfin converting the video when a lower quality is picked.
- Playback speed, chapters, trickplay previews, and skip buttons or automatic skipping for media segments and SponsorBlock segments.
- Lock and rotate buttons. Every button can be moved to the top bar or bottom bar, or hidden.
- Controls in the app's accent colour, if wanted.

## Jellyfin features

- A native interface for movies, shows, seasons and episodes, and Jellyfin playlists.
- An in-app downloader that resumes interrupted downloads, downloads one item at a time, and lets you reorder or pause the queue.
- A storage overview of everything downloaded, per server.
- Notifications about new episodes and movies on the server.
- An accent colour of your choice, and options to hide episode numbers, show episode titles first and list all episodes on show pages.

## Installing

Download the APK from the [latest release](../../releases/latest). The arm64-v8a file is the right one for nearly every current phone. Tubedroid installs next to Findroid rather than replacing it, and newer releases install over older ones.

## Building

```sh
./gradlew :app:phone:assembleLibreRelease
```

The Android TV app in this repository is Findroid's and doesn't have the TubeArchivist interface.

## Credits and license

Tubedroid is a modified version of [Findroid](https://github.com/jarnedemeulemeester/findroid) by Jarne Demeulemeester. The changes start from Findroid's commit `101e2d0` of 16 November 2026. Like Findroid, it is licensed under [GPLv3](LICENSE).

Tubedroid is not affiliated with TubeArchivist, Jellyfin or YouTube.

Android is a trademark of Google LLC.
