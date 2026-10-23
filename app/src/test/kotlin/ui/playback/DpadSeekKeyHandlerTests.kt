package org.jellyfin.androidtv.ui.playback

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DpadSeekKeyHandlerTests : FunSpec({

	fun DpadSeekKeyHandler.down(key: Int, time: Long, repeats: Int = 0, allowed: Boolean = true) =
		onKey(key, KeyEvent.ACTION_DOWN, repeats, time, allowed)

	fun DpadSeekKeyHandler.up(key: Int, time: Long, allowed: Boolean = true) =
		onKey(key, KeyEvent.ACTION_UP, 0, time, allowed)

	test("a single press seeks once on down and consumes its release") {
		for ((key, result) in listOf(
			KeyEvent.KEYCODE_DPAD_LEFT to DpadSeekKeyHandler.Result.REWIND,
			KeyEvent.KEYCODE_DPAD_RIGHT to DpadSeekKeyHandler.Result.FORWARD,
		)) {
			val handler = DpadSeekKeyHandler()
			handler.down(key, 100) shouldBe result
			handler.up(key, 100) shouldBe DpadSeekKeyHandler.Result.CONSUMED
		}
	}

	test("duplicate dispatch and held-key repeats do not issue extra seeks") {
		val handler = DpadSeekKeyHandler()
		val key = KeyEvent.KEYCODE_DPAD_RIGHT
		handler.down(key, 100) shouldBe DpadSeekKeyHandler.Result.FORWARD
		handler.down(key, 100) shouldBe DpadSeekKeyHandler.Result.CONSUMED
		for (repeat in 1..20) {
			handler.down(key, 100, repeats = repeat) shouldBe DpadSeekKeyHandler.Result.CONSUMED
		}
		handler.up(key, 100) shouldBe DpadSeekKeyHandler.Result.CONSUMED
	}

	test("rapid distinct taps each issue one seek, including direction changes") {
		val handler = DpadSeekKeyHandler()
		for (time in 100L..110L) {
			val forward = time % 2 == 0L
			val key = if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
			handler.down(key, time) shouldBe if (forward) DpadSeekKeyHandler.Result.FORWARD else DpadSeekKeyHandler.Result.REWIND
			handler.up(key, time) shouldBe DpadSeekKeyHandler.Result.CONSUMED
		}
	}

	test("visible controls, guide, popups and nonseekable playback retain their key events") {
		val handler = DpadSeekKeyHandler()
		for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
			handler.down(key, 100, allowed = false) shouldBe DpadSeekKeyHandler.Result.PASS
			handler.up(key, 100, allowed = false) shouldBe DpadSeekKeyHandler.Result.PASS
			// Controls hiding during an existing hold must not turn it into a seek.
			handler.down(key, 100, repeats = 1) shouldBe DpadSeekKeyHandler.Result.PASS
		}
	}

	test("up, down, OK and media keys remain available to the existing player UI") {
		val handler = DpadSeekKeyHandler()
		for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
			handler.down(key, 100) shouldBe DpadSeekKeyHandler.Result.PASS
			handler.up(key, 100) shouldBe DpadSeekKeyHandler.Result.PASS
		}
	}

	test("a seek press stays consumed if an overlay appears before release") {
		val handler = DpadSeekKeyHandler()
		val key = KeyEvent.KEYCODE_DPAD_LEFT
		handler.down(key, 100) shouldBe DpadSeekKeyHandler.Result.REWIND
		handler.down(key, 100, repeats = 1, allowed = false) shouldBe DpadSeekKeyHandler.Result.CONSUMED
		handler.up(key, 100, allowed = false) shouldBe DpadSeekKeyHandler.Result.CONSUMED
	}

	test("a late release from an old press cannot consume a newer press") {
		val handler = DpadSeekKeyHandler()
		val key = KeyEvent.KEYCODE_DPAD_LEFT
		handler.down(key, 100) shouldBe DpadSeekKeyHandler.Result.REWIND
		handler.down(key, 200) shouldBe DpadSeekKeyHandler.Result.REWIND
		handler.up(key, 100) shouldBe DpadSeekKeyHandler.Result.PASS
		handler.up(key, 200) shouldBe DpadSeekKeyHandler.Result.CONSUMED
	}

	test("leaving playback resets an unfinished press") {
		val handler = DpadSeekKeyHandler()
		val key = KeyEvent.KEYCODE_DPAD_RIGHT
		handler.down(key, 100) shouldBe DpadSeekKeyHandler.Result.FORWARD
		handler.reset()
		handler.up(key, 100) shouldBe DpadSeekKeyHandler.Result.PASS
		handler.down(key, 200) shouldBe DpadSeekKeyHandler.Result.FORWARD
	}
})
