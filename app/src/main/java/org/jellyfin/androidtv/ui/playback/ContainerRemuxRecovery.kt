package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.hls.HlsManifest
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

enum class RemuxGate { DIRECT_START, MISSING_IDENTITY, LIVE_SOURCE, DIFFERENT_SOURCE, NOT_ARMED, ARMED, RETRY_READY, MAPPING, NO_EQUIVALENT, CHOSEN }
data class SafeRemuxState(val gate: RemuxGate, val enabled: Boolean, val sameItem: Boolean, val sameSource: Boolean,
	val idMatches: Int, val mimeMatches: Int, val candidates: Int)

/** Scoped to one player's same-item retry; never changes server capability declarations or URLs. */
class ContainerRemuxRecovery {
	private data class Source(val item: UUID, val mediaSource: String)
	private var current: Source? = null
	private var pending: Source? = null
	private var directMkv = false
	var enabled = false
		private set
	private var gate = RemuxGate.NOT_ARMED
	private var sameItem = false
	private var sameSource = false
	private var idMatches = 0
	private var mimeMatches = 0
	private var mappedCandidates = 0
	fun diagnostic() = SafeRemuxState(gate, enabled, sameItem, sameSource, idMatches, mimeMatches, mappedCandidates)

	fun prepare(item: UUID?, mediaSource: String?, method: PlayMethod?, container: String?, live: Boolean) {
		sameItem = pending != null && pending?.item == item
		sameSource = pending != null && pending?.mediaSource == mediaSource
		current = if (item != null && !mediaSource.isNullOrEmpty() && !live) Source(item, mediaSource) else null
		directMkv = current != null && method == PlayMethod.DIRECT_PLAY && container?.lowercase() in setOf("mkv", "matroska")
		if (method == PlayMethod.DIRECT_PLAY || current == null || current != pending) pending = null
		enabled = pending != null && current == pending && method != PlayMethod.DIRECT_PLAY
		gate = when {
			live -> RemuxGate.LIVE_SOURCE
			current == null -> RemuxGate.MISSING_IDENTITY
			method == PlayMethod.DIRECT_PLAY -> RemuxGate.DIRECT_START
			enabled -> RemuxGate.RETRY_READY
			sameItem && !sameSource -> RemuxGate.DIFFERENT_SOURCE
			else -> RemuxGate.NOT_ARMED
		}
		idMatches = 0; mimeMatches = 0; mappedCandidates = 0
	}

	fun onError(error: Throwable) {
		val failure = summarizePlaybackFailure(error)
		pending = current.takeIf {
			directMkv && failure.parser == ParserComponent.MATROSKA &&
				failure.rejection == ParserRejection.COMPRESSION_ALGORITHM && failure.rejectedValue == 0L
		}
		enabled = false // Any error on the remux retry returns to ordinary fallback.
		gate = if (pending != null) RemuxGate.ARMED else RemuxGate.NOT_ARMED
	}

	fun findOverride(tracks: Tracks, manifest: HlsManifest): TrackSelectionOverride? {
		if (!enabled) return null
		gate = RemuxGate.MAPPING
		idMatches = 0; mimeMatches = 0; mappedCandidates = 0
		val candidates = tracks.groups.flatMapIndexed { groupIndex, group ->
			if (group.type != C.TRACK_TYPE_VIDEO) return@flatMapIndexed emptyList()
			(0 until group.length).mapNotNull { index ->
				val format = group.getTrackFormat(index)
				idMatches += manifest.multivariantPlaylist.variants.count { format.id != null && it.format.id == format.id }
				mimeMatches += manifest.multivariantPlaylist.variants.count { MimeTypes.getVideoMediaMimeType(it.format.codecs) == format.sampleMimeType }
				// Media3 preserves the manifest format ID when deriving HLS video tracks.
				// Refuse ambiguous mappings rather than guessing from codec or list position.
				val variant = manifest.multivariantPlaylist.variants.singleOrNull {
					format.id != null && it.format.id == format.id && it.format.width == format.width &&
						it.format.height == format.height && MimeTypes.getVideoMediaMimeType(it.format.codecs) == format.sampleMimeType
				} ?: return@mapNotNull null
				val copy = summarizeCopyRequest(variant.url.toString())
				RemuxVideoTrack(groupIndex, index, format.width, format.height, format.bitrate, format.frameRate,
					group.getTrackSupport(index) == C.FORMAT_HANDLED, group.isTrackSelected(index), copy.videoCopy, copy.autoCopy)
			}
		}
		mappedCandidates = candidates.size
		val chosen = selectSupportedRemux(candidates) ?: run { gate = RemuxGate.NO_EQUIVALENT; return null }
		gate = RemuxGate.CHOSEN
		return TrackSelectionOverride(tracks.groups[chosen.group].mediaTrackGroup, chosen.track)
	}
}

internal data class RemuxVideoTrack(
	val group: Int, val track: Int, val width: Int, val height: Int, val bitrate: Int, val frameRate: Float,
	val handled: Boolean, val selected: Boolean, val videoCopy: CopyPermission, val autoCopy: CopyPermission,
)

/** Prefer an equally sized/equally rated supported remux only over an explicitly forced encode. */
internal fun selectSupportedRemux(tracks: List<RemuxVideoTrack>): RemuxVideoTrack? {
	val selected = tracks.singleOrNull { it.selected } ?: return null
	if (selected.videoCopy != CopyPermission.DENIED || selected.width <= 0 || selected.height <= 0 || selected.bitrate <= 0) return null
	return tracks.filter {
		it.handled && !it.selected && it.videoCopy in setOf(CopyPermission.ALLOWED, CopyPermission.UNSPECIFIED) &&
			it.autoCopy in setOf(CopyPermission.ALLOWED, CopyPermission.UNSPECIFIED) &&
			it.width == selected.width && it.height == selected.height && it.bitrate == selected.bitrate && it.frameRate == selected.frameRate
	}.singleOrNull()
}
