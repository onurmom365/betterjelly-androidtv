package org.jellyfin.androidtv.ui.playback

interface PlaybackControllerNotifiable {
	fun onCompletion()
	fun onError(automaticContainerRecovery: Boolean)
	fun onPrepared()
	fun onProgress()
	fun onPlaybackSpeedChange(newSpeed: Float)
	fun onTracksChanged()
}
