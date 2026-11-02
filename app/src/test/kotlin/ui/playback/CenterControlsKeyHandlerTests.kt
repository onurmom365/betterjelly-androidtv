package org.jellyfin.androidtv.ui.playback

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CenterControlsKeyHandlerTests : FunSpec({
	fun CenterControlsKeyHandler.down(time: Long, allowed: Boolean = true, repeat: Int = 0, key: Int = KeyEvent.KEYCODE_DPAD_CENTER) =
		onKey(key, KeyEvent.ACTION_DOWN, repeat, time, allowed)
	fun CenterControlsKeyHandler.up(time: Long, allowed: Boolean = false, key: Int = KeyEvent.KEYCODE_DPAD_CENTER) =
		onKey(key, KeyEvent.ACTION_UP, 0, time, allowed)

	test("hidden controls reveal on down and the release cannot activate the newly focused button") {
		val handler = CenterControlsKeyHandler()
		handler.down(100) shouldBe CenterControlsKeyHandler.Result.REVEAL
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.CONSUMED
	}

	test("holding the reveal press and duplicate dispatch cannot toggle playback or open another action") {
		val handler = CenterControlsKeyHandler()
		handler.down(100) shouldBe CenterControlsKeyHandler.Result.REVEAL
		repeat(10) { handler.down(100, allowed = false, repeat = it) shouldBe CenterControlsKeyHandler.Result.CONSUMED }
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.CONSUMED
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.CONSUMED
	}

	test("a separate second press with visible controls goes to the selected action normally") {
		val handler = CenterControlsKeyHandler()
		handler.down(100) shouldBe CenterControlsKeyHandler.Result.REVEAL
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.CONSUMED
		handler.down(200, allowed = false) shouldBe CenterControlsKeyHandler.Result.PASS
		handler.up(200) shouldBe CenterControlsKeyHandler.Result.PASS
	}

	test("a new press after the overlay fades reveals again") {
		val handler = CenterControlsKeyHandler()
		handler.down(100) shouldBe CenterControlsKeyHandler.Result.REVEAL
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.CONSUMED
		handler.down(5_000) shouldBe CenterControlsKeyHandler.Result.REVEAL
	}

	test("Enter follows the same reveal only contract") {
		val handler = CenterControlsKeyHandler()
		handler.down(100, key = KeyEvent.KEYCODE_ENTER) shouldBe CenterControlsKeyHandler.Result.REVEAL
		handler.up(100, key = KeyEvent.KEYCODE_ENTER) shouldBe CenterControlsKeyHandler.Result.CONSUMED
	}

	test("guides popups segment prompts and visible controls retain their existing center action") {
		val handler = CenterControlsKeyHandler()
		handler.down(100, allowed = false) shouldBe CenterControlsKeyHandler.Result.PASS
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.PASS
	}

	test("a hold or release which began in another UI context is not taken over") {
		val handler = CenterControlsKeyHandler()
		handler.down(100, allowed = false) shouldBe CenterControlsKeyHandler.Result.PASS
		handler.down(100, repeat = 1) shouldBe CenterControlsKeyHandler.Result.PASS
		handler.up(100, allowed = true) shouldBe CenterControlsKeyHandler.Result.PASS
	}

	test("direction and dedicated media keys remain unchanged") {
		val handler = CenterControlsKeyHandler()
		for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
			handler.down(100, key = key) shouldBe CenterControlsKeyHandler.Result.PASS
			handler.up(100, key = key) shouldBe CenterControlsKeyHandler.Result.PASS
		}
	}

	test("leaving playback clears ownership of the original press") {
		val handler = CenterControlsKeyHandler()
		handler.down(100) shouldBe CenterControlsKeyHandler.Result.REVEAL
		handler.reset()
		handler.up(100) shouldBe CenterControlsKeyHandler.Result.PASS
	}
})
