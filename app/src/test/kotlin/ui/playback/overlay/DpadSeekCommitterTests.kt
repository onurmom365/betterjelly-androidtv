package org.jellyfin.androidtv.ui.playback.overlay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class SeekScheduler : DpadSeekCommitter.Scheduler {
	private var now = 0L
	private val tasks = mutableMapOf<Runnable, Long>()

	override fun postDelayed(task: Runnable, delayMs: Long) { tasks[task] = now + delayMs }
	override fun removeCallbacks(task: Runnable) { tasks.remove(task) }

	fun advanceBy(milliseconds: Long) {
		now += milliseconds
		for (task in tasks.filterValues { it <= now }.keys.toList()) {
			tasks.remove(task)
			task.run()
		}
	}
}

class DpadSeekCommitterTests : FunSpec({
	test("one tap gives time to inspect trickplay then resumes exactly once without OK") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.onKeyDown(0)
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(1_499)
		finishes shouldBe 0
		scheduler.advanceBy(1)
		finishes shouldBe 1
		scheduler.advanceBy(5_000)
		finishes shouldBe 1
	}

	test("deliberate taps one second apart keep one preview instead of returning to playback") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		repeat(8) {
			committer.onKeyDown(0)
			committer.finishAfterPause { finishes++ }
			scheduler.advanceBy(1_000)
			finishes shouldBe 0
		}
		scheduler.advanceBy(500)
		finishes shouldBe 1
	}

	test("rapid taps also finish only after the final release") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		repeat(10) {
			committer.onKeyDown(0)
			committer.finishAfterPause { finishes++ }
			scheduler.advanceBy(100)
			finishes shouldBe 0
		}
		scheduler.advanceBy(1_400)
		finishes shouldBe 1
	}

	test("holding cancels the preceding tap and keeps the accepted quick release timing") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.onKeyDown(0)
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(1_000)
		committer.onKeyDown(0)
		committer.onKeyDown(1)
		scheduler.advanceBy(5_000)
		finishes shouldBe 0
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(249)
		finishes shouldBe 0
		scheduler.advanceBy(1)
		finishes shouldBe 1
	}

	test("a fine tap after a hold gets the full inspection window") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.onKeyDown(3)
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(100)
		committer.onKeyDown(0)
		committer.finishAfterPause { finishes++ }
		scheduler.advanceBy(1_499)
		finishes shouldBe 0
		scheduler.advanceBy(1)
		finishes shouldBe 1
	}

	test("leaving playback or explicit cancellation removes pending completion") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		var finishes = 0
		committer.finishAfterPause { finishes++ }
		committer.cancel()
		scheduler.advanceBy(5_000)
		finishes shouldBe 0
	}

	test("a replaced release cannot commit an obsolete callback") {
		val scheduler = SeekScheduler()
		val committer = DpadSeekCommitter(scheduler)
		val finished = mutableListOf<String>()
		committer.finishAfterPause { finished += "old" }
		scheduler.advanceBy(1_000)
		committer.onKeyDown(0)
		committer.finishAfterPause { finished += "new" }
		scheduler.advanceBy(500)
		finished shouldBe emptyList()
		scheduler.advanceBy(1_000)
		finished shouldBe listOf("new")
	}
})
