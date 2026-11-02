package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean

class SeerrEfficiencyTests : FunSpec({
	test("catalog cache expires entries and evicts the least recently used item") {
		var now = 0L
		val cache = SeerrTimedCache<String, Int>(2, 10) { now }
		cache.put("a", 1); cache.put("b", 2)
		cache.get("a") shouldBe 1
		cache.put("c", 3)
		cache.get("b") shouldBe null
		cache.get("c") shouldBe 3
		now = 10
		cache.get("a") shouldBe null
		cache.get("c") shouldBe null
	}
	test("cache invalidation discards prior results") {
		val cache = SeerrTimedCache<String, Int>(2, 100) { 0 }
		cache.put("title", 1)
		cache.clear()
		cache.get("title") shouldBe null
	}
	test("repeated search and history metadata reuse responses while explicit details always refresh status") {
		var calls = 0
		val client = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
			calls++
			200 to if (request.url.encodedPath.endsWith("/search")) """{"results":[{"id":10,"mediaType":"tv","name":"Test series"}]}"""
			else """{"id":10,"name":"Refreshed series"}"""
		})
		client.titles("Test", "movie", 1)
		client.titles(" test ", "movie", 1)
		client.metadata("tv", 10).displayTitle shouldBe "Test series"
		calls shouldBe 1
		client.details("tv", 10).displayTitle shouldBe "Refreshed series"
		client.details("tv", 10)
		calls shouldBe 3
	}
	test("navigation reuses a recent account check but mutations can always request fresh permissions") {
		var calls = 0
		val client = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { calls++; 200 to """{"id":4,"permissions":16}""" })
		client.meForNavigation(); client.meForNavigation()
		calls shouldBe 1
		client.me(); client.me()
		calls shouldBe 3
		client.disconnect()
		client.meForNavigation()
		calls shouldBe 4
	}
	test("request mutations invalidate cached discovery status") {
		var searches = 0
		val client = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
			if (request.method == "POST") 201 to """{"id":42,"status":2}"""
			else { searches++; 200 to """{"results":[{"id":10,"mediaType":"movie","title":"Test movie"}]}""" }
		})
		client.titles("Test", "movie", 1)
		client.submit(SeerrRequestBody("movie", 10))
		client.titles("Test", "movie", 1)
		searches shouldBe 2
	}
	test("cached catalog data cannot bypass a changed Jellyfin account guard") {
		var allowed = true
		var calls = 0
		val client = SeerrApi(MemorySeerrStore(), accessAllowed = { allowed }, callFactory = seerrFakeCalls {
			calls++; 200 to """{"results":[{"id":10,"mediaType":"movie","title":"Test movie"}]}"""
		})
		client.titles("Test", "movie", 1)
		allowed = false
		client.cachedTitle("movie", 10) shouldBe null
		expectFailure<SeerrFailure> { client.titles("Test", "movie", 1) }.status shouldBe 403
		calls shouldBe 1
	}
	test("constructing a client performs no secure-store read on the UI path and cookies load only for their own origin") {
		var reads = 0
		val origin = SEERR_ORIGIN.toHttpUrl()
		val cookie = Cookie.Builder().name("connect.sid").value("unit-test-session").hostOnlyDomain(origin.host).path("/").build()
		val store = object : SeerrSessionStore {
			override fun read(): List<String> { reads++; return listOf(cookie.toString()) }
			override fun write(cookies: List<String>) { }
			override fun clear() { }
		}
		val jar = SeerrCookieJar(origin, store)
		reads shouldBe 0
		jar.loadForRequest("https://plex.tv".toHttpUrl()) shouldBe emptyList()
		reads shouldBe 0
		jar.loadForRequest(origin).size shouldBe 1
		jar.loadForRequest(origin).size shouldBe 1
		reads shouldBe 1
	}
	fun sessionStore() = MemorySeerrStore().apply {
		values = listOf(Cookie.Builder().name("connect.sid").value("unit-test-session").hostOnlyDomain("192.168.1.199").path("/").build().toString())
	}
	test("history is interactive before slow title metadata finishes and opening a confirmation makes no mutation") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		val metadataStarted = CompletableDeferred<Unit>()
		val metadataCancelled = CompletableDeferred<Unit>()
		var mutations = 0
		val immediate = seerrFakeCalls { request ->
			if (request.method != "GET") mutations++
			200 to if (request.url.encodedPath.endsWith("/auth/me")) """{"id":4,"permissions":16}"""
			else """{"results":[{"id":42,"status":4,"type":"tv","requestedBy":{"id":4},"media":{"tmdbId":10,"mediaType":"tv"}}]}"""
		}
		val factory = Call.Factory { request ->
			if (request.url.encodedPath.endsWith("/tv/10")) mockk<Call> {
				every { enqueue(any()) } answers { metadataStarted.complete(Unit); Unit /* Keep metadata pending. */ }
				every { cancel() } answers { metadataCancelled.complete(Unit); Unit }
			} else immediate.newCall(request)
		}
		val model = SeerrViewModel(SeerrApi(sessionStore(), callFactory = factory), SeerrLibrary { Result.success(emptyList()) }, scope)
		try {
			model.start("", history = true)
			val state = withTimeout(5_000) { model.state.first { !it.busy && it.history.size == 1 } }
			state.history.single().title shouldBe null
			withTimeout(5_000) { metadataStarted.await() }
			model.askRequestAction(SeerrRequestAction.RETRY, state.history.single().request, "Test series")
			model.state.value.requestPrompt?.action shouldBe SeerrRequestAction.RETRY
			mutations shouldBe 0
			model.dismissRequestAction()
			model.leave()
			withTimeout(5_000) { metadataCancelled.await() }
		} finally { scope.cancel() }
	}
	test("title artwork and metadata publish before a slow library check while requesting stays gated") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		val libraryResult = CompletableDeferred<Result<List<org.jellyfin.sdk.model.api.BaseItemDto>>>()
		val client = SeerrApi(sessionStore(), callFactory = seerrFakeCalls { request ->
			200 to if (request.url.encodedPath.endsWith("/auth/me")) """{"id":4,"permissions":32}"""
			else """{"id":10,"name":"Visible before library","posterPath":"/poster.jpg"}"""
		})
		val model = SeerrViewModel(client, SeerrLibrary { libraryResult.await() }, scope)
		try {
			model.start("", type = "tv", id = 10)
			val visible = withTimeout(5_000) { model.state.first { it.title?.displayTitle == "Visible before library" } }
			visible.libraryChecked shouldBe false
			visible.requestBody() shouldBe null
			libraryResult.complete(Result.success(emptyList()))
			withTimeout(5_000) { model.state.first { !it.busy && it.libraryChecked } }
		} finally { scope.cancel() }
	}
})
