package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.Call
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SeerrEndpointProbeTests : FunSpec({
	val remote = SeerrEndpoint.parse(SEERR_REMOTE_ORIGIN)

	test("connection check uses exactly the two anonymous public GET routes") {
		val paths = mutableListOf<String>()
		val result = checkSeerrEndpoint(remote, endpointTestFactory { request ->
			request.method shouldBe "GET"
			request.url.host shouldBe remote.url.host
			request.header("Cookie") shouldBe null
			request.header("Authorization") shouldBe null
			request.header("X-Api-Key") shouldBe null
			paths += request.url.encodedPath
			200 to if (request.url.encodedPath.endsWith("/status")) """{"version":"unit-version"}""" else """{"initialized":true}"""
		})
		result.version shouldBe "unit-version"
		paths shouldBe listOf("/api/v1/status", "/api/v1/settings/public")
	}

	test("a proxy redirect is reported without following it or exposing its response") {
		var calls = 0
		val error = expectFailure<SeerrEndpointCheckFailure> {
			checkSeerrEndpoint(remote, endpointTestFactory { calls++; 302 to "unit-sensitive-response" })
		}
		error.userMessage.contains("redirects") shouldBe true
		error.userMessage.contains("sensitive") shouldBe false
		calls shouldBe 1
	}

	test("HTML malformed JSON or an uninitialized server cannot count as successful Seerr connectivity") {
		for (body in listOf("<html>sign-in</html>", "[]", "{}", """{"version":"unit-version","initialized":false}""")) {
			expectFailure<SeerrEndpointCheckFailure> { checkSeerrEndpoint(remote, endpointTestFactory { 200 to body }) }
		}
	}

	test("denied and unavailable servers return safe actionable errors") {
		for (status in listOf(401, 403, 500, 503)) {
			val failure = expectFailure<SeerrEndpointCheckFailure> {
				checkSeerrEndpoint(remote, endpointTestFactory { status to "unit-private-response" })
			}
			failure.userMessage.contains("private-response") shouldBe false
		}
	}

	test("closing a pending connection check cancels its HTTP call") {
		coroutineScope {
			var cancelled = false
			val factory = Call.Factory {
				mockk<Call> {
					every { enqueue(any()) } returns Unit
					every { cancel() } answers { cancelled = true }
				}
			}
			val pending = launch { checkSeerrEndpoint(remote, factory) }
			yield()
			pending.cancelAndJoin()
			cancelled shouldBe true
		}
	}

	test("real anonymous transport does not retain a server cookie between public checks") {
		// Loopback only. No NAS, real user session, or media mutation.
		ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
			server.soTimeout = 3_000
			val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "endpoint-probe-test").apply { isDaemon = true } }
			try {
				val headers = mutableListOf<String>()
				val task = worker.submit(Callable {
					repeat(2) { index -> server.accept().use { socket ->
						socket.soTimeout = 3_000
						val reader = socket.getInputStream().bufferedReader()
						reader.readLine().startsWith("GET /api/v1/") shouldBe true
						while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers += line }
						val body = if (index == 0) """{"version":"unit-version"}""" else """{"initialized":true}"""
						val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nSet-Cookie: unit-session=unit-cookie; Path=/\r\nConnection: close\r\n\r\n$body"
						socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
					} }
				})
				checkSeerrEndpoint(SeerrEndpoint.parse("http://127.0.0.1:${server.localPort}")).version shouldBe "unit-version"
				task.get(5, TimeUnit.SECONDS)
				headers.any { it.startsWith("Cookie:", true) || it.startsWith("Authorization:", true) } shouldBe false
			} finally { worker.shutdownNow() }
		}
	}
})
