package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

const val SEERR_ORIGIN = "http://192.168.1.199:5055"
internal val seerrJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

class SeerrFailure(val status: Int = 0, val submissionUncertain: Boolean = false) : IOException() {
	val userMessage get() = when {
		submissionUncertain -> "The result could not be confirmed. Refresh this title's status before trying again; the request may have reached Seerr."
		status == 401 || status == 403 -> "Sign-in or permission denied. Check your Seerr account and try again."
		status == 409 || status == 202 -> "Already requested or no additional seasons are available. Refresh this title."
		status == 429 -> "Seerr is busy. Please try again shortly."
		status in 300..399 -> "Seerr redirected the connection. Check the server address."
		status > 0 -> "Seerr could not complete this action. Refresh its status or try again later."
		else -> "Seerr is unavailable. Library browsing and playback still work; return to your library or retry."
	}
}

internal class SeerrCookieJar(private val origin: HttpUrl, private val store: SeerrSessionStore) : CookieJar {
	private var cookies = store.read().mapNotNull { Cookie.parse(origin, it) }.filter { valid(it) }
	private fun sameOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
	private fun valid(cookie: Cookie) = cookie.domain == origin.host && cookie.expiresAt > System.currentTimeMillis()
	@Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
		if (sameOrigin(url)) cookies.filter { valid(it) && it.matches(url) } else emptyList()
	@Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (!sameOrigin(url)) return
		this.cookies = (this.cookies.filter { old -> cookies.none { it.name == old.name && it.path == old.path } } + cookies)
			.filter { valid(it) }
		try { store.write(this.cookies.map { it.toString() }) }
		catch (_: Exception) { throw IOException("Secure session storage unavailable") }
	}
	@Synchronized fun clear() { cookies = emptyList(); store.clear() }
}

class SeerrApi internal constructor(
	store: SeerrSessionStore,
	private val accessAllowed: () -> Boolean = { true },
	private val callFactory: Call.Factory? = null,
) {
	private val origin = SEERR_ORIGIN.toHttpUrl()
	private val jar = SeerrCookieJar(origin, store)
	// Dedicated client: never inherits Jellyfin headers, logging, redirects or automatic POST retries.
	private val client = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
		.retryOnConnectionFailure(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
		.callTimeout(15, TimeUnit.SECONDS).build()
	private val plexClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

	private suspend fun exchange(request: Request, plex: Boolean = false, submitting: Boolean = false): String =
		suspendCancellableCoroutine { continuation ->
			if (!accessAllowed()) {
				continuation.resumeWith(Result.failure(SeerrFailure(403)))
				return@suspendCancellableCoroutine
			}
			val call = (callFactory ?: if (plex) plexClient else client).newCall(request)
			continuation.invokeOnCancellation { call.cancel() }
			call.enqueue(object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					if (continuation.isActive) continuation.resumeWith(Result.failure(SeerrFailure(submissionUncertain = submitting)))
				}
				override fun onResponse(call: Call, response: Response) {
					val result = runCatching {
						response.use {
							if (!it.isSuccessful || it.code == 202) throw SeerrFailure(it.code)
							val body = it.body ?: throw SeerrFailure(submissionUncertain = submitting)
							val source = body.source()
							source.request(3 * 1024 * 1024 + 1L)
							if (source.buffer.size > 3 * 1024 * 1024) throw SeerrFailure(submissionUncertain = submitting)
							source.readUtf8()
						}
					}.recoverCatching { throw (it as? SeerrFailure ?: SeerrFailure(submissionUncertain = submitting)) }
					if (continuation.isActive) continuation.resumeWith(result)
				}
			})
		}

	private fun request(path: String, query: Map<String, String> = emptyMap(), body: String? = null): Request {
		require(path.startsWith('/') && !path.contains(".."))
		val url = origin.newBuilder().encodedPath("/api/v1$path").apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
		return Request.Builder().url(url).header("Accept", "application/json").apply {
			if (body != null) post(body.toRequestBody("application/json".toMediaType()))
		}.build()
	}
	private inline fun <reified T> decode(body: String): T = try { seerrJson.decodeFromString(body) }
	catch (_: Exception) { throw SeerrFailure() }

	suspend fun me(): SeerrUser = decode(exchange(request("/auth/me")))
	suspend fun login(email: String, password: String): SeerrUser {
		jar.clear()
		val body = buildJsonObject { put("email", email); put("password", password) }.toString()
		exchange(request("/auth/local", body = body))
		return me()
	}
	fun disconnect() = jar.clear()
	suspend fun titles(query: String, type: String, page: Int): SeerrPage {
		val path = if (query.isBlank()) "/discover/${if (type == "movie") "movies" else "tv"}" else "/search"
		val params = mutableMapOf("page" to page.toString()).apply { if (query.isNotBlank()) put("query", query.trim()) }
		val results: SeerrPage = decode(exchange(request(path, params)))
		return results.copy(results = results.results.map { if (it.mediaType.isBlank()) it.copy(mediaType = type) else it }
			.filter { it.mediaType in listOf("movie", "tv") })
	}
	suspend fun details(type: String, id: Int): SeerrTitle {
		require(type in listOf("movie", "tv") && id > 0)
		return decode<SeerrTitle>(exchange(request("/$type/$id"))).copy(mediaType = type)
	}
	suspend fun services(type: String): List<SeerrService> = decode(exchange(request("/service/${serviceType(type)}")))
	suspend fun service(type: String, id: Int): SeerrServiceDetails = decode(exchange(request("/service/${serviceType(type)}/$id")))
	private fun serviceType(type: String): String { require(type in listOf("movie", "tv")); return if (type == "movie") "radarr" else "sonarr" }
	suspend fun submit(body: SeerrRequestBody): SeerrRequest {
		val response = exchange(request("/request", body = seerrJson.encodeToString(body)), submitting = true)
		return try { decode<SeerrRequest>(response) } catch (_: Exception) { throw SeerrFailure(submissionUncertain = true) }
	}

	@Serializable data class PlexPin(val id: Int, val code: String, val expiresIn: Int = 300, val authToken: String? = null)
	private fun plexRequest(path: String, identifier: String, create: Boolean = false): Request =
		Request.Builder().url("https://plex.tv/api/v2/pins$path").header("Accept", "application/json")
			.header("X-Plex-Product", "Jellyfin Shield Seerr").header("X-Plex-Client-Identifier", identifier)
			.apply { if (create) post("strong=false".toRequestBody("application/x-www-form-urlencoded".toMediaType())) }.build()
	suspend fun startPlex(identifier: String): PlexPin = decode(exchange(plexRequest("", identifier, true), plex = true))
	suspend fun checkPlex(pin: PlexPin, identifier: String): Boolean {
		val result: PlexPin = decode(exchange(plexRequest("/${pin.id}", identifier), plex = true))
		val token = result.authToken?.takeIf { it.isNotBlank() } ?: return false
		jar.clear()
		exchange(request("/auth/plex", body = buildJsonObject { put("authToken", token) }.toString()))
		return true // Token is exchanged once; never retained or placed in UI state/storage.
	}
}
