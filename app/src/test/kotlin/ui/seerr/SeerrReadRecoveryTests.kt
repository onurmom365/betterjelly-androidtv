package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Cookie
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

internal fun recoverySessionStore() = MemorySeerrStore().apply {
	values = listOf(Cookie.Builder().name("connect.sid").value("unit-session").hostOnlyDomain("192.168.1.199").path("/").build().toString())
}

class SeerrReadRecoveryTests : FunSpec({
	val user = """{"id":4,"permissions":32}"""

	test("a timed out saved-session read recovers once without another login or lost cookies") {
		val store = recoverySessionStore()
		val original = store.values
		var calls = 0
		val api = SeerrApi(store, callFactory = seerrFakeCalls { request ->
			request.method shouldBe "GET"
			request.url.encodedPath shouldBe "/api/v1/auth/me"
			if (++calls == 1) throw SocketTimeoutException("unit-private-detail")
			200 to user
		})
		checkSavedSeerrSession(api) shouldBe SeerrSessionCheck.Connected(SeerrUser(4, permissions = 32))
		calls shouldBe 2
		store.values shouldBe original
	}

	test("persistent read failure stops after two attempts with a safe reason and intact session") {
		val store = recoverySessionStore()
		val original = store.values
		var calls = 0
		val api = SeerrApi(store, callFactory = seerrFakeCalls { calls++; throw SocketTimeoutException("unit-private-detail") })
		val failed = checkSavedSeerrSession(api) as SeerrSessionCheck.Unavailable
		calls shouldBe 2
		failed.message.contains("timed out") shouldBe true
		failed.message.contains("private-detail") shouldBe false
		store.values shouldBe original
	}

	test("anonymous connection testing also recovers a transient read without adding auth or writes") {
		val paths = mutableListOf<String>()
		val result = checkSeerrEndpoint(defaultSeerrEndpoint, seerrFakeCalls { request ->
			request.method shouldBe "GET"
			request.header("Cookie") shouldBe null
			request.header("Authorization") shouldBe null
			paths += request.url.encodedPath
			if (paths.size == 1) throw SocketTimeoutException("unit-timeout")
			200 to if (request.url.encodedPath.endsWith("/status")) """{"version":"unit-version"}""" else """{"initialized":true}"""
		})
		result.version shouldBe "unit-version"
		paths shouldBe listOf("/api/v1/status", "/api/v1/status", "/api/v1/settings/public")
	}

	test("authentication denial and redirects are not read-retried") {
		for (status in listOf(401, 403, 302)) {
			var calls = 0
			val api = SeerrApi(recoverySessionStore(), callFactory = seerrFakeCalls { calls++; status to "unit-private-body" })
			expectFailure<SeerrFailure> { api.me() }.status shouldBe status
			calls shouldBe 1
		}
	}

	test("a transient gateway failure can recover on the second GET") {
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls {
			if (++calls == 1) 503 to "{}" else 200 to user
		})
		api.me().id shouldBe 4
		calls shouldBe 2
	}

	test("media create retry delete and password submissions are never automatically replayed") {
		for (action in 0..3) {
			var calls = 0
			val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
				(request.method == "POST" || request.method == "DELETE") shouldBe true
				calls++
				throw SocketTimeoutException("unit-write-response-loss")
			})
			expectFailure<SeerrFailure> { when (action) {
				0 -> api.submit(SeerrRequestBody("movie", 3))
				1 -> api.retryRequest(42)
				2 -> api.deleteRequest(42)
				else -> api.login("unit@example.invalid", "unit-password")
			} }
			calls shouldBe 1
		}
	}

	test("TLS DNS and secure-store failures do not become a retry loop or misleading sign-out") {
		for (problem in listOf(SSLHandshakeException("unit-cert"), UnknownHostException("unit-dns"), SeerrSessionWriteFailure())) {
			var calls = 0
			val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { calls++; throw problem })
			val failure = expectFailure<SeerrFailure> { api.me() }
			calls shouldBe 1
			if (problem is SeerrSessionWriteFailure) failure.secureSessionUnavailable shouldBe true
		}
	}

	test("leaving during the recovery pause cancels the second network attempt") {
		coroutineScope {
			val first = CompletableDeferred<Unit>()
			var calls = 0
			val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls {
				calls++; first.complete(Unit); throw SocketTimeoutException("unit-timeout")
			})
			val pending = launch { api.me() }
			first.await()
			pending.cancelAndJoin()
			calls shouldBe 1
		}
	}

	test("foreground validation can bypass a recently cached navigation account") {
		var calls = 0
		val api = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { calls++; 200 to user })
		api.meForNavigation()
		api.meForNavigation()
		calls shouldBe 1
		api.invalidateNavigationCheck()
		api.meForNavigation()
		calls shouldBe 2
	}
})
