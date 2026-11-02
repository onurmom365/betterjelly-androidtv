package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

class PlaybackReturnPositionTests : FunSpec({
	fun movie() = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, runTimeTicks = 7200L * 10000000,
		userData = UserItemDataDto(itemId = UUID.randomUUID(), playbackPositionTicks = 0, playCount = 2, isFavorite = true, played = false, key = "test"))
	test("early exit is immediately resumable despite a zero server bookmark") {
		val positions = PlaybackReturnPosition({ "server/user" })
		val original = movie()
		positions.stopped(original, 45L * 10000000, false)
		val restored = positions.apply(original)
		restored.userData?.playbackPositionTicks shouldBe 45L * 10000000
		restored.userData?.isFavorite shouldBe true
		restored.userData?.playCount shouldBe 2
		original.userData?.playbackPositionTicks shouldBe 0L
	}
	test("latest exit replaces the position even after a backward seek") {
		val positions = PlaybackReturnPosition({ "server/user" })
		val item = movie()
		positions.stopped(item, 90L * 10000000, false)
		positions.stopped(item, 20L * 10000000, false)
		positions.apply(item).userData?.playbackPositionTicks shouldBe 20L * 10000000
	}
	test("completion and starting over clear the local return point") {
		val positions = PlaybackReturnPosition({ "server/user" })
		val item = movie()
		positions.stopped(item, 50L * 10000000, false)
		positions.stopped(item, item.runTimeTicks, true)
		positions.apply(item) shouldBe item
		positions.stopped(item, 50L * 10000000, false)
		positions.clear(item.id)
		positions.apply(item) shouldBe item
	}
	test("accounts servers and titles have isolated return positions") {
		var account: String? = "server1/user1"
		val positions = PlaybackReturnPosition({ account })
		val item = movie()
		positions.stopped(item, 50L * 10000000, false)
		for (other in listOf("server1/user2", "server2/user1", null)) {
			account = other
			positions.apply(item) shouldBe item
		}
		account = "server1/user1"
		positions.apply(item).userData?.playbackPositionTicks shouldBe 50L * 10000000
		val other = movie()
		positions.apply(other) shouldBe other
	}
	test("short lived return state expires so later server state takes precedence") {
		var time = 1000L
		val positions = PlaybackReturnPosition({ "server/user" }, { time })
		val item = movie()
		positions.stopped(item, 50L * 10000000, false)
		time += 30 * 60 * 1000
		positions.apply(item) shouldBe item
	}
	test("a stop from an old playback cannot save into a newly switched account") {
		var account = "server/user1"
		val positions = PlaybackReturnPosition({ account })
		val item = movie()
		positions.started(item, 0)
		positions.stopped(item, 20L * 10000000, false)
		account = "server/user2"
		positions.stopped(item, 50L * 10000000, false)
		positions.apply(item) shouldBe item
		account = "server/user1"
		positions.apply(item).userData?.playbackPositionTicks shouldBe 20L * 10000000
	}
	test("live media and invalid or completed positions do not get bookmarks") {
		val positions = PlaybackReturnPosition({ "server/user" })
		val item = movie()
		for (ticks in listOf(null, -1L, 0L, item.runTimeTicks)) {
			positions.stopped(item, ticks, false)
			positions.apply(item) shouldBe item
		}
		val live = item.copy(type = BaseItemKind.TV_CHANNEL)
		positions.stopped(live, 50L * 10000000, false)
		positions.apply(live) shouldBe live
	}
})
