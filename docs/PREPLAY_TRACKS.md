# Audio and subtitles before playback

Movie, episode and series detail pages have **Audio** and **Subtitles** actions, using the same icons as the player. If the action row is full, look in **Other options**. Opening a picker does not start playback.

- **Default — Jellyfin / Language Failover** leaves the track index to the server and its installed plugin. It does not configure or implement Language Failover.
- **Off — no subtitles** explicitly disables subtitles, including forced tracks, for the selected scope.
- A named track selects that specific audio/subtitle track before playback negotiation. The player can still use safe transcoding when necessary.
- Movies and episodes remember choices for that title on this device/account. Episodes can choose **Use series selection** to remove their individual override, or choose Default to override a saved series preference with server defaults.
- Series choices persist on this device/account and apply to subsequent episode launches and queued episodes. The picker lists tracks from the next-up episode, or the first available episode when there is no next-up item.

Series preferences match the manually selected track's language, title, codec, channel count, forced and hearing-impaired flags in each episode. They never reuse a numeric stream index across episodes. Missing or ambiguous matches defer to the server; this deliberately avoids guessing between signs, dialogue and commentary. A changed release/track layout can require choosing again. In-player changes affect the current playback and do not rewrite a saved series choice.

Preferences are local to this fork, scoped by Jellyfin server/user IDs. No server settings, media metadata or Seerr records change. The standard internal player supports these controls; external applications and the experimental playback rewrite retain their existing behavior and the picker explains that limitation.

## Shield verification

1. On a movie and an episode, choose a nondefault audio track and subtitles, then Play or Resume. Confirm the player starts with those tracks. Try Off, seeking, intro skipping and a stream restart; Off should remain off.
2. On a series, choose audio/subtitles. Launch two episodes, including one with reordered streams if available. Reopen the app and verify the series choice remains. An episode override must stay local to that episode; Use series selection restores inheritance.
3. Set Default and confirm normal Jellyfin/Language Failover behavior. A series choice whose matching track is absent or duplicated must fall back safely.
4. Check both direct-play ASS and a title that requires transcoding. Confirm seeking/OK focus, subtitles, playback resume and settings remain intact.

Upstream research: [feature issue2184](https://github.com/jellyfin/jellyfin-androidtv/issues/2184), [open PR4987](https://github.com/jellyfin/jellyfin-androidtv/pull/4987). That PR remains open; this fork uses its own small manual-choice implementation rather than importing another client's playback or language engine.
