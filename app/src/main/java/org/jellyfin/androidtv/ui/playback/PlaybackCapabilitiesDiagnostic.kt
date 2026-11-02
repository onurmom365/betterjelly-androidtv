package org.jellyfin.androidtv.ui.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import android.util.Log
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/** Deliberately excludes URLs, IDs, titles, paths, headers and arbitrary exception/codec text. */
object PlaybackCapabilitiesDiagnostic {
	const val TAG = "BetterJellyCapabilities"
	private val knownReasons = setOf("AudioCodecNotSupported", "SubtitleCodecNotSupported", "ContainerNotSupported", "VideoCodecNotSupported",
		"AudioChannelsNotSupported", "AudioBitrateNotSupported", "VideoBitrateNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported",
		"VideoResolutionNotSupported", "VideoRangeTypeNotSupported", "DirectPlayError")
	internal fun safeReasons(url: String?): String {
		val values = runCatching { "https://invalid.example/".toHttpUrl().resolve(url.orEmpty())?.queryParameter("TranscodeReasons") }.getOrNull()
		return values?.split(',')?.map { if (it in knownReasons) it else "Other" }?.distinct()?.joinToString("|") ?: "None"
	}
	internal fun safeCodec(value: String?): String = value?.lowercase()?.takeIf {
		it in setOf("opus", "libopus", "aac", "aac_latm", "ac3", "eac3", "flac", "dts", "dca", "truehd", "vorbis", "pcm_s16le")
	} ?: "other"
	@JvmStatic fun playerError(code: Int) { Log.i(TAG, "playerErrorCode=$code") }
	internal fun negotiation(options: VideoOptions, response: PlaybackInfoResponse) {
		val source = response.mediaSources.firstOrNull { it.id == options.mediaSourceId } ?: response.mediaSources.firstOrNull() ?: return
		val audio = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }
			.let { tracks -> tracks.firstOrNull { it.index == (options.audioStreamIndex ?: source.defaultAudioStreamIndex) } ?: tracks.firstOrNull() }
		val opusAllowed = options.profile?.directPlayProfiles.orEmpty().any { "opus" in it.audioCodec.orEmpty().split(',') }
		val assAllowed = options.profile?.subtitleProfiles.orEmpty().any { it.format == "ass" && it.method == SubtitleDeliveryMethod.EMBED }
		Log.i(TAG, "requestDirectPlay=%s requestDirectStream=%s profileOpus=%s profileAss=%s audioCodec=%s channels=%s sampleRate=%s sourceDirectPlay=%s sourceDirectStream=%s reasons=%s".format(
			options.enableDirectPlay, options.enableDirectStream, opusAllowed, assAllowed, safeCodec(audio?.codec), audio?.channels, audio?.sampleRate,
			source.supportsDirectPlay, source.supportsDirectStream, safeReasons(source.transcodingUrl)))
	}
}
