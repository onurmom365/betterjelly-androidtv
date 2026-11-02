package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Short-lived return-to-details state, independent of the server's Continue Watching thresholds. */
class PlaybackReturnPosition(private val account: () -> String?, private val now: () -> Long = System::currentTimeMillis) {
	private data class Position(val ticks: Long, val expires: Long)
	private val positions = LinkedHashMap<Pair<String, UUID>, Position>()
	private var startedFor: Pair<String, UUID>? = null
	private fun key(id: UUID) = account()?.let { it to id }
	fun started(item: BaseItemDto, ticks: Long) {
		startedFor = key(item.id)
		if (ticks == 0L) clear(item.id)
	}

	fun clear(id: UUID) { key(id)?.let(positions::remove) }
	fun stopped(item: BaseItemDto, ticks: Long?, completed: Boolean) {
		if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.EPISODE) return
		val key = key(item.id) ?: return
		if (startedFor != null && startedFor != key) return
		positions.entries.removeAll { it.value.expires <= now() }
		if (completed || ticks == null || ticks <= 0 || (item.runTimeTicks != null && ticks >= item.runTimeTicks!!)) {
			positions.remove(key)
			return
		}
		positions.remove(key)
		positions[key] = Position(ticks, now() + 30 * 60 * 1000)
		while (positions.size > 32) positions.remove(positions.keys.first())
	}

	fun apply(item: BaseItemDto): BaseItemDto {
		val key = key(item.id) ?: return item
		val position = positions[key] ?: return item
		if (position.expires <= now()) { positions.remove(key); return item }
		val data = item.userData ?: return item
		return item.copy(userData = data.copy(playbackPositionTicks = position.ticks,
			playedPercentage = item.runTimeTicks?.takeIf { it > 0 }?.let { position.ticks * 100.0 / it } ?: data.playedPercentage))
	}
}
