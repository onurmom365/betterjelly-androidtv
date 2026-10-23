package org.jellyfin.androidtv.ui.playback.segment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.MediaSegmentType

class SegmentCountdownTests : FunSpec({
	val intro = SegmentCountdownCue(60_000, 90_000, MediaSegmentType.INTRO, MediaSegmentAction.SKIP)

	test("no server segments means no countdown") {
		findSegmentCountdown(emptyList(), 57_000) shouldBe null
	}

	test("countdown appears only in the five seconds before the real start") {
		findSegmentCountdown(listOf(intro), 54_999) shouldBe null
		findSegmentCountdown(listOf(intro), 55_000)?.secondsRemaining shouldBe 5
		findSegmentCountdown(listOf(intro), 59_999)?.secondsRemaining shouldBe 1
		findSegmentCountdown(listOf(intro), 60_000) shouldBe null
		findSegmentCountdown(listOf(intro), 70_000) shouldBe null
		findSegmentCountdown(listOf(intro), 90_000) shouldBe null
	}

	test("seconds round up while ring progress follows playback position") {
		findSegmentCountdown(listOf(intro), 57_500)?.secondsRemaining shouldBe 3
		findSegmentCountdown(listOf(intro), 57_500)?.remainingFraction shouldBe 0.5f
		findSegmentCountdown(listOf(intro), 59_000)?.secondsRemaining shouldBe 1
	}

	test("a paused playback position does not advance the countdown") {
		val first = findSegmentCountdown(listOf(intro), 57_500)
		repeat(10) { findSegmentCountdown(listOf(intro), 57_500) shouldBe first }
	}

	test("ask mode remains ask mode and nothing mode has no countdown") {
		val ask = intro.copy(action = MediaSegmentAction.ASK_TO_SKIP)
		findSegmentCountdown(listOf(ask), 57_000)?.cue?.action shouldBe MediaSegmentAction.ASK_TO_SKIP
		findSegmentCountdown(listOf(intro.copy(action = MediaSegmentAction.NOTHING)), 57_000) shouldBe null
	}

	test("intro, credits and recap are supported but unrelated types stay quiet") {
		for (type in listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO, MediaSegmentType.RECAP)) {
			findSegmentCountdown(listOf(intro.copy(type = type)), 57_000)?.cue?.type shouldBe type
		}
		for (type in listOf(MediaSegmentType.COMMERCIAL, MediaSegmentType.PREVIEW)) {
			findSegmentCountdown(listOf(intro.copy(type = type)), 57_000) shouldBe null
		}
	}

	test("invalid boundaries and too-short segments do not produce misleading cues") {
		for (cue in listOf(intro.copy(endMs = 59_000), intro.copy(endMs = 60_000), intro.copy(startMs = -1))) {
			findSegmentCountdown(listOf(cue), 57_000) shouldBe null
		}
		findSegmentCountdown(listOf(intro.copy(endMs = 60_999)), 57_000) shouldBe null
		findSegmentCountdown(listOf(intro.copy(endMs = 61_000)), 57_000)?.cue shouldBe intro.copy(endMs = 61_000)
		val shortAsk = intro.copy(endMs = 62_999, action = MediaSegmentAction.ASK_TO_SKIP)
		findSegmentCountdown(listOf(shortAsk), 57_000) shouldBe null
		findSegmentCountdown(listOf(shortAsk.copy(endMs = 63_000)), 57_000)?.cue?.action shouldBe MediaSegmentAction.ASK_TO_SKIP
	}

	test("earliest upcoming segment wins regardless of repository ordering") {
		val later = intro.copy(startMs = 62_000, endMs = 95_000, type = MediaSegmentType.OUTRO)
		findSegmentCountdown(listOf(later, intro), 58_000)?.cue shouldBe intro
	}

	test("seeking past a segment clears its cue and seeking back recalculates it") {
		findSegmentCountdown(listOf(intro), 100_000) shouldBe null
		findSegmentCountdown(listOf(intro), 57_000)?.secondsRemaining shouldBe 3
		findSegmentCountdown(listOf(intro), 20_000) shouldBe null
	}

	test("a segment starting at zero has no invented lead-in") {
		findSegmentCountdown(listOf(intro.copy(startMs = 0)), 0) shouldBe null
		findSegmentCountdown(listOf(intro), -1) shouldBe null
	}

	test("replacing the item's segments cannot retain an old countdown") {
		findSegmentCountdown(listOf(intro), 57_000)?.secondsRemaining shouldBe 3
		findSegmentCountdown(emptyList(), 57_000) shouldBe null
	}
})
