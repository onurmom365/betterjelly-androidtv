package org.jellyfin.androidtv.ui.playback.segment

import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID

/** Main-thread playback state shared by the countdown and the scheduled segment action. */
class SegmentSkipSession {
	private data class SegmentKey(val start: Long, val end: Long, val type: MediaSegmentType)
	private fun SegmentCountdownCue.key() = SegmentKey(startMs, endMs, type)
	private var itemId: UUID? = null
	private var generation = 0L
	private var cues = emptyList<SegmentCountdownCue>()
	private val dismissed = mutableSetOf<SegmentKey>()

	fun begin(item: UUID): Long {
		if (itemId != item) dismissed.clear()
		itemId = item
		cues = emptyList()
		return ++generation
	}

	fun isCurrent(token: Long) = itemId != null && token == generation

	fun setCues(token: Long, value: List<SegmentCountdownCue>) {
		if (isCurrent(token)) cues = value.toList()
	}

	val countdowns: List<SegmentCountdownCue>
		get() = cues.filterNot { it.key() in dismissed }

	fun dismiss(cue: SegmentCountdownCue): Boolean {
		if (cue !in cues || cue.action == MediaSegmentAction.NOTHING) return false
		return dismissed.add(cue.key())
	}

	// Check at action execution, not just scheduling: Back may arrive while a message is queued.
	fun shouldAct(token: Long, cue: SegmentCountdownCue) =
		isCurrent(token) && cue in cues && cue.action != MediaSegmentAction.NOTHING && cue.key() !in dismissed
}
