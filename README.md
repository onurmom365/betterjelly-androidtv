# betterjelly-androidtv

An independent Jellyfin Android TV fork with remote seeking improvements, optional styled ASS/SSA direct playback and native Seerr integration. The installed app is called **Jellyfin**, with the Jellyfin icon and splash screen. This project is not an official Jellyfin release.

**[Download the latest APK](https://github.com/onurmom365/betterjelly-androidtv/releases/latest/download/betterjelly-androidtv.apk)** | **[Installation guide](docs/INSTALL.md)** | **[Releases](https://github.com/onurmom365/betterjelly-androidtv/releases)**

**Downloader code: `8769319`** — install Downloader by AFTVnews on Android-based Fire OS/Android TV, enter the code, then choose Install. The code targets the latest APK.

## Features

- Left/Right immediately controls the progress bar with trickplay previews. Deliberate taps move five seconds; held buttons use configured seek steps. Playback resumes after seeking without pressing OK.
- First OK reveals controls without pausing, focuses Play/Pause and fades after about three seconds without further input. A separate second OK activates Play/Pause.
- Separate Resume and Play from beginning actions.
- Audio/subtitle selection before playback on movie, episode and series pages, with persistent series choices, Off and server Default options. Explicit manual choices take precedence over automatic startup track commands.
- Optional libass ASS/SSA direct playback for styled subtitles and embedded fonts. Enable it under Playback → Advanced playback settings. See [subtitle setup](docs/ASS_SUBTITLES.md) and [pre-play tracks](docs/PREPLAY_TRACKS.md).
- Recovery for a specific unsupported compressed-MKV header: prefer a fully supported, equivalent-quality HLS video remux over a forced video encode. Shield playback is verified; startup/seek latency, trickplay speed and Fire TV behavior remain under investigation. HLS audio conversion can still occur.
- Immediate return-to-details Resume for early playback, using a local 30-minute/current-process return point while normal server resume rules remain unchanged.
- Smooth colored countdown before supported intro/credits/recap segments. Back during the countdown lets that segment play through for the current playback session.
- Regular Search includes Seerr poster results above library results. Native title details, season selection, request review/history and permitted failed-request Retry/Delete controls.
- Seerr quality profiles, root folders and existing tags, subject to the signed-in user's permissions. Configurable LAN/HTTPS address, separate encrypted sessions and foreground recovery.
- Jellyfin, Seerr account or Plex sign-in according to the chosen Seerr server's enabled methods.
- In-app GitHub updates with daily availability checks, release notes, verified APK downloads and user-confirmed installation. Daily checks also work while the app stays open; prompts appear only on Home.

Seerr requires a separately configured server. Intro segments and trickplay images require server-provided data. Jellyfin browsing and playback remain independent of Seerr availability.

## Compatibility and updates

Tested on an NVIDIA Shield. The universal APK includes ARM 32-bit/64-bit and x86 variants. Android-based Fire OS devices are candidates; Fire Stick installation, remote behavior and codecs still need hardware testing. Vega OS devices cannot install Android APKs. Older Android versions may not support every integration feature; Seerr secure session storage requires Android 6 or later.

The application ID is **`org.jellyfin.androidtv.debug`**, retained from the tested development builds to preserve their data. It is separate from official `org.jellyfin.androidtv`. Release APKs are not debuggable. If both are installed, both may be labelled Jellyfin; identify the fork in its version information or Android App info.

Version 1.0.1 adds **Settings → About → Check for updates**. Review the update, choose Download update, then Install update and confirm the system installer. Compatible settings and saved sessions are retained; do not uninstall first. You may need to allow Jellyfin to install updates once. Daily background checks can be switched off, do not require closing the app, and defer prompts until Home. Android sleep/network restrictions can delay a check; downloads and installs always require your selection. Existing 1.0.0 users need Downloader code 8769319 once to get the updater. Official store updates do not update this fork.

Version **1.0.4** repairs certificate reading on older Android/affected Fire OS versions. If an older updater refuses the update with a signing-identity message, use Downloader **8769319** once to install over the fork, preserving settings. The old check runs before the fix can be installed. The certificate pin and signing key are unchanged; later in-app updates use the repaired check.

## Source and license

Based on Jellyfin Android TV **v0.19.10**, upstream commit `984181a3d6ab14e9a6d2dcc850c582e1c138bd95`. Original copyright and license notices are retained. Distributed under GPL-2.0-or-later; see [LICENSE](LICENSE), [changes](CHANGELOG.md), [build instructions](docs/BUILD.md) and the [preserved upstream README](docs/UPSTREAM_README.md). Third-party dependency notices remain available in the app.

Use this repository for fork-specific issues. Do not report fork-specific bugs as official Jellyfin bugs. Do not include passwords, tokens, API keys or private playback/network logs in reports.
