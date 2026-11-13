# Changelog

## 1.0.4 — 2026-11-12

- Fixed updater APK certificate extraction on Android 9/10 and affected Fire OS versions. Request both certificate flags so older archive parsers actually collect the APK's certificates; use the legacy certificate field only when modern metadata is unavailable.
- Keep the same signing key, exact certificate pin, package/version/hash checks and system installer confirmation. A mismatched current signer cannot be bypassed by a matching legacy field; unsigned and multiple-signer packages remain rejected.
- Distinguish missing certificate metadata from a real signing mismatch.

If an older installation blocks the update with a signing-identity message, use Downloader code **8769319** once and install over the existing fork, without uninstalling. The old updater performs its check before this fix can run. Later in-app updates use the corrected check. Playback, quality, Seerr and saved settings are unchanged from 1.0.3; existing latency/trickplay work remains separate. Real Fire OS installer confirmation is still required.

## 1.0.3 — 2026-11-11

- Recover from the specific unsupported zlib-compressed MKV track header by using the server's HLS fallback. When the player would select a forced video encode, prefer a unique equally sized/equally rated remux that Media3 reports as fully supported. Ordinary fallback remains available if recovery fails.
- Match equivalent HLS video formats even when player track IDs differ from playlist IDs. Refuse ambiguous mappings and unsupported or different-quality alternatives.
- Avoid the generic startup error popup for this recognized automatic container recovery; other player errors and terminal failure notifications remain visible.
- Added diagnostics limited to fixed categories, numeric media properties and anonymous state flags, with no media identities, URLs or credentials.

Shield testing confirms working playback and resumed seeking for the affected container case. Startup/seek delays and trickplay responsiveness still need improvement. Fire TV testing is pending; this release is available for that testing, not a universal Dolby or Fire playback fix. The recovery targets preserving the original video; server-side HLS audio conversion may still occur, and lossless audio output is not confirmed. Existing Seerr, subtitle selections, seek/OK/skip behavior and updater are retained.

## 1.0.2 — 2026-11-02

- Added optional ASS/SSA direct playback with libass, preserving styled subtitles and embedded font attachments on compatible playback. Enable it in Settings → Playback → Advanced playback settings; it remains experimental and off by default. Transcoding fallbacks remain available.
- Added Audio and Subtitles actions to movie, episode and series detail pages, including explicit Off and Default options. Series choices persist per device/account; a newer series-wide choice automatically supersedes older episode overrides. A later episode choice can create an individual exception.
- Resolved manual track choices and explicit Subtitle Off now take precedence over unattributed automatic server track commands. Default still follows Jellyfin/Language Failover; identified remote-user control and in-player choices remain available.
- Added an immediate local Resume point when returning to a movie/episode page, including early playback below the server's resume threshold. It lasts up to 30 minutes within the current app process; server resume settings and cross-device progress rules are unchanged.
- Improved subtitle stream-index handling and startup track application. Shield checks confirm HEVC/Opus/ASS Direct Play, styled subtitles, named series/movie subtitles, server-default selection and early return Resume. Existing audio capabilities, Seerr integration, custom seek/OK behavior and daily updater remain intact.

Fire hardware and wider HDR/Dolby Vision/passthrough regressions remain untested. This release retains the fork's application ID, signing identity and Jellyfin launcher/splash branding.

## 1.0.1 — 2026-11-01

- Added Settings → About → Check for updates, release notes, download progress/cancellation and verified in-place system installation.
- Added daily availability checks through Android's persistent background scheduler, including when the app stays open. Prompts appear only on Home; downloads/installations remain user initiated. Automatic checks can be disabled and may be delayed by OS sleep/network restrictions.
- Updates use this fork's public GitHub Releases with bounded metadata/downloads, SHA-256, package/version/non-debuggable checks and the existing signing identity. No Jellyfin/Seerr credentials are used.
- Added a narrowly scoped FileProvider and the user-controlled install permission. Account/channel refresh preserves the independent daily update schedule. No media-player or Seerr behavior/dependency changes.
- Existing 1.0.0 installations need Downloader code 8769319 once to receive the updater. Later updates can be downloaded from inside the app.

## 1.0.0 — 2026-11-01

First public release of this independent Jellyfin Android TV fork, based on upstream v0.19.10.

- Immediate progress-bar/trickplay control with Left/Right, stable five-second precision taps, configured held seeking and automatic resume without OK.
- Steady playback buttons/timestamps during consecutive seeking. Completed-seek controls remain hidden through delayed media catch-up.
- First hidden-controls OK reveals controls with Play/Pause focused without pausing; about three seconds of inactivity hides them. A separate second press activates the button.
- Separate Resume and Play from beginning actions.
- Smooth blue/purple segment countdown. Back during a countdown lets that segment play through for the current playback session; other segments retain normal behavior.
- Native Seerr results within regular Search, poster/title presentation, request history, season selection, quality profiles, folders and existing tags.
- Permission-checked failed-request Retry/Delete confirmation and saved-request verification without automatic mutation replay.
- Configurable LAN/HTTPS Seerr endpoint, isolated encrypted sessions, server-enabled login methods including Jellyfin, transient read recovery and fresh foreground session validation.
- Non-debuggable release packaging, unchanged fork package/signing identity, increasing semantic version codes, and Jellyfin launcher/splash branding.

Shield acceptance covers the changed controls and Seerr browsing/login flows. Fire Stick hardware, live Retry/Delete and long-term session retention remain unverified. No backend service/configuration changes are included. The in-app updater is not included in this release.
