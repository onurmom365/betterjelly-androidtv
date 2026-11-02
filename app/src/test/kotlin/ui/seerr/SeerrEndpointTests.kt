package org.jellyfin.androidtv.ui.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import org.jellyfin.androidtv.auth.repository.Session
import java.io.IOException
import java.util.UUID

internal class MemoryEndpointStore : SeerrEndpointStore {
	val values = mutableMapOf<String, String>()
	var writes = 0
	var failWrite = false
	override fun read(account: String) = values[account]
	override fun write(account: String, origin: String) {
		writes++
		values[account] = origin
		if (failWrite) throw IOException("Unit-test storage failure")
	}
}

internal fun endpointTestFactory(handler: (Request) -> Pair<Int, String>): Call.Factory = Call.Factory { request ->
	mockk<Call> {
		every { cancel() } returns Unit
		every { enqueue(any()) } answers {
			val callback = firstArg<Callback>()
			val (status, body) = handler(request)
			callback.onResponse(this@mockk, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
				.body(body.toResponseBody("application/json".toMediaType())).build())
		}
	}
}

private class ScopedEndpointModel : ViewModel() {
	var cleared = false
	override fun onCleared() { cleared = true }
}

class SeerrEndpointTests : FunSpec({
	val account = "server-a/user-a"
	val remote = SeerrEndpoint.parse(SEERR_REMOTE_ORIGIN)

	test("canonical LAN address preserves the original encrypted session scope") {
		SeerrEndpoint.parse("  http://192.168.1.199:5055/  ").origin shouldBe SEERR_ORIGIN
		val session = Session(UUID(0, 1), UUID(0, 2), "unit-test-only")
		seerrAccountScope(session, defaultSeerrEndpoint) shouldBe "${session.serverId}/${session.userId}/$SEERR_ORIGIN"
	}

	test("HTTPS case default port and trailing slash normalize to one address") {
		SeerrEndpoint.parse("HTTPS://SEERRPLEXREQUEST.SYNOLOGY.ME:443/") shouldBe remote
		remote.origin shouldBe SEERR_REMOTE_ORIGIN
	}

	test("page paths tracking queries credentials and unsupported schemes are rejected without echoing secrets") {
		for (value in listOf("", "example.invalid", "ftp://example.invalid", "$SEERR_REMOTE_ORIGIN/login",
			"$SEERR_REMOTE_ORIGIN/?utm_source=unit", "$SEERR_REMOTE_ORIGIN/#secret", "https://user:unit-test-secret@example.invalid")) {
			val failure = expectFailure<IllegalArgumentException> { SeerrEndpoint.parse(value) }
			failure.message.orEmpty().contains("unit-test-secret") shouldBe false
		}
	}

	test("plaintext is limited to unambiguous local or private addresses") {
		for (value in listOf(SEERR_ORIGIN, "http://10.0.0.1:5055", "http://172.16.1.2", "http://127.0.0.1:1234", "http://localhost:5055"))
			SeerrEndpoint.parse(value).url.scheme shouldBe "http"
		for (value in listOf("http://example.invalid", "http://8.8.8.8", "http://172.32.0.1", "http://192.168.1.1.example.invalid"))
			expectFailure<IllegalArgumentException> { SeerrEndpoint.parse(value) }
	}

	test("saved addresses persist independently for each Jellyfin account") {
		val store = MemoryEndpointStore()
		val configuration = SeerrEndpointConfiguration(store)
		configuration.endpoint(account) shouldBe defaultSeerrEndpoint
		configuration.save(account, remote.origin)
		configuration.revision.value shouldBe 1L
		configuration.endpoint("server-a/user-b") shouldBe defaultSeerrEndpoint
		SeerrEndpointConfiguration(store).endpoint(account) shouldBe remote
		configuration.save(account, SEERR_ORIGIN)
		configuration.endpoint(account) shouldBe defaultSeerrEndpoint
		configuration.revision.value shouldBe 2L
	}

	test("saving an equivalent address does not write or invalidate active navigation") {
		val store = MemoryEndpointStore()
		val configuration = SeerrEndpointConfiguration(store)
		configuration.save(account, "$SEERR_ORIGIN/")
		store.writes shouldBe 0
		configuration.revision.value shouldBe 0L
	}

	test("a failed persistence write cannot silently change the active address or access guard") {
		val store = MemoryEndpointStore().apply { failWrite = true }
		val configuration = SeerrEndpointConfiguration(store)
		expectFailure<IOException> { configuration.save(account, remote.origin) }
		configuration.endpoint(account) shouldBe defaultSeerrEndpoint
		configuration.revision.value shouldBe 0L
	}

	test("unreadable or invalid saved settings do not break library UI construction") {
		val store = object : SeerrEndpointStore {
			override fun read(account: String): String? = throw IOException()
			override fun write(account: String, origin: String) = Unit
		}
		SeerrEndpointConfiguration(store).endpoint(account) shouldBe defaultSeerrEndpoint
		SeerrEndpointConfiguration(MemoryEndpointStore().apply { values[account] = "not-a-url" }).endpoint(account) shouldBe defaultSeerrEndpoint
	}

	test("account and endpoint scopes remain distinct and never include a Jellyfin access token") {
		val session = Session(UUID(0, 1), UUID(0, 2), "unit-test-token")
		seerrAccountScope(session, remote).orEmpty().contains("unit-test-token") shouldBe false
		(seerrAccountScope(session, remote) == seerrAccountScope(session, defaultSeerrEndpoint)) shouldBe false
		(seerrAccountScope(session, remote) == seerrAccountScope(session.copy(userId = UUID(0, 3)), remote)) shouldBe false
		seerrAccountScope(null, remote) shouldBe null
	}

	test("cookies for the home and remote address cannot cross origin or session stores") {
		val homeStore = MemorySeerrStore()
		val remoteStore = MemorySeerrStore()
		val homeJar = SeerrCookieJar(defaultSeerrEndpoint.url, homeStore)
		val remoteJar = SeerrCookieJar(remote.url, remoteStore)
		homeJar.saveFromResponse(defaultSeerrEndpoint.url, listOf(Cookie.Builder().name("connect.sid").value("unit-home")
			.hostOnlyDomain(defaultSeerrEndpoint.url.host).path("/").build()))
		remoteJar.saveFromResponse(remote.url, listOf(Cookie.Builder().name("connect.sid").value("unit-remote")
			.hostOnlyDomain(remote.url.host).path("/").secure().build()))
		homeJar.loadForRequest(remote.url) shouldBe emptyList()
		remoteJar.loadForRequest(defaultSeerrEndpoint.url) shouldBe emptyList()
		homeJar.clear()
		remoteJar.reload() shouldBe true
		remoteJar.loadForRequest(remote.url).single().value shouldBe "unit-remote"
	}

	test("local sign-in and media submission use the selected HTTPS origin without shared credentials") {
		val paths = mutableListOf<String>()
		val api = SeerrApi(MemorySeerrStore(), endpoint = remote, callFactory = endpointTestFactory { request ->
			request.url.scheme shouldBe "https"
			request.url.host shouldBe remote.url.host
			request.header("Authorization") shouldBe null
			request.header("X-Api-Key") shouldBe null
			paths += request.url.encodedPath
			200 to if (request.url.encodedPath == "/api/v1/request") """{"id":10,"status":2}""" else """{"id":4,"permissions":32}"""
		})
		api.login("unit@example.invalid", "unit-password")
		api.submit(SeerrRequestBody("movie", 3))
		paths shouldBe listOf("/api/v1/auth/local", "/api/v1/auth/me", "/api/v1/request")
	}

	test("Plex token exchange follows the selected Seerr address and never enters its URL") {
		val hosts = mutableListOf<String>()
		val api = SeerrApi(MemorySeerrStore(), endpoint = remote, callFactory = endpointTestFactory { request ->
			hosts += request.url.host
			request.url.toString().contains("unit-token") shouldBe false
			200 to if (request.url.host == "plex.tv") """{"id":12,"code":"ABCD","authToken":"unit-token"}""" else "{}"
		})
		api.checkPlex(SeerrApi.PlexPin(12, "ABCD"), "unit-device") shouldBe true
		hosts shouldBe listOf("plex.tv", remote.url.host)
	}

	test("switching an address denies old API access and cached data before another request is sent") {
		val configuration = SeerrEndpointConfiguration(MemoryEndpointStore())
		val selected = configuration.endpoint(account)
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), endpoint = selected, accessAllowed = { configuration.endpoint(account) == selected },
			callFactory = endpointTestFactory { calls++; 200 to """{"id":3,"mediaType":"movie"}""" })
		api.details("movie", 3)
		configuration.save(account, remote.origin)
		api.cachedTitle("movie", 3) shouldBe null
		expectFailure<SeerrFailure> { api.submit(SeerrRequestBody("movie", 3)) }.status shouldBe 403
		calls shouldBe 1
	}

	test("changing address clears old screen models while retaining navigation within the same scope") {
		val models = SeerrScopedModels()
		val factory = object : ViewModelProvider.Factory {
			@Suppress("UNCHECKED_CAST")
			override fun <T : ViewModel> create(modelClass: Class<T>): T = ScopedEndpointModel() as T
		}
		val home = models.provider("home/0", factory)[ScopedEndpointModel::class.java]
		(models.provider("home/0", factory)[ScopedEndpointModel::class.java] === home) shouldBe true
		val away = models.provider("remote/1", factory)[ScopedEndpointModel::class.java]
		home.cleared shouldBe true
		val returned = models.provider("home/2", factory)[ScopedEndpointModel::class.java]
		away.cleared shouldBe true
		(returned === home) shouldBe false
	}
})
