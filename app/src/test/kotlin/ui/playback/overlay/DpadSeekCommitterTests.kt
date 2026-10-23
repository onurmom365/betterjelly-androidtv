package org.jellyfin.androidtv.ui.playback.overlay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class SeekScheduler : DpadSeekCommitter.Scheduler {
	private var now = 0L
	private val tasks = mutableMapOf<Runnable, Long>()

	override fun postDelayed(task: Runnable, delayMs: Long) {
		tasks[task] = now + delayMs
	}

	override fun removeCallbacks(task: Runnable) {
		tasks.remove(task)
	}

	fun advanceBy(milliseconds: Long) {
		now += milliseconds
		val due = tasks.filterValues { it <= now }.keys.toList()
		for (task in due) {
			tasks.remove(task)
			task.run()
		}
	}
}

class DpadSeekCommitterTests : FunSpec({
	test("one tap automatically finishes after a short quiet period") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(249)
		finishes shouldBe 0
		scheduler.advanceBy(1)
		finishes shouldBe 1
		scheduler.advanceBy(1_000)
		finishes shouldBe 1
	}

	test("quick consecutive taps keep the same session open until the last release") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		repeat(10) {
			committer.cancel() // key down keeps previewing the current native session
			scheduler.advanceBy(50)
			committer.finishAfterPause { finishes++ }
			scheduler.advanceBy(100)
			finishes shouldBe 0
		}
		scheduler.advanceBy(150)
		finishes shouldBe 1
	}

	test("holding the next press cannot finish the preceding tap in the background") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(100)
		committer.cancel()
		scheduler.advanceBy(5_000)
		finishes shouldBe 0
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(250)
		finishes shouldBe 1
	}

	test("leaving playback cancels pending automatic completion") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.finishAfterPause { finishes++ }
		committer.cancel()
		scheduler.advanceBy(1_000)
		finishes shouldBe 0
	}

	test("a direction-change flush finishes exactly once and removes its timer") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.finishAfterPause { finishes++ }
		committer.flush()
		finishes shouldBe 1
		scheduler.advanceBy(1_000)
		committer.flush()
		finishes shouldBe 1
	}

	test("a replaced release cannot commit an obsolete completion callback") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		val finished = mutableListOf<String>()
		committer.finishAfterPause { finished += "old" }
		scheduler.advanceBy(200)
		committer.finishAfterPause { finished += "new" }
		scheduler.advanceBy(50)
		finished shouldBe emptyList()
		scheduler.advanceBy(200)
		finished shouldBe listOf("new")
	}
})
