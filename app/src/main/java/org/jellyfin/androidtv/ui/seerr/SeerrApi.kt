package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

const val SEERR_ORIGIN = "http://192.168.1.199:5055"
internal val seerrJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
// Catalog metadata can contain explicit nulls where TMDB has no value. Required IDs stay strict.
internal val seerrCatalogJson = Json(seerrJson) { coerceInputValues = true }

class SeerrFailure(val status: Int = 0, val submissionUncertain: Boolean = false,
	val invalidResponse: Boolean = false, val requestId: Int? = null, val secureSessionUnavailable: Boolean = false) : IOException() {
	val userMessage get() = when {
		submissionUncertain -> (requestId?.let { "Seerr returned request #$it, but its saved record could not be confirmed. " }
			?: "The result could not be confirmed. ") + "Check status before trying again; do not submit a duplicate."
		status == 401 || status == 403 -> "Sign-in or permission denied. Check your Seerr account and try again."
		status == 409 || status == 202 -> "Already requested or no additional seasons are available. Refresh this title."
		status == 429 -> "Seerr is busy. Please try again shortly."
		status in 300..399 -> "Seerr redirected the connection. Check the server address."
		status > 0 -> "Seerr could not complete this action. Refresh its status or try again later."
		secureSessionUnavailable -> "The saved Seerr session could not be opened securely. Retry the session check, or sign in again."
		invalidResponse -> "Seerr responded, but its data could not be read. Please refresh and try again."
		else -> "Seerr is unavailable. Library browsing and playback still work; return to your library or retry."
	}
}

internal class SeerrCookieJar(private val origin: HttpUrl, private val store: SeerrSessionStore) : CookieJar {
	private var cookies = emptyList<Cookie>()
	private var loaded = false
	private fun sameOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
	private fun valid(cookie: Cookie) = cookie.domain == origin.host && cookie.expiresAt > System.currentTimeMillis()
	@Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
		if (!sameOrigin(url)) return emptyList()
		if (!loaded) reload() // OkHttp invokes this on its worker, not while the screen is being composed.
		return cookies.filter { valid(it) && it.matches(url) }
	}
	@Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (!sameOrigin(url)) return
		this.cookies = (this.cookies.filter { old -> cookies.none { it.name == old.name && it.path == old.path } } + cookies)
			.filter { valid(it) }
		loaded = true
		try { store.write(this.cookies.map { it.toString() }) }
		catch (_: Exception) { throw IOException("Secure session storage unavailable") }
	}
	@Synchronized fun clear() { cookies = emptyList(); loaded = true; store.clear() }
	@Synchronized fun reload(): Boolean {
		val saved = try { store.read() } catch (_: Exception) { throw SeerrFailure(secureSessionUnavailable = true) }
		cookies = saved.mapNotNull { Cookie.parse(origin, it) }.filter { valid(it) }
		loaded = true
		return cookies.isNotEmpty()
	}
}

class SeerrApi internal constructor(
	store: SeerrSessionStore,
	private val accessAllowed: () -> Boolean = { true },
	private val callFactory: Call.Factory? = null,
	private val endpoint: SeerrEndpoint = defaultSeerrEndpoint,
) {
	private val origin = endpoint.url
	private val jar = SeerrCookieJar(origin, store)
	// Dedicated transport: GET connection recovery, single-attempt mutations, no Jellyfin headers/logging.
	private val transport = SeerrTransport(jar)
	private val catalog = SeerrTimedCache<String, SeerrTitle>(64, TimeUnit.MINUTES.toNanos(5))
	private val searches = SeerrTimedCache<String, SeerrPage>(12, TimeUnit.SECONDS.toNanos(30))
	private val navigationUser = SeerrTimedCache<Unit, SeerrUser>(1, TimeUnit.SECONDS.toNanos(30))
	private fun checkAccess() { if (!accessAllowed()) throw SeerrFailure(403) }
	fun cachedTitle(type: String, id: Int): SeerrTitle? = if (accessAllowed()) catalog.get("$type/$id") else null
	fun invalidateCatalog() { catalog.clear(); searches.clear() }

	private suspend fun exchange(request: Request, plex: Boolean = false, submitting: Boolean = false, emptySuccess: Boolean = false): String =
		suspendCancellableCoroutine { continuation ->
			if (!accessAllowed()) {
				continuation.resumeWith(Result.failure(SeerrFailure(403)))
				return@suspendCancellableCoroutine
			}
			val call = callFactory?.newCall(request) ?: when {
				plex -> transport.plexCall(request)
				submitting -> transport.mutationCall(request)
				else -> transport.newCall(request)
			}
			continuation.invokeOnCancellation { call.cancel() }
			call.enqueue(object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					if (continuation.isActive) continuation.resumeWith(Result.failure(SeerrFailure(submissionUncertain = submitting)))
				}
				override fun onResponse(call: Call, response: Response) {
					val result = runCatching {
						response.use {
							if (!it.isSuccessful || it.code == 202) throw SeerrFailure(it.code)
							if (emptySuccess && it.code == 204) return@use ""
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

	private fun request(path: String, query: Map<String, String> = emptyMap(), body: String? = null, httpDelete: Boolean = false): Request {
		require(path.startsWith('/') && !path.contains(".."))
		val url = origin.newBuilder().encodedPath("/api/v1$path").apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
		return Request.Builder().url(url).header("Accept", "application/json").apply {
			if (httpDelete) delete("".toRequestBody()) else if (body != null) post(body.toRequestBody("application/json".toMediaType()))
		}.build()
	}
	private suspend inline fun <reified T> decode(body: String): T = withContext(Dispatchers.Default) {
		try { seerrJson.decodeFromString(body) } catch (_: Exception) { throw SeerrFailure(invalidResponse = true) }
	}

	suspend fun me(): SeerrUser = decode<SeerrUser>(exchange(request("/auth/me"))).also { navigationUser.put(Unit, it) }
	suspend fun meForNavigation(): SeerrUser { checkAccess(); return navigationUser.get(Unit) ?: me() }
	suspend fun login(email: String, password: String): SeerrUser {
		jar.clear()
		navigationUser.clear(); invalidateCatalog()
		val body = buildJsonObject { put("email", email); put("password", password) }.toString()
		exchange(request("/auth/local", body = body))
		return me()
	}
	fun disconnect() { jar.clear(); navigationUser.clear(); invalidateCatalog() }
	fun restoreSession() = jar.reload().also { if (!it) navigationUser.clear() }
	suspend fun titles(query: String, type: String, page: Int): SeerrPage {
		checkAccess()
		val cacheKey = "${query.trim().lowercase()}|$type|$page"
		searches.get(cacheKey)?.let { return it }
		val path = if (query.isBlank()) "/discover/${if (type == "movie") "movies" else "tv"}" else "/search"
		val params = mutableMapOf("page" to page.toString()).apply { if (query.isNotBlank()) put("query", query.trim()) }
		val response = exchange(request(path, params))
		return withContext(Dispatchers.Default) { try {
			// Filter heterogeneous search results before decoding movie/TV metadata.
			val page = seerrJson.parseToJsonElement(response) as JsonObject
			val entries = page["results"] as JsonArray
			val filtered = entries.filter { entry ->
				val kind = (entry as? JsonObject)?.get("mediaType")?.jsonPrimitive?.contentOrNull
				kind in listOf("movie", "tv") || (query.isBlank() && kind.isNullOrBlank())
			}
			val results = seerrCatalogJson.decodeFromJsonElement(SeerrPage.serializer(), JsonObject(page + ("results" to JsonArray(filtered))))
			results.copy(results = results.results.map { if (it.mediaType.isBlank()) it.copy(mediaType = type) else it }).also { result ->
				searches.put(cacheKey, result)
				result.results.forEach { catalog.put("${it.mediaType}/${it.id}", it) }
			}
		} catch (_: Exception) { throw SeerrFailure(invalidResponse = true) } }
	}
	suspend fun details(type: String, id: Int): SeerrTitle {
		require(type in listOf("movie", "tv") && id > 0)
		return decode<SeerrTitle>(exchange(request("/$type/$id"))).copy(mediaType = type).also { catalog.put("$type/$id", it) }
	}
	suspend fun metadata(type: String, id: Int): SeerrTitle { checkAccess(); return cachedTitle(type, id) ?: details(type, id) }
	suspend fun services(type: String): List<SeerrService> = decode(exchange(request("/service/${serviceType(type)}")))
	suspend fun service(type: String, id: Int): SeerrServiceDetails = decode(exchange(request("/service/${serviceType(type)}/$id")))
	private fun serviceType(type: String): String { require(type in listOf("movie", "tv")); return if (type == "movie") "radarr" else "sonarr" }
	suspend fun submit(body: SeerrRequestBody): SeerrRequest {
		// Resolution belongs to the selected profile; this fork does not create a separate 4K request lane.
		require(!body.is4k)
		invalidateCatalog()
		val response = exchange(request("/request", body = seerrJson.encodeToString(body)), submitting = true)
		return try { decode<SeerrRequest>(response) } catch (_: Exception) { throw SeerrFailure(submissionUncertain = true) }
	}
	suspend fun requestRecord(id: Int): SeerrRequest { require(id > 0); return decode(exchange(request("/request/$id"))) }
	suspend fun retryRequest(id: Int): SeerrRequest {
		require(id > 0); invalidateCatalog()
		return decode(exchange(request("/request/$id/retry", body = "{}"), submitting = true))
	}
	suspend fun deleteRequest(id: Int) {
		require(id > 0); invalidateCatalog()
		exchange(request("/request/$id", httpDelete = true), submitting = true, emptySuccess = true)
	}

	/** A POST response alone is not a saved-record confirmation. This read never retries the POST. */
	suspend fun confirmRequest(body: SeerrRequestBody, id: Int): SeerrRequest {
		require(id > 0)
		try {
			return decode<SeerrRequest>(exchange(request("/request/$id"))).also { receipt ->
				require(receipt.matchesSavedRequest(body) && receipt.id == id)
			}
		} catch (error: CancellationException) { throw error }
		catch (_: Exception) { throw SeerrFailure(submissionUncertain = true, requestId = id) }
	}
	suspend fun history(userId: Int, filter: String, page: Int): SeerrRequestPage {
		require(userId > 0 && filter in listOf("all", "pending", "processing", "available", "failed") && page > 0)
		return decode(exchange(request("/request", mapOf("requestedBy" to userId.toString(), "filter" to filter,
			"take" to "12", "skip" to ((page - 1) * 12).toString(), "sort" to "added"))))
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
		navigationUser.clear(); invalidateCatalog()
		exchange(request("/auth/plex", body = buildJsonObject { put("authToken", token) }.toString()))
		return true // Token is exchanged once; never retained or placed in UI state/storage.
	}
}
