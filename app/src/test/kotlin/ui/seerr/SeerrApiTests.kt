package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

class SeerrApiTests : FunSpec({
	fun factory(handler: (Request) -> Pair<Int, String>) = Call.Factory { request ->
		mockk<Call> {
			every { cancel() } returns Unit
			every { enqueue(any()) } answers {
				val callback = firstArg<Callback>()
				try {
					val (code, body) = handler(request)
					callback.onResponse(this@mockk, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
						.body(body.toResponseBody("application/json".toMediaType())).build())
				} catch (error: IOException) { callback.onFailure(this@mockk, error) }
			}
		}
	}
	test("local authentication sends credentials only to auth endpoint and never uses an API key") {
		val paths = mutableListOf<String>()
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			paths += request.url.encodedPath
			request.url.host shouldBe "192.168.1.199"
			request.url.port shouldBe 5055
			request.header("X-Api-Key") shouldBe null
			request.header("Authorization") shouldBe null
			if (request.url.encodedPath.endsWith("/local")) {
				request.method shouldBe "POST"
				val payload = Buffer().also { request.body?.writeTo(it) }.readUtf8()
				payload shouldBe """{"email":"test@example.invalid","password":"unit-test-only"}"""
			}
			200 to """{"id":4,"permissions":32}"""
		})
		api.login("test@example.invalid", "unit-test-only").id shouldBe 4
		paths shouldBe listOf("/api/v1/auth/local", "/api/v1/auth/me")
	}
	test("search query is encoded and people are excluded") {
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			request.url.queryParameter("query") shouldBe "A&B / test"
			200 to """{"results":[{"id":1,"mediaType":"person"},{"id":2,"mediaType":"movie","title":"Movie"}]}"""
		})
		api.titles("A&B / test", "movie", 1).results.map { it.id } shouldBe listOf(2)
	}
	test("discovery responses inherit the known media type") {
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			request.url.encodedPath shouldBe "/api/v1/discover/tv"
			200 to """{"results":[{"id":4,"name":"Show"}]}"""
		})
		api.titles("", "tv", 1).results.single().mediaType shouldBe "tv"
	}
	test("invalid details route cannot issue a network call") {
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { calls++; 200 to "{}" })
		expectFailure<IllegalArgumentException> { api.details("../settings", 1) }
		calls shouldBe 0
	}
	test("changed Jellyfin user prevents any call with the previous Seerr session") {
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), accessAllowed = { false }, callFactory = factory { calls++; 200 to "{}" })
		expectFailure<SeerrFailure> { api.me() }.status shouldBe 403
		calls shouldBe 0
	}
	test("uncertain POST failure has no retry and requires status refresh") {
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { calls++; throw IOException("unit-test-failure") })
		expectFailure<SeerrFailure> { api.submit(SeerrRequestBody("movie", 3)) }.submissionUncertain shouldBe true
		calls shouldBe 1
	}
	test("duplicate and no-new-season responses cannot appear as successful submissions") {
		for (code in listOf(409, 202)) {
			val api = SeerrApi(MemorySeerrStore(), callFactory = factory { code to "{}" })
			expectFailure<SeerrFailure> { api.submit(SeerrRequestBody("tv", 3, listOf(1))) }.status shouldBe code
		}
	}
	test("redirect and permission errors do not leak response data into user messages") {
		for (code in listOf(302, 403, 500)) {
			val api = SeerrApi(MemorySeerrStore(), callFactory = factory { code to "sensitive-server-text" })
			val error = expectFailure<SeerrFailure> { api.me() }
			error.userMessage.contains("sensitive") shouldBe false
		}
	}
	test("malformed success response after POST remains uncertain") {
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { 201 to "invalid-json" })
		expectFailure<SeerrFailure> { api.submit(SeerrRequestBody("movie", 3)) }.submissionUncertain shouldBe true
	}
	test("Plex token is exchanged only in the Seerr login body and never placed in a URL") {
		val paths = mutableListOf<String>()
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			request.url.toString().contains("unit-test-token") shouldBe false
			paths += "${request.url.host}${request.url.encodedPath}"
			if (request.url.host == "plex.tv") {
				request.url.isHttps shouldBe true
				request.header("X-Plex-Client-Identifier") shouldBe "test-device"
				200 to """{"id":12,"code":"ABCD","authToken":"unit-test-token"}"""
			} else {
				request.url.encodedPath shouldBe "/api/v1/auth/plex"
				Buffer().also { request.body?.writeTo(it) }.readUtf8() shouldBe """{"authToken":"unit-test-token"}"""
				200 to "{}"
			}
		})
		api.checkPlex(SeerrApi.PlexPin(12, "ABCD"), "test-device") shouldBe true
		paths shouldBe listOf("plex.tv/api/v2/pins/12", "192.168.1.199/api/v1/auth/plex")
	}
	test("4K requests are rejected before any network call") {
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { calls++; 201 to "{\"id\":1}" })
		expectFailure<IllegalArgumentException> { api.submit(SeerrRequestBody("movie", 3, is4k = true)) }
		calls shouldBe 0
	}
	test("normal submission serializes explicit false plus chosen profile and preset tags") {
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			val body = seerrJson.decodeFromString<SeerrRequestBody>(Buffer().also { request.body?.writeTo(it) }.readUtf8())
			body.is4k shouldBe false
			body.profileId shouldBe 7
			body.tags shouldBe listOf(5)
			201 to "{\"id\":1,\"status\":2}"
		})
		api.submit(SeerrRequestBody("movie", 3, profileId = 7, tags = listOf(5))).statusLabel shouldBe "Requested"
	}
	test("history is scoped to the signed-in user with bounded pagination and a supported filter") {
		val api = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			request.method shouldBe "GET"
			request.url.encodedPath shouldBe "/api/v1/request"
			request.url.queryParameter("requestedBy") shouldBe "4"
			request.url.queryParameter("skip") shouldBe "12"
			request.url.queryParameter("take") shouldBe "12"
			request.url.queryParameter("filter") shouldBe "failed"
			200 to """{"results":[{"id":1,"status":4,"is4k":true}],"pageInfo":{"pages":3}}"""
		})
		val result = api.history(4, "failed", 2)
		result.pageInfo.pages shouldBe 3
		result.results.single().statusLabel shouldBe "Failed"
	}

	fun savedStore() = MemorySeerrStore().apply {
		values = listOf(Cookie.Builder().name("connect.sid").value("unit-test-session").hostOnlyDomain("192.168.1.199").path("/").build().toString())
	}
	test("a saved session restores in a fresh client with only a read and no new sign-in") {
		val store = savedStore()
		val paths = mutableListOf<String>()
		val client = SeerrApi(store, callFactory = factory { request ->
			request.method shouldBe "GET"
			paths += request.url.encodedPath
			200 to """{"id":4,"permissions":32}"""
		})
		checkSavedSeerrSession(client) shouldBe SeerrSessionCheck.Connected(SeerrUser(4, permissions = 32))
		paths shouldBe listOf("/api/v1/auth/me")
	}
	test("no saved session asks for sign-in without contacting the server") {
		var calls = 0
		val client = SeerrApi(MemorySeerrStore(), callFactory = factory { calls++; 200 to "{}" })
		checkSavedSeerrSession(client) shouldBe SeerrSessionCheck.SignInRequired
		calls shouldBe 0
	}
	test("a failed session connection preserves saved cookies and can recover without signing in") {
		val store = savedStore()
		val saved = store.values
		var offline = true
		val client = SeerrApi(store, callFactory = factory {
			if (offline) throw IOException("private transport detail")
			200 to """{"id":4,"permissions":32}"""
		})
		val failed = checkSavedSeerrSession(client) as SeerrSessionCheck.Unavailable
		failed.message.contains("private") shouldBe false
		store.values shouldBe saved
		offline = false
		checkSavedSeerrSession(client) shouldBe SeerrSessionCheck.Connected(SeerrUser(4, permissions = 32))
	}
	test("a temporary secure-store failure neither erases the session nor forces sign-in") {
		var unreadable = true
		var clears = 0
		var saved = savedStore().values
		val original = saved
		val store = object : SeerrSessionStore {
			override fun read(): List<String> { if (unreadable) throw IOException("private storage detail"); return saved }
			override fun write(cookies: List<String>) { saved = cookies }
			override fun clear() { clears++; saved = emptyList() }
		}
		val client = SeerrApi(store, callFactory = factory { 200 to """{"id":4,"permissions":32}""" })
		val failed = checkSavedSeerrSession(client) as SeerrSessionCheck.Unavailable
		failed.message.contains("securely") shouldBe true
		failed.message.contains("private") shouldBe false
		clears shouldBe 0
		saved shouldBe original
		unreadable = false
		checkSavedSeerrSession(client) shouldBe SeerrSessionCheck.Connected(SeerrUser(4, permissions = 32))
	}
	test("a saved session rejected by Seerr requires explicit sign-in rather than password or token replay") {
		for (status in listOf(401, 403)) {
			val store = savedStore()
			val original = store.values
			var calls = 0
			val client = SeerrApi(store, callFactory = factory { request ->
				request.method shouldBe "GET"
				request.url.encodedPath shouldBe "/api/v1/auth/me"
				calls++
				status to "private error"
			})
			checkSavedSeerrSession(client) shouldBe SeerrSessionCheck.SignInRequired
			calls shouldBe 1
			store.values shouldBe original
		}
	}
	test("session-check errors preserve the account and expose retry state until a confirmed result") {
		val signedIn = SeerrUiState(user = SeerrUser(4))
		val failed = signedIn.checkedSession(SeerrSessionCheck.Unavailable("Connection unavailable"))
		failed.user shouldBe signedIn.user
		failed.sessionCheckFailed shouldBe true
		val recovered = failed.checkedSession(SeerrSessionCheck.Connected(SeerrUser(4)))
		recovered.sessionCheckFailed shouldBe false
		recovered.message shouldBe null
		val rejected = failed.checkedSession(SeerrSessionCheck.SignInRequired)
		rejected.sessionCheckFailed shouldBe false
		rejected.user shouldBe null
	}
})
