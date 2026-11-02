package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okio.Buffer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SeerrLoginTests : FunSpec({
	val options = """{"initialized":true,"mediaServerType":2,"mediaServerLogin":true,"jellyfinConfigured":true,"localLogin":true}"""
	val user = """{"id":4,"permissions":32}"""
	val emptyPage = """{"results":[],"page":1,"totalPages":1}"""

	test("login choices follow public configured-server settings rather than assuming Plex") {
		val jellyfin = seerrJson.decodeFromString<SeerrLoginOptions>(options)
		jellyfin.jellyfin shouldBe true
		jellyfin.plex shouldBe false
		jellyfin.local shouldBe true
		jellyfin.copy(mediaServerType = 1).plex shouldBe true
		jellyfin.copy(mediaServerType = 1).jellyfin shouldBe false
		jellyfin.copy(mediaServerLogin = false).jellyfin shouldBe false
		jellyfin.copy(initialized = false).jellyfin shouldBe false
		jellyfin.copy(jellyfinConfigured = false).jellyfin shouldBe false
		SeerrLoginOptions().local shouldBe false
	}

	test("Jellyfin credentials go only to the selected Seerr configured-server login endpoint") {
		val paths = mutableListOf<String>()
		val remote = SeerrEndpoint.parse(SEERR_REMOTE_ORIGIN)
		val api = SeerrApi(MemorySeerrStore(), endpoint = remote, callFactory = seerrFakeCalls { request ->
			request.url.host shouldBe remote.url.host
			request.header("Authorization") shouldBe null
			request.header("X-Api-Key") shouldBe null
			request.url.toString().contains("unit-password") shouldBe false
			paths += "${request.method} ${request.url.encodedPath}"
			if (request.method == "POST") {
				Buffer().also { request.body?.writeTo(it) }.readUtf8() shouldBe
					"""{"username":"Unit User","password":"unit-password","email":"Unit User"}"""
			}
			200 to if (request.url.encodedPath.endsWith("/public")) options else user
		})
		api.loginJellyfin("Unit User", "unit-password").id shouldBe 4
		paths shouldBe listOf("GET /api/v1/settings/public", "POST /api/v1/auth/jellyfin", "GET /api/v1/auth/me")
	}

	test("disabled or unconfigured Jellyfin login cannot start server setup or submit credentials") {
		for (json in listOf("{}", """{"initialized":true,"mediaServerType":1,"mediaServerLogin":true}""")) {
			val store = recoverySessionStore()
			val saved = store.values
			var calls = 0
			val api = SeerrApi(store, callFactory = seerrFakeCalls { request ->
				request.method shouldBe "GET"
				calls++; 200 to json
			})
			expectFailure<SeerrFailure> { api.loginJellyfin("unit-user", "unit-password") }.status shouldBe 403
			calls shouldBe 1
			store.values shouldBe saved
		}
	}

	test("rejected Jellyfin credentials are submitted once without password replay") {
		var posts = 0
		val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
			if (request.method == "POST") { posts++; 401 to "{}" } else 200 to options
		})
		expectFailure<SeerrFailure> { api.loginJellyfin("unit-user", "wrong-unit-password") }.status shouldBe 401
		posts shouldBe 1
	}

	test("Jellyfin accounts with an empty password use the same supported login flow") {
		val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
			if (request.method == "POST") Buffer().also { request.body?.writeTo(it) }.readUtf8().contains("\"password\":\"\"") shouldBe true
			200 to if (request.url.encodedPath.endsWith("/public")) options else user
		})
		api.loginJellyfin("unit-user", "").id shouldBe 4
	}

	test("constructing a screen model does not authenticate and opening it with no cookie reads only public methods") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		val paths = mutableListOf<String>()
		val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
			paths += "${request.method} ${request.url.encodedPath}"
			200 to options
		})
		val model = SeerrViewModel(api, SeerrLibrary { Result.success(emptyList()) }, scope)
		try {
			paths shouldBe emptyList()
			model.start("")
			val state = withTimeout(3_000) { model.state.first { !it.busy && !it.restoringSession && it.loginOptions != null } }
			state.user shouldBe null
			state.loginOptions?.jellyfin shouldBe true
			paths shouldBe listOf("GET /api/v1/settings/public")
		} finally { scope.cancel() }
	}

	test("resuming revalidates cookies freshly recovers transient reads and offers sign-in only for a rejected session") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		val store = recoverySessionStore()
		val saved = store.values
		var checks = 0
		var failNext = false
		var expired = false
		var posts = 0
		val api = SeerrApi(store, callFactory = seerrFakeCalls { request ->
			if (request.method == "POST") posts++
			when (request.url.encodedPath) {
				"/api/v1/auth/me" -> {
					checks++
					if (failNext) { failNext = false; throw SocketTimeoutException("unit-idle-timeout") }
					if (expired) 401 to "{}" else 200 to user
				}
				"/api/v1/settings/public" -> 200 to options
				else -> 200 to emptyPage
			}
		})
		val model = SeerrViewModel(api, SeerrLibrary { Result.success(emptyList()) }, scope)
		try {
			api.meForNavigation()
			model.start("")
			withTimeout(3_000) { model.state.first { !it.busy && it.user != null } }
			checks shouldBe 1
			failNext = true
			model.resumed()
			withTimeout(3_000) { model.state.first { !it.busy && !it.restoringSession } }
			checks shouldBe 3
			model.state.value.user?.id shouldBe 4
			expired = true
			model.resumed()
			withTimeout(3_000) { model.state.first { !it.busy && it.loginOptions != null } }
			model.state.value.user shouldBe null
			model.state.value.loginOptions?.jellyfin shouldBe true
			posts shouldBe 0
			store.values shouldBe saved
		} finally { scope.cancel() }
	}

	test("failed public method lookup does not invent a Plex fallback") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		var calls = 0
		val model = SeerrViewModel(SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { calls++; 503 to "{}" }),
			SeerrLibrary { Result.success(emptyList()) }, scope)
		try {
			model.start("")
			val state = withTimeout(3_000) { model.state.first { !it.busy && !it.restoringSession } }
			state.loginOptions shouldBe null
			state.user shouldBe null
			calls shouldBe 2
		} finally { scope.cancel() }
	}

	test("public login-method lookup never loads or sends the existing session cookie") {
		ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
			server.soTimeout = 3_000
			val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "login-options-test").apply { isDaemon = true } }
			var storeReads = 0
			val store = object : SeerrSessionStore {
				override fun read(): List<String> { storeReads++; error("Public lookup must not access credentials") }
				override fun write(cookies: List<String>) = error("Public lookup must not persist cookies")
				override fun clear() = Unit
			}
			try {
				val task = worker.submit(Callable {
					server.accept().use { socket ->
						val reader = socket.getInputStream().bufferedReader()
						reader.readLine() shouldBe "GET /api/v1/settings/public HTTP/1.1"
						val headers = mutableListOf<String>()
						while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers += line }
						headers.any { it.startsWith("Cookie:", true) || it.startsWith("Authorization:", true) } shouldBe false
						socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${options.length}\r\nSet-Cookie: unit-cookie=unit; Path=/\r\nConnection: close\r\n\r\n$options").toByteArray())
					}
				})
				SeerrApi(store, endpoint = SeerrEndpoint.parse("http://127.0.0.1:${server.localPort}")).loginOptions().jellyfin shouldBe true
				task.get(5, TimeUnit.SECONDS)
				storeReads shouldBe 0
			} finally { worker.shutdownNow() }
		}
	}
})
