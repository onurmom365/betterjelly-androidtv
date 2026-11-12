package org.jellyfin.androidtv.ui.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import android.util.Log
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.concurrent.atomic.AtomicInteger
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

enum class SubtitleSelectionOutcome { WAITING, APPLIED, OFF, MISSING_STREAM }
enum class SubtitleCheckpoint { CREATED, REQUEST, START_ITEM, START_READY, PREPARED, PAUSED_RESUME, BURNED_PATH,
	SET_REQUEST, UNCHANGED, ENCODE_REQUEST, REMOVE_BURN_REQUEST, REMOTE_REQUEST, REMOTE_IGNORED, CC_OPEN, STOP }

/** Deliberately excludes URLs, IDs, titles, paths, headers and arbitrary exception/codec text. */
object PlaybackCapabilitiesDiagnostic {
	const val TAG = "BetterJellyCapabilities"
	private val controllerSequence = AtomicInteger()
	@JvmStatic fun nextController() = controllerSequence.incrementAndGet()
	@JvmStatic fun playerReady(subscribed: Boolean) { Log.i(TAG, "playerPlaying=true controllerSubscribed=$subscribed") }
	@JvmStatic fun remoteCommand(type: MediaStreamType, index: Int, userControlled: Boolean, accepted: Boolean) {
		Log.i(TAG, "remoteTrackType=$type remoteIndex=$index hasControllingUser=$userControlled accepted=$accepted")
	}
	@JvmStatic fun checkpoint(stage: SubtitleCheckpoint, controller: Int, requested: Int?, current: Int?, sourceDefault: Int?,
		delivery: SubtitleDeliveryMethod?, playMethod: PlayMethod?, paused: Boolean, burning: Boolean, force: Boolean) {
		// Enums, per-process anonymous counters, stream numbers and booleans only. No object/string payloads.
		Log.i(TAG, "controller=$controller checkpoint=$stage requested=$requested current=$current sourceDefault=$sourceDefault delivery=$delivery playMethod=$playMethod paused=$paused burning=$burning force=$force")
	}
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
	@JvmStatic fun playerError(error: PlaybackException) {
		playerError(error.errorCode)
		val safe = summarizePlaybackFailure(error)
		Log.i(TAG, "errorCauseTypes=${safe.causes.joinToString("|")} parserComponent=${safe.parser} parserLine=${safe.parserLine} parserRejection=${safe.rejection} parserValue=${safe.rejectedValue} exoErrorType=${(error as? ExoPlaybackException)?.type ?: -1}")
	}
	internal fun safeVideoCodec(value: String?) = value?.lowercase()?.takeIf {
		it in setOf("h264", "hevc", "h265", "av1", "mpeg2video", "mpeg4", "vc1", "vp8", "vp9")
	} ?: "other"
	internal fun safeContainer(value: String?) = value?.lowercase()?.takeIf {
		it in setOf("mkv", "matroska", "mp4", "mov", "mpegts", "ts", "webm", "m4v", "avi", "asf", "ogg", "wmv")
	} ?: "other"
	@JvmStatic fun subtitle(index: Int?, outcome: SubtitleSelectionOutcome, groups: Int) {
		Log.i(TAG, "subtitleIndex=$index subtitleOutcome=$outcome textGroups=$groups")
	}
	internal fun safeSelectionMode(value: String?) = value?.takeIf { it in setOf("default", "off", "track") } ?: "none"
	@JvmStatic fun preplay(audioMode: String?, subtitleMode: String?, audioIndex: Int?, subtitleIndex: Int?) {
		Log.i(TAG, "preplayAudio=${safeSelectionMode(audioMode)} preplaySubtitle=${safeSelectionMode(subtitleMode)} resolvedAudioIndex=$audioIndex resolvedSubtitleIndex=$subtitleIndex")
	}
	internal fun negotiation(options: VideoOptions, response: PlaybackInfoResponse) {
		val source = response.mediaSources.firstOrNull { it.id == options.mediaSourceId } ?: response.mediaSources.firstOrNull() ?: return
		val video = source.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO }
		Log.i(TAG, "sourceVideoBitrate=${video?.bitRate} sourceVideoLevel=${video?.level} sourceRotation=${video?.rotation}")
		copyRequest("NEGOTIATED", source.transcodingUrl)
		Log.i(TAG, "container=${safeContainer(source.container)} videoCodec=${safeVideoCodec(video?.codec)} width=${video?.width} height=${video?.height} bitDepth=${video?.bitDepth} videoRange=${video?.videoRangeType?.serialName}")
		val audio = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }
			.let { tracks -> tracks.firstOrNull { it.index == (options.audioStreamIndex ?: source.defaultAudioStreamIndex) } ?: tracks.firstOrNull() }
		val opusAllowed = options.profile?.directPlayProfiles.orEmpty().any { "opus" in it.audioCodec.orEmpty().split(',') }
		val assAllowed = options.profile?.subtitleProfiles.orEmpty().any { it.format == "ass" && it.method == SubtitleDeliveryMethod.EMBED }
		Log.i(TAG, "requestedSubtitleIndex=${options.subtitleStreamIndex} responseSubtitleIndex=${source.defaultSubtitleStreamIndex}")
		Log.i(TAG, "requestDirectPlay=%s requestDirectStream=%s profileOpus=%s profileAss=%s audioCodec=%s channels=%s sampleRate=%s sourceDirectPlay=%s sourceDirectStream=%s reasons=%s".format(
			options.enableDirectPlay, options.enableDirectStream, opusAllowed, assAllowed, safeCodec(audio?.codec), audio?.channels, audio?.sampleRate,
			source.supportsDirectPlay, source.supportsDirectStream, safeReasons(source.transcodingUrl)))
	}
	private fun copyRequest(stage: String, uri: String?) {
		val safe = summarizeCopyRequest(uri)
		Log.i(TAG, "fallbackStage=$stage videoCopy=${safe.videoCopy} audioCopy=${safe.audioCopy} autoCopy=${safe.autoCopy} segmentContainer=${safe.segmentContainer} targetVideoBitrate=${safe.targetVideoBitrate} targetMaxWidth=${safe.targetMaxWidth} targetMaxHeight=${safe.targetMaxHeight}")
	}
	@JvmStatic fun hlsVariants(manifest: HlsManifest) {
		val variants = manifest.multivariantPlaylist.variants
		Log.i(TAG, "hlsVariantCount=${variants.size}")
		variants.take(12).forEachIndexed { index, variant ->
			val format = variant.format
			Log.i(TAG, "hlsVariant=$index videoCodec=${safeVideoMime(MimeTypes.getVideoMediaMimeType(format.codecs))} width=${format.width} height=${format.height} colorTransfer=${format.colorInfo?.colorTransfer} trackBitrate=${format.bitrate} frameMilli=${frameMilli(format.frameRate)}")
			copyRequest("OFFERED", variant.url.toString())
		}
	}
	@JvmStatic fun hlsSelected(uri: String) { copyRequest("SELECTED", uri) }
	@JvmStatic fun remuxRecovery() { Log.i(TAG, "containerRemuxPreferred=true") }
	@JvmStatic fun remuxState(recovery: ContainerRemuxRecovery) {
		val state = recovery.diagnostic()
		Log.i(TAG, "remuxGate=${state.gate} remuxEnabled=${state.enabled} remuxSameItem=${state.sameItem} remuxSameSource=${state.sameSource} remuxIdMatches=${state.idMatches} remuxMimeMatches=${state.mimeMatches} remuxCandidates=${state.candidates}")
	}
	@JvmStatic fun videoTracks(tracks: Tracks) {
		tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.take(12).forEachIndexed { groupIndex, group ->
			(0 until minOf(group.length, 12)).forEach { index ->
				val format = group.getTrackFormat(index)
				Log.i(TAG, "videoGroup=$groupIndex videoTrack=$index trackSelected=${group.isTrackSelected(index)} trackSupport=${group.getTrackSupport(index)} videoCodec=${safeVideoMime(format.sampleMimeType)} width=${format.width} height=${format.height} colorTransfer=${format.colorInfo?.colorTransfer} trackBitrate=${format.bitrate} frameMilli=${frameMilli(format.frameRate)}")
			}
		}
	}
	private fun frameMilli(value: Float): Int = if (value.isFinite() && value in 0f..1000f) (value * 1000).toInt() else -1
}
