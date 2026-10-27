package org.jellyfin.androidtv.ui.playback.overlay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

// Model Leanback's next/previous grid-position behavior, including clamping.
private fun DpadSeekNavigator.step(current: Long, forward: Boolean, held: Boolean = false): Long {
	val from = positionBeforeStep(current, forward, held)
	val index = positions.binarySearch(from)
	return positions[(index + if (forward) 1 else -1).coerceIn(positions.indices)]
}

class DpadSeekNavigatorTests : FunSpec({
	test("fine taps reverse around the selected moment without restarting the session") {
		val navigator = DpadSeekNavigator(75_321, 200_000, 10_000, 30_000)
		var cursor = 75_321L
		cursor = navigator.step(cursor, true)
		cursor shouldBe 80_321
		cursor = navigator.step(cursor, true)
		cursor shouldBe 85_321
		cursor = navigator.step(cursor, false)
		cursor shouldBe 80_321
		cursor = navigator.step(cursor, false)
		cursor shouldBe 75_321
	}

	test("held repeats retain independent configured backward and forward lengths") {
		val navigator = DpadSeekNavigator(75_000, 200_000, 10_000, 30_000)
		navigator.step(75_000, true, true) shouldBe 105_000
		navigator.step(105_000, false, true) shouldBe 95_000
		navigator.step(95_000, false) shouldBe 90_000
		navigator.step(90_000, true) shouldBe 95_000
	}

	test("custom whole-second hold intervals coexist with five-second taps") {
		val navigator = DpadSeekNavigator(75_000, 200_000, 7_000, 20_000)
		navigator.step(75_000, false, true) shouldBe 68_000
		navigator.step(68_000, true, true) shouldBe 88_000
		navigator.step(88_000, false) shouldBe 83_000
	}

	test("start and end clamp and remain reachable with both fine and held steps") {
		val navigator = DpadSeekNavigator(12_345, 28_765, 10_000, 30_000)
		for (held in listOf(false, true)) {
			navigator.step(0, false, held) shouldBe 0
			navigator.step(28_765, true, held) shouldBe 28_765
		}
		navigator.step(2_345, false) shouldBe 0
		navigator.step(27_345, true) shouldBe 28_765
		navigator.positions.toList().distinct().size shouldBe navigator.positions.size
	}

	test("zero or invalid duration has one safe position") {
		for (duration in listOf(0L, -1L)) {
			val navigator = DpadSeekNavigator(10_000, duration, 0, 0)
			navigator.positions shouldBe longArrayOf(0)
			navigator.step(0, true) shouldBe 0
			navigator.step(0, false) shouldBe 0
		}
	}

	test("malformed subsecond preferences cannot allocate a millisecond thumbnail grid") {
		val navigator = DpadSeekNavigator(0, 3_600_000, 1_001, 30_001)
		navigator.positions.size shouldBe 3_601
	}
})
