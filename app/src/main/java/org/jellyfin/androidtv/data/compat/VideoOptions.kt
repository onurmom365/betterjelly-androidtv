package org.jellyfin.androidtv.data.compat

class VideoOptions : AudioOptions() {
	var audioStreamIndex: Int? = null
	var subtitleStreamIndex: Int? = null
	// Explicit Default must also suppress the legacy last-audio-language override.
	var preplayAudioExplicit = false
}
