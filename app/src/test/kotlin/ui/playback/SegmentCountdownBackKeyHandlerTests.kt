package org.jellyfin.androidtv.ui.playback

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SegmentCountdownBackKeyHandlerTests : FunSpec({
	fun SegmentCountdownBackKeyHandler.down(time: Long, visible: Boolean = true, repeat: Int = 0, key: Int = KeyEvent.KEYCODE_BACK) =
		onKey(key, KeyEvent.ACTION_DOWN, repeat, time, visible)
	fun SegmentCountdownBackKeyHandler.up(time: Long, key: Int = KeyEvent.KEYCODE_BACK) =
		onKey(key, KeyEvent.ACTION_UP, 0, time, false)

	test("Back cancels the visible countdown and its release cannot exit playback") {
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100) shouldBe SegmentCountdownBackKeyHandler.Result.CANCEL
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.CONSUMED
	}

	test("a held or duplicated cancellation never becomes a repeated exit action") {
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100) shouldBe SegmentCountdownBackKeyHandler.Result.CANCEL
		repeat(8) { handler.down(100, visible = false, repeat = it) shouldBe SegmentCountdownBackKeyHandler.Result.CONSUMED }
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.CONSUMED
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.CONSUMED
	}

	test("a separate Back press after cancellation keeps its normal navigation behavior") {
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100) shouldBe SegmentCountdownBackKeyHandler.Result.CANCEL
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.CONSUMED
		handler.down(200, visible = false) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
		handler.up(200) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
	}

	test("hidden countdowns including controls guide and popup contexts do not consume Back") {
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100, visible = false) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
	}

	test("gamepad Back and Escape can cancel while other keys keep their current actions") {
		for (key in listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE)) {
			val handler = SegmentCountdownBackKeyHandler()
			handler.down(100, key = key) shouldBe SegmentCountdownBackKeyHandler.Result.CANCEL
			handler.up(100, key) shouldBe SegmentCountdownBackKeyHandler.Result.CONSUMED
		}
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100, key = KeyEvent.KEYCODE_DPAD_CENTER) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
		handler.down(100, key = KeyEvent.KEYCODE_DPAD_LEFT) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
	}

	test("a hold that began before the countdown is not hijacked when it appears") {
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100, visible = false) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
		handler.down(100, repeat = 1) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
	}

	test("leaving playback resets ownership of the cancelled press") {
		val handler = SegmentCountdownBackKeyHandler()
		handler.down(100) shouldBe SegmentCountdownBackKeyHandler.Result.CANCEL
		handler.reset()
		handler.up(100) shouldBe SegmentCountdownBackKeyHandler.Result.PASS
	}
})
