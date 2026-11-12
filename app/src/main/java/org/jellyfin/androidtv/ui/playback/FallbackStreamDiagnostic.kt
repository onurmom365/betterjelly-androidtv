package org.jellyfin.androidtv.ui.playback

import okhttp3.HttpUrl.Companion.toHttpUrl

internal enum class CopyPermission { ALLOWED, DENIED, UNSPECIFIED, INVALID }

/** Fixed scalar fields only: never retain the supplied URI, unknown parameters, or codec text. */
internal data class SafeCopyRequest(
	val videoCopy: CopyPermission = CopyPermission.UNSPECIFIED,
	val audioCopy: CopyPermission = CopyPermission.UNSPECIFIED,
	val autoCopy: CopyPermission = CopyPermission.UNSPECIFIED,
	val segmentContainer: String = "other",
	val targetVideoBitrate: Int? = null,
	val targetMaxWidth: Int? = null,
	val targetMaxHeight: Int? = null,
)

internal fun summarizeCopyRequest(uri: String?): SafeCopyRequest {
	val url = runCatching { "https://invalid.example/".toHttpUrl().resolve(uri.orEmpty()) }.getOrNull() ?: return SafeCopyRequest()
	fun values(name: String) = (0 until url.querySize).filter { url.queryParameterName(it).equals(name, ignoreCase = true) }
		.map { url.queryParameterValue(it) }
	fun permission(name: String): CopyPermission {
		val values = values(name)
		return when {
			values.isEmpty() -> CopyPermission.UNSPECIFIED
			values.any { it.equals("false", ignoreCase = true) } -> CopyPermission.DENIED
			values.all { it.equals("true", ignoreCase = true) } -> CopyPermission.ALLOWED
			else -> CopyPermission.INVALID
		}
	}
	fun number(name: String) = values(name).singleOrNull()?.takeIf { it.matches(Regex("[0-9]{1,10}")) }?.toIntOrNull()
	return SafeCopyRequest(
		videoCopy = permission("AllowVideoStreamCopy"), audioCopy = permission("AllowAudioStreamCopy"),
		autoCopy = permission("EnableAutoStreamCopy"),
		segmentContainer = values("SegmentContainer").singleOrNull()?.lowercase()?.takeIf { it in setOf("ts", "mp4") } ?: "other",
		targetVideoBitrate = number("VideoBitrate"), targetMaxWidth = number("MaxWidth"), targetMaxHeight = number("MaxHeight"),
	)
}

internal fun safeVideoMime(mime: String?): String = when (mime) {
	"video/hevc" -> "hevc"
	"video/avc" -> "h264"
	"video/dolby-vision" -> "dovi"
	"video/av01" -> "av1"
	"video/x-vnd.on2.vp9" -> "vp9"
	else -> "other"
}
