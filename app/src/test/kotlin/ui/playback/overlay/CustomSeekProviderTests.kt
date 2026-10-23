package org.jellyfin.androidtv.ui.playback.overlay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class CustomSeekProviderTests : FunSpec({
	test("CustomSeekProvider.seekPositions with simple duration") {
		val videoPlayerAdapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns true
			every { duration } returns 30000L
		}
		val customSeekProvider = CustomSeekProvider(videoPlayerAdapter, mockk(), mockk(), mockk(), false, 10_000)

		customSeekProvider.seekPositions shouldBe arrayOf(0L, 10000L, 20000L, 30000L)
	}

	test("CustomSeekProvider.seekPositions with odd duration") {
		val videoPlayerAdapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns true
			every { duration } returns 45000L
		}
		val customSeekProvider = CustomSeekProvider(videoPlayerAdapter, mockk(), mockk(), mockk(), false, 10_000)

		customSeekProvider.seekPositions shouldBe arrayOf(0L, 10000, 20000, 30000, 40000, 45000)
	}

	test("CustomSeekProvider.seekPositions with seek disabled") {
		val videoPlayerAdapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns false
		}
		val customSeekProvider = CustomSeekProvider(videoPlayerAdapter, mockk(), mockk(), mockk(), false, 10_000)

		customSeekProvider.seekPositions.size shouldBe 0
	}

	test("relative seek grid preserves 10 second back and 30 second forward steps around current position") {
		val adapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns true
			every { duration } returns 200_000L
		}
		val provider = CustomSeekProvider(adapter, mockk(), mockk(), mockk(), false, 30_000)
		provider.setRelativeSeekAnchor(75_000, 10_000)

		provider.seekPositions shouldBe longArrayOf(
			0, 5_000, 15_000, 25_000, 35_000, 45_000, 55_000, 65_000,
			75_000, 105_000, 135_000, 165_000, 195_000, 200_000,
		)
	}

	test("relative grid clamps start and end positions without duplicates") {
		val adapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns true
			every { duration } returns 25_000L
		}
		val provider = CustomSeekProvider(adapter, mockk(), mockk(), mockk(), false, 30_000)
		provider.setRelativeSeekAnchor(-1, 10_000)
		provider.seekPositions shouldBe longArrayOf(0, 25_000)
		provider.setRelativeSeekAnchor(30_000, 10_000)
		provider.seekPositions shouldBe longArrayOf(0, 5_000, 15_000, 25_000)
	}

	test("finishing a gesture restores the ordinary trickplay grid") {
		val adapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns true
			every { duration } returns 100_000L
		}
		val provider = CustomSeekProvider(adapter, mockk(), mockk(), mockk(), false, 30_000)
		provider.setRelativeSeekAnchor(50_000, 10_000)
		provider.reset()
		provider.seekPositions shouldBe longArrayOf(0, 30_000, 60_000, 90_000, 100_000)
	}

	test("zero duration has a single safe relative position") {
		val adapter = mockk<VideoPlayerAdapter> {
			every { canSeek() } returns true
			every { duration } returns 0L
		}
		val provider = CustomSeekProvider(adapter, mockk(), mockk(), mockk(), false, 30_000)
		provider.setRelativeSeekAnchor(50_000, 10_000)
		provider.seekPositions shouldBe longArrayOf(0)
	}
})
