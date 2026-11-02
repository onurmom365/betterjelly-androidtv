package org.jellyfin.androidtv.ui.playback.selection

import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

/** Manual choices override anonymous automatic commands; identified remote users can still take control. */
object TrackCommandPolicy {
	@JvmStatic fun hasControllingUser(id: UUID?) = id != null && id != UUID(0, 0)
	@JvmStatic fun accepts(options: VideoOptions?, type: MediaStreamType, userControlled: Boolean): Boolean {
		if (options == null) return false
		val manual = when (type) {
			MediaStreamType.AUDIO -> options.manualAudioSelection
			MediaStreamType.SUBTITLE -> options.manualSubtitleSelection
			else -> return false
		}
		return userControlled || !manual
	}
	@JvmStatic fun markManual(options: VideoOptions?, type: MediaStreamType) {
		when (type) {
			MediaStreamType.AUDIO -> options?.manualAudioSelection = true
			MediaStreamType.SUBTITLE -> options?.manualSubtitleSelection = true
			else -> Unit
		}
	}
}
