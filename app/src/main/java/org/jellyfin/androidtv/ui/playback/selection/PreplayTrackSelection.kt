package org.jellyfin.androidtv.ui.playback.selection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

/** A manual choice, never a language-priority or automatic failover rule. No credentials or media URLs. */
@Serializable
data class PreplayTrackChoice(
	val mode: String = "default",
	val itemId: String? = null,
	val sourceId: String? = null,
	val index: Int? = null,
	val language: String? = null,
	val title: String? = null,
	val codec: String? = null,
	val channels: Int? = null,
	val forced: Boolean = false,
	val hearingImpaired: Boolean = false,
	val label: String? = null,
	val revision: Long = 0,
) {
	companion object {
		fun track(item: BaseItemDto, source: MediaSourceInfo, stream: MediaStream) = PreplayTrackChoice(
			mode = "track", itemId = item.id.toString(), sourceId = source.id, index = stream.index,
			language = stream.language?.lowercase(), title = stream.title, codec = stream.codec?.lowercase(),
			channels = stream.channels, forced = stream.isForced, hearingImpaired = stream.isHearingImpaired,
			label = stream.displayTitle ?: stream.title ?: stream.language,
		)
	}

	fun matches(stream: MediaStream) = language == stream.language?.lowercase() && title == stream.title &&
		codec == stream.codec?.lowercase() && channels == stream.channels && forced == stream.isForced &&
		hearingImpaired == stream.isHearingImpaired

	fun resolve(item: BaseItemDto, source: MediaSourceInfo, type: MediaStreamType): Int? {
		if (mode == "off" && type == MediaStreamType.SUBTITLE) return -1
		if (mode != "track") return null
		val matches = sourceStreams(item, source).filter { it.type == type && matches(it) }
		// An index is meaningful only in the original item AND media source, with unchanged track metadata.
		if (itemId == item.id.toString() && sourceId == source.id) return matches.firstOrNull { it.index == index }?.index
		return matches.singleOrNull()?.index
	}
}

private fun sourceStreams(item: BaseItemDto, source: MediaSourceInfo): List<MediaStream> {
	// Library list DTOs can have streams only on the item. Never borrow them across versions.
	val supplied = source.mediaStreams.orEmpty()
	if (item.mediaSources?.size != 1) return supplied
	return supplied + item.mediaStreams.orEmpty().filter { candidate ->
		supplied.none { it.index == candidate.index && it.type == candidate.type }
	}
}

interface PreplaySelectionStorage {
	fun read(key: String): String?
	fun write(key: String, value: String?)
}

class PreplayTrackSelection(
	private val storage: PreplaySelectionStorage,
	private val account: () -> String?,
) {
	private val json = Json { ignoreUnknownKeys = true }
	fun accountIdentity() = account()
	private fun key(id: UUID, type: MediaStreamType): String? = account()?.let { "$it/$id/$type" }

	fun read(id: UUID, type: MediaStreamType): PreplayTrackChoice? = key(id, type)?.let(storage::read)?.let {
		runCatching { json.decodeFromString<PreplayTrackChoice>(it) }.getOrNull()
	}

	@Synchronized
	fun save(id: UUID, type: MediaStreamType, choice: PreplayTrackChoice?) {
		require(type == MediaStreamType.AUDIO || type == MediaStreamType.SUBTITLE)
		val account = account() ?: return
		val key = "$account/$id/$type"
		if (choice == null) { storage.write(key, null); return }
		val revisionKey = "$account/track-choice-revision"
		val revision = (storage.read(revisionKey)?.toLongOrNull() ?: 0).coerceIn(0, Long.MAX_VALUE - 1) + 1
		storage.write(revisionKey, revision.toString())
		storage.write(key, json.encodeToString(choice.copy(revision = revision)))
	}

	/** A new series choice invalidates older per-episode overrides without enumerating server episodes. */
	fun activeOverride(item: BaseItemDto, type: MediaStreamType): PreplayTrackChoice? {
		val own = read(item.id, type) ?: return null
		val series = item.seriesId?.let { read(it, type) } ?: return own
		// Legacy records have no edit order (revision 0); the requested series-wide choice wins that tie.
		return own.takeIf { it.revision > series.revision }
	}

	fun effective(item: BaseItemDto, type: MediaStreamType): PreplayTrackChoice? = activeOverride(item, type)
		?: item.seriesId?.let { read(it, type) }

	fun source(item: BaseItemDto): MediaSourceInfo? {
		val sources = item.mediaSources.orEmpty()
		for (type in listOf(MediaStreamType.AUDIO, MediaStreamType.SUBTITLE)) {
			val choice = activeOverride(item, type)
			if (choice?.mode == "track" && choice.itemId == item.id.toString()) {
				sources.firstOrNull { it.id == choice.sourceId }?.let { return it }
			}
		}
		return sources.firstOrNull { it.id == item.id.toString().replace("-", "") || it.id == item.id.toString() }
			?: sources.firstOrNull()
	}

	/** Called once for each new item, before PlaybackInfo. Stream restarts keep the player's current choice. */
	fun apply(item: BaseItemDto, options: VideoOptions) {
		if (item.type !in listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE)) return
		val audio = effective(item, MediaStreamType.AUDIO)
		val subtitles = effective(item, MediaStreamType.SUBTITLE)
		val source = source(item)
		val streams = source?.let { sourceStreams(item, it) }.orEmpty()
		options.preplayNeedsMetadata = (audio?.mode == "track" && streams.none { it.type == MediaStreamType.AUDIO }) ||
			(subtitles?.mode == "track" && streams.none { it.type == MediaStreamType.SUBTITLE })
		options.preplayAudioExplicit = audio != null
		options.audioStreamIndex = source?.let { audio?.resolve(item, it, MediaStreamType.AUDIO) }
		options.subtitleStreamIndex = if (subtitles?.mode == "off") -1 else source?.let { subtitles?.resolve(item, it, MediaStreamType.SUBTITLE) }
		options.manualAudioSelection = audio?.mode == "track" && options.audioStreamIndex != null
		options.manualSubtitleSelection = subtitles?.mode == "off" || (subtitles?.mode == "track" && options.subtitleStreamIndex != null)
		if (source != null && (options.audioStreamIndex != null || options.subtitleStreamIndex != null)) options.mediaSourceId = source.id
	}
}
