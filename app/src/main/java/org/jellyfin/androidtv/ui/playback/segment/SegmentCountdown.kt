package org.jellyfin.androidtv.ui.playback.segment

import org.jellyfin.sdk.model.api.MediaSegmentType

data class SegmentCountdownCue(
	val startMs: Long,
	val endMs: Long,
	val type: MediaSegmentType,
	val action: MediaSegmentAction,
)

data class SegmentCountdown(
	val cue: SegmentCountdownCue,
	val secondsRemaining: Int,
	val remainingFraction: Float,
)

private const val COUNTDOWN_WINDOW_MS = 5_000L

/** A passive cue before a real segment starts; it never schedules a skip or changes playback. */
fun findSegmentCountdown(cues: List<SegmentCountdownCue>, positionMs: Long): SegmentCountdown? {
	if (positionMs < 0) return null
	val cue = cues.asSequence()
		.filter { it.type == MediaSegmentType.INTRO || it.type == MediaSegmentType.OUTRO || it.type == MediaSegmentType.RECAP }
		.filter { it.startMs >= 0 && it.endMs > it.startMs }
		.filter {
			val minimumDuration = when (it.action) {
				MediaSegmentAction.SKIP -> MediaSegmentRepository.SkipMinDuration.inWholeMilliseconds
				MediaSegmentAction.ASK_TO_SKIP -> MediaSegmentRepository.AskToSkipMinDuration.inWholeMilliseconds
				MediaSegmentAction.NOTHING -> return@filter false
			}
			it.endMs - it.startMs >= minimumDuration
		}
		.filter { it.startMs - positionMs in 1..COUNTDOWN_WINDOW_MS }
		.minByOrNull { it.startMs }
		?: return null
	val remainingMs = cue.startMs - positionMs
	return SegmentCountdown(cue, ((remainingMs + 999) / 1_000).toInt(), remainingMs.toFloat() / COUNTDOWN_WINDOW_MS)
}
