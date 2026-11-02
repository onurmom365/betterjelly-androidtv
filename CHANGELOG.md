# Changelog

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
