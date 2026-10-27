package org.jellyfin.androidtv.ui.seerr

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

/** Recover stale connections for reads only. Mutations must never be transparently replayed. */
internal class SeerrTransport(jar: CookieJar) : Call.Factory {
	private val writes = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
		.retryOnConnectionFailure(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
		.callTimeout(15, TimeUnit.SECONDS).build()
	private val reads = writes.newBuilder().retryOnConnectionFailure(true).build()
	private val mutations = writes.newBuilder().readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
	private val plex = writes.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

	override fun newCall(request: Request): Call =
		if (request.method == "GET") reads.newCall(request) else writes.newCall(singleAttempt(request))

	fun plexCall(request: Request): Call = plex.newCall(singleAttempt(request))
	fun mutationCall(request: Request): Call = mutations.newCall(singleAttempt(request))

	private fun singleAttempt(request: Request): Request {
		val body = request.body ?: return request
		// Also prevents HTTP follow-up retries (e.g. 503 Retry-After: 0), not just connection retries.
		val oneShot = object : RequestBody() {
			override fun contentType() = body.contentType()
			override fun contentLength() = body.contentLength()
			override fun isOneShot() = true
			override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
		}
		return request.newBuilder().method(request.method, oneShot).build()
	}
}
