# Install Jellyfin from betterjelly-androidtv

This is an independent fork. The TV app still displays **Jellyfin** and uses the Jellyfin splash screen.

## Fire OS with Downloader

1. Confirm the device runs Android-based Fire OS. Vega OS cannot run this APK.
2. Install **Downloader by AFTVnews** from the device's app store.
3. Allow Downloader to install apps when the device asks. Depending on Fire OS, this is under Settings → My Fire TV → Developer options → Install unknown apps. Menu names vary. If Developer options is hidden, open About and select the device name seven times.
4. In Downloader, enter code **8769319**, or open this direct APK URL:

   `https://github.com/onurmom365/betterjelly-androidtv/releases/latest/download/betterjelly-androidtv.apk`

5. Choose Install, then open **Jellyfin**. No cable or GitHub account is needed. Code **8769319** points to the stable latest APK URL, so it can be reused for future releases that keep the same asset name.

For Shield/Android TV, use Downloader where available, transfer the APK through your preferred file-transfer app, or install with `adb install -r betterjelly-androidtv.apk` after verifying its identity.

## Connect to Jellyfin and Seerr

Sign into the intended Jellyfin server with your own Jellyfin account. Do not use another person's administrator credentials.

For Seerr, open Search and a Seerr title, choose **Server**, and select the correct HTTPS address when away from home. This deployment includes a Remote HTTPS preset; another server can be entered instead. Test and save the address. Sign in using your own account and one of the methods enabled by that Seerr server. Jellyfin sign-in uses your Jellyfin username/password through Seerr; it does not copy your existing Jellyfin token. Passwords are not retained. LAN and HTTPS sessions are saved separately.

Search, Requests and request review can be checked without submitting media. Actual media requests, Retry and Delete change server state and should be used deliberately. Approval rules and quality/tag permissions remain controlled by Seerr. A request saved in Seerr does not guarantee successful downstream acquisition.

## Audio and subtitles in 1.0.2

Movie, episode and series pages have **Audio** and **Subtitles** actions, sometimes under **Other options**. Choose a named track, **Off**, or **Default — Jellyfin / Language Failover**. A new series-wide choice automatically supersedes older episode overrides; a later episode-specific choice can override the series again. Preferences stay on this device/account. See [pre-play track behavior](PREPLAY_TRACKS.md).

For compatible styled anime subtitles, optionally enable **Settings → Playback → Advanced playback settings → Direct Play ASS/SSA subtitles (experimental)**. It is off by default. Exit and start the video again after changing it. Shield HEVC/Opus/ASS Direct Play and styled subtitles are user-verified; other devices/files may still need transcoding. See [ASS/SSA setup and fallback](ASS_SUBTITLES.md).

Returning from early playback can now show Resume even before the server keeps a Continue Watching bookmark. This local return point lasts up to 30 minutes within the current app process; normal server resume rules remain unchanged after that window or an app-process restart.

## Update while preserving settings

Version **1.0.3** includes Shield-tested recovery for a specific compressed-MKV startup failure and selects a supported HLS video remux when available. Fire TV users can install it to test affected playback. It does not guarantee every Dolby track/output path; startup/seek delay and trickplay speed remain open, and HLS audio conversion can still occur. Existing playback settings stay in place.

In 1.0.1 and later, open **Settings → About → Check for updates**. Review the version/notes, select **Download update**, then **Install update**. If asked, allow **Jellyfin** in **Install unknown apps**, return to the updater and select Install update again. Confirm the system install dialog. Cancelling the download/installer leaves the current app and settings in place.

Automatic checks run about once every 24 hours, even if the app stays open, using Android's persistent background scheduler. They check availability only, require a network connection and may be delayed by sleep or OS power restrictions. An unseen compatible update prompts on Home; playback is not interrupted. **Automatic checks: Off** disables the daily job. Manual Check for updates bypasses the daily schedule and remains available.

For an existing 1.0.0 installation, use Downloader code **8769319** or the download link once to install a version containing the updater. The same code/link remains a fallback for later manual updates. Install over the existing fork: do not uninstall or clear data. Matching application ID and signing identity preserve compatible local settings and sessions; expired server sessions may need sign-in again.

The fork's package is `org.jellyfin.androidtv.debug`; official Jellyfin is `org.jellyfin.androidtv`. Both can coexist, even though both display Jellyfin. Existing Jellyfin Debug development installations update to this release and become labelled Jellyfin. Official app settings do not transfer automatically.

Before handing a file to Android's installer, the updater checks the actual APK's package, newer version, non-debuggable status, signing identity and SHA-256. It uses this fork's public GitHub Releases only; no GitHub account, Jellyfin/Seerr credential or storage permission is used for updating. Silent/unattended installation is not promised. Future releases retain the package/signing identity and increase version codes.

## Verification and troubleshooting

The release provides `SHA256SUMS.txt`, `release.json` and a matching source tag. The signing certificate SHA-256 is:

`6644d28e5c9249280a7552f404c675bde1316508eef2438fa2217b10121d8272`

If installation reports an incompatible signature or wrong package, stop rather than uninstalling an app containing wanted data. Check that you downloaded this repository's release APK. Never uninstall official Jellyfin to install this fork.

Fire hardware is not yet validated. After installation, check remote navigation, audio/passthrough, HDR/Dolby Vision where supported, subtitles, seeking/trickplay, intro skipping and Seerr with your own account. Seerr encrypted sessions require Android 6 or later.
