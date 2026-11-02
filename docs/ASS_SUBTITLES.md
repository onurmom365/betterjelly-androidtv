# Optional ASS/SSA direct playback

This backport restores the official Jellyfin Android TV libass integration to the fork's v0.19.10-based internal player. That stable baseline had removed libass, its preference and renderer wiring; its remaining subtitle-profile code alone cannot render styled ASS.

Sources: official [PR #5121](https://github.com/jellyfin/jellyfin-androidtv/pull/5121), the applicable embedded-index fix from [PR #5862](https://github.com/jellyfin/jellyfin-androidtv/pull/5862), and [libass-android](https://github.com/peerless2012/libass-android) 0.5.1. The added `ass-media`/`ass-kt` dependency uses the library's published sources and license notices; the wrapper is MIT licensed. The existing Jellyfin GPL-2.0-or-later license and attribution remain unchanged. No implementation from Jellystack is used.

## Enable and check

1. In the fork, open Settings → Playback → Advanced playback settings and enable **Direct Play ASS/SSA subtitles (experimental)**. It is off by default. Keep Audio output at **Direct** for the Opus investigation.
2. Exit any current video and start a fresh playback. The normal internal player creates its renderer for the next playback. The separate experimental rewritten backend retains its configured player, so restart the app if changing its renderer option; do not switch playback backends merely to test this feature.
3. Play the affected episode with its ASS track selected. Check the Jellyfin dashboard for **Direct Play**, original HEVC video/Opus audio, and no H.264 subtitle-burn-in conversion. The option does not guarantee Direct Play if video, audio, container, bitrate policy or a decoder independently requires conversion.
4. Check embedded fonts, dialogue/sign positions, colors, overlapping text and karaoke/animation where present. Test normal playback, deliberate/held seeks and intro skipping for timing or stale subtitles. Check Subtitle Off and a normal SRT track too when available.

The libass Matroska wrapper supplies embedded scripts and font attachments from the original container. Styles and positioning are handled by libass instead of converting ASS to plain text. Remuxed/transcoded streams may not carry those original attachments; do not assume identical font availability in every delivery mode. Forced/default track selection remains controlled by Jellyfin and the user's existing selections; this feature does not add language-selection rules or reconfigure Language Failover.

## Fallback and diagnostics

Turn the ASS option off to restore the prior server-burn-in profile if rendering is wrong or unavailable. Native-library availability gates the advertised ASS profile and actual renderer together. The existing legacy playback-error retry also withdraws ASS direct-play advertisement so a failed playback can fall back to server encoding. Existing video/audio decoder fallback and profile constraints remain intact.

Opus was already included in the original Direct-mode profile. Downmix mode intentionally narrows codecs; this backport does not expand either audio policy or claim every stream is decodable. A platform Opus decoder declaration is not proof that a particular file decoded successfully.

For targeted investigation, the new **BetterJellyCapabilities** log tag emits only capability/request flags, whitelisted codec/reason names, numeric audio properties and numeric player error codes. It excludes URLs, IDs, titles, paths, arbitrary exception text, headers and credentials. Read only that tag; never share normal unfiltered player or network logs. `requestDirectPlay=false` after a player error indicates retry/fallback rather than an absent codec entry. `profileOpus=true` and `profileAss=true` show what was advertised, not proof of actual decoding or Direct Play.

Manual pre-play audio/subtitle controls are documented in [PREPLAY_TRACKS.md](PREPLAY_TRACKS.md). Their Default option delegates to Jellyfin/Language Failover; neither feature introduces an automatic language-failover engine.
