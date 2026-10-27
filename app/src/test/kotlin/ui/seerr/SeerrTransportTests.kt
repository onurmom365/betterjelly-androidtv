package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real OkHttp/socket tests on loopback only; never contacts the NAS or uses a saved session. */
private class SeerrLoopbackServer(handler: (ServerSocket) -> Unit) : AutoCloseable {
	private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 700 }
	private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "seerr-loopback-test").apply { isDaemon = true } }
	private val task = worker.submit(Callable { handler(socket) })
	val url = "http://127.0.0.1:${socket.localPort}/auth/me"
	fun await() { task.get(5, TimeUnit.SECONDS) }
	override fun close() { socket.close(); worker.shutdownNow() }
}

private fun Socket.readTestRequest(): String {
	soTimeout = 2_000
	val reader = getInputStream().bufferedReader(Charsets.UTF_8)
	val first = reader.readLine() ?: throw IOException("No test request")
	var length = 0
	while (true) {
		val header = reader.readLine() ?: throw IOException("Incomplete test request")
		if (header.isEmpty()) break
		if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
	}
	repeat(length) { if (reader.read() < 0) throw IOException("Incomplete test body") }
	return first
}

private fun Socket.testResponse(status: String = "200 OK", close: Boolean = false, extra: String = "") {
	getOutputStream().apply {
		write(("HTTP/1.1 $status\r\nContent-Length: 2\r\nConnection: ${if (close) "close" else "keep-alive"}\r\n$extra\r\n{}").toByteArray(Charsets.UTF_8))
		flush()
	}
}

private fun droppedPooledRead(factory: Call.Factory): Pair<Boolean, Int> {
	val received = AtomicInteger()
	SeerrLoopbackServer { server ->
		server.accept().use { socket ->
			socket.readTestRequest().startsWith("GET") shouldBe true
			received.incrementAndGet()
			socket.testResponse()
			// Drop a previously healthy pooled connection during the next read (idle-close race).
			socket.readTestRequest().startsWith("GET") shouldBe true
			received.incrementAndGet()
		}
		try {
			server.accept().use { socket ->
				socket.readTestRequest().startsWith("GET") shouldBe true
				received.incrementAndGet()
				socket.testResponse(close = true)
			}
		} catch (_: SocketTimeoutException) { /* The old policy gives up instead of reconnecting. */ }
	}.use { server ->
		val request = Request.Builder().url(server.url).build()
		factory.newCall(request).execute().use { it.body!!.string() shouldBe "{}" }
		val succeeded = try { factory.newCall(request).execute().use { it.body!!.string() shouldBe "{}" }; true }
		catch (_: IOException) { false }
		server.await()
		return succeeded to received.get()
	}
}

class SeerrTransportTests : FunSpec({
	test("previous single-attempt GET policy reproduces a failed sign-in recheck on a dropped pooled connection") {
		val previous = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(4, TimeUnit.SECONDS).build()
		droppedPooledRead(previous) shouldBe (false to 2)
	}
	test("read-only sign-in recheck recovers from a dropped pooled connection using a fresh socket") {
		droppedPooledRead(SeerrTransport(CookieJar.NO_COOKIES)) shouldBe (true to 3)
	}
	test("a media POST whose response is lost is sent once without automatic replay") {
		val received = AtomicInteger()
		SeerrLoopbackServer { server ->
			try { repeat(2) {
				server.accept().use { socket ->
					socket.readTestRequest().startsWith("POST") shouldBe true
					received.incrementAndGet()
					// Close after receiving the body, without a response.
				}
			} } catch (_: SocketTimeoutException) { }
		}.use { server ->
			val request = Request.Builder().url(server.url).post("{}".toRequestBody()).build()
			expectFailure<IOException> { SeerrTransport(CookieJar.NO_COOKIES).mutationCall(request).execute().use { it.close() } }
			server.await()
			received.get() shouldBe 1
		}
	}
	test("a media POST receiving 503 Retry-After zero is not automatically resubmitted") {
		val received = AtomicInteger()
		SeerrLoopbackServer { server ->
			try { repeat(2) {
				server.accept().use { socket ->
					socket.readTestRequest().startsWith("POST") shouldBe true
					received.incrementAndGet()
					socket.testResponse("503 Service Unavailable", close = true, extra = "Retry-After: 0\r\n")
				}
			} } catch (_: SocketTimeoutException) { }
		}.use { server ->
			val request = Request.Builder().url(server.url).post("{}".toRequestBody()).build()
			SeerrTransport(CookieJar.NO_COOKIES).mutationCall(request).execute().use { it.code shouldBe 503 }
			server.await()
			received.get() shouldBe 1
		}
	}
	test("a request DELETE receiving a retry hint is sent once") {
		val received = AtomicInteger()
		SeerrLoopbackServer { server ->
			try { repeat(2) {
				server.accept().use { socket ->
					socket.readTestRequest().startsWith("DELETE") shouldBe true
					received.incrementAndGet()
					socket.testResponse("503 Service Unavailable", close = true, extra = "Retry-After: 0\r\n")
				}
			} } catch (_: SocketTimeoutException) { }
		}.use { server ->
			val request = Request.Builder().url(server.url).delete("".toRequestBody()).build()
			SeerrTransport(CookieJar.NO_COOKIES).mutationCall(request).execute().use { it.code shouldBe 503 }
			server.await()
			received.get() shouldBe 1
		}
	}
})
