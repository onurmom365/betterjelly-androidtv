package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

class SeerrModelsTests : FunSpec({
	val movie = SeerrTitle(123, "movie", title = "Test movie")
	val tv = SeerrTitle(456, "tv", name = "Test show", seasons = listOf(
		SeerrSeason(0, "Specials", 1), SeerrSeason(1, "Season 1", 10), SeerrSeason(2, "Season 2", 8), SeerrSeason(3, "Unaired", 0)))
	val user = SeerrUser(4, permissions = 32)
	val admin = SeerrUser(1, permissions = 2)

	test("movie and TV permissions are independent") {
		SeerrUser(4, permissions = 262144).canRequest("movie", false) shouldBe true
		SeerrUser(4, permissions = 262144).canRequest("tv", false) shouldBe false
		SeerrUser(4, permissions = 524288).canRequest("tv", false) shouldBe true
		SeerrUser(4).canRequest("movie", false) shouldBe false
	}
	test("4K requires its own permission and admin permits both") {
		user.canRequest("movie", true) shouldBe false
		SeerrUser(4, permissions = 2048).canRequest("movie", true) shouldBe true
		SeerrUser(4, permissions = 2048).canRequest("tv", true) shouldBe false
		admin.canRequest("tv", true) shouldBe true
	}
	test("basic movie request contains no elevated user tags quota or approval fields") {
		val body = prepareSeerrRequest(user, movie, false, emptySet())
		val keys = seerrJson.parseToJsonElement(seerrJson.encodeToString(body)).jsonObject.keys
		keys shouldBe setOf("mediaType", "mediaId")
	}
	test("unsupported types and invalid IDs cannot be submitted") {
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(admin, movie.copy(mediaType = "person"), false, emptySet()) }
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(admin, movie.copy(id = 0), false, emptySet()) }
	}
	test("pending processing and available movies cannot be duplicated") {
		for (status in listOf(2, 3, 5)) expectFailure<IllegalArgumentException> {
			prepareSeerrRequest(user, movie.copy(mediaInfo = SeerrMediaInfo(status = status)), false, emptySet())
		}
	}
	test("pending movie requests block duplicates even with stale media availability") {
		movie.copy(mediaInfo = SeerrMediaInfo(requests = listOf(SeerrRequest(1, 1)))).movieRequestable(false) shouldBe false
	}
	test("declined request or deleted media can be requested again subject to server validation") {
		movie.copy(mediaInfo = SeerrMediaInfo(status = 7, requests = listOf(SeerrRequest(1, 3)))).movieRequestable(false) shouldBe true
	}
	test("blocklist applies to both standard and 4K requests") {
		val blocked = SeerrMediaInfo(status = 6)
		movie.copy(mediaInfo = blocked).movieRequestable(false) shouldBe false
		movie.copy(mediaInfo = blocked).movieRequestable(true) shouldBe false
		tv.copy(mediaInfo = blocked).requestableSeasons(true) shouldBe emptyList()
	}
	test("standard availability does not block separate 4K selection") {
		movie.copy(mediaInfo = SeerrMediaInfo(status = 5)).movieRequestable(true) shouldBe true
	}
	test("TV requires an explicit nonempty known season selection") {
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(user, tv, false, emptySet()) }
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(user, tv, false, setOf(99)) }
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(user, tv, false, setOf(3)) }
		prepareSeerrRequest(user, tv, false, setOf(2, 0)).seasons shouldBe listOf(0, 2)
	}
	test("available pending processing and already requested seasons are excluded") {
		val title = tv.copy(mediaInfo = SeerrMediaInfo(seasons = listOf(SeerrSeasonStatus(0, 5), SeerrSeasonStatus(1, 3)),
			requests = listOf(SeerrRequest(9, 1, seasons = listOf(SeerrSeasonRequest(2, 1))))))
		title.requestableSeasons(false) shouldBe emptyList()
		title.requestableSeasons(true).map { it.seasonNumber } shouldBe listOf(0, 1, 2)
	}
	test("advanced choices require permission and actual returned profile root and quality") {
		val service = SeerrServiceDetails(SeerrService(0, "Movies"), listOf(SeerrProfile(1, "HD")), listOf(SeerrRootFolder("/movies")))
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(user, movie, false, emptySet(), service, 1, "/movies") }
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(admin, movie, false, emptySet(), service, 7, "/movies") }
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(admin, movie, false, emptySet(), service, 1, "/other") }
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(admin, movie, true, emptySet(), service, 1, "/movies") }
		prepareSeerrRequest(admin, movie, false, emptySet(), service, 1, "/movies").serverId shouldBe 0
	}
	test("service defaults omit all advanced fields") {
		prepareSeerrRequest(user, tv, false, setOf(1)).serverId shouldBe null
		expectFailure<IllegalArgumentException> { prepareSeerrRequest(admin, movie, false, emptySet(), profileId = 4) }
	}
	test("UI requires successful library check and blocks exact local movie matches") {
		val state = SeerrUiState(user = user, title = movie)
		state.requestBody() shouldBe null
		state.copy(libraryChecked = true, exactLibraryMatch = true).requestBody() shouldBe null
		state.copy(libraryChecked = true).requestBody()?.mediaId shouldBe 123
		state.copy(libraryChecked = true, needsRefresh = true).requestBody() shouldBe null
	}
	test("TV library match allows requests for missing seasons") {
		SeerrUiState(user = user, title = tv, libraryChecked = true, exactLibraryMatch = true, seasons = setOf(2)).requestBody()?.seasons shouldBe listOf(2)
	}
	test("auth decoder retains only minimal user data") {
		val decoded = seerrJson.decodeFromString<SeerrUser>("""{"id":4,"permissions":32,"email":"private@example.invalid","plexToken":"secret","jellyfinAuthToken":"secret"}""")
		seerrJson.encodeToString(decoded).contains("secret") shouldBe false
	}
})
