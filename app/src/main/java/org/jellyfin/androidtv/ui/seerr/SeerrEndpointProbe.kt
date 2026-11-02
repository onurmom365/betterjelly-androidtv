package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal class SeerrEndpointCheckFailure(val userMessage: String, override val retryableRead: Boolean = false) : IOException(userMessage), SeerrRetryableReadFailure
internal data class SeerrEndpointCheck(val version: String)
private val publicSeerrTransport by lazy { SeerrTransport(CookieJar.NO_COOKIES) }

/** Anonymous public GETs only. This probe has no access to an account's encrypted cookie store. */
internal suspend fun checkSeerrEndpoint(endpoint: SeerrEndpoint, factory: Call.Factory = publicSeerrTransport): SeerrEndpointCheck {
	val status = publicSeerrJson(endpoint, "/api/v1/status", factory)
	val settings = publicSeerrJson(endpoint, "/api/v1/settings/public", factory)
	val version = runCatching { status["version"]?.jsonPrimitive?.contentOrNull }.getOrNull()
	val initialized = runCatching { settings["initialized"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
	if (version.isNullOrBlank() || initialized != true) throw SeerrEndpointCheckFailure("This address did not return a ready Seerr API.")
	return SeerrEndpointCheck(version)
}

private suspend fun publicSeerrJson(endpoint: SeerrEndpoint, path: String, factory: Call.Factory): JsonObject =
	recoverSeerrRead { publicSeerrJsonOnce(endpoint, path, factory) }

private suspend fun publicSeerrJsonOnce(endpoint: SeerrEndpoint, path: String, factory: Call.Factory): JsonObject =
	suspendCancellableCoroutine { continuation ->
		val request = Request.Builder().url(endpoint.url.newBuilder().encodedPath(path).build())
			.header("Accept", "application/json").header("Cache-Control", "no-store").build()
		val call = factory.newCall(request)
		continuation.invokeOnCancellation { call.cancel() }
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				val issue = SeerrConnectionIssue.from(e)
				if (continuation.isActive) continuation.resumeWith(Result.failure(
					SeerrEndpointCheckFailure("${issue.description} Retry the connection test.", issue.retryable)))
			}
			override fun onResponse(call: Call, response: Response) {
				val result = runCatching {
					response.use {
						if (!it.isSuccessful) throw SeerrEndpointCheckFailure(when (it.code) {
							in 300..399 -> "This address redirects. Enter the final Seerr HTTPS address."
							401, 403 -> "The server blocked this public check. Check its address or proxy sign-in requirements."
							else -> "The server returned HTTP ${it.code}. Please retry later."
						}, retryableRead = it.code in listOf(408, 502, 503, 504))
						val source = it.body?.source() ?: throw SeerrEndpointCheckFailure("This address did not return a ready Seerr API.")
						source.request(65_537)
						if (source.buffer.size > 65_536) throw SeerrEndpointCheckFailure("The public Seerr response was too large.")
						seerrJson.parseToJsonElement(source.readUtf8()) as? JsonObject ?: throw SeerrEndpointCheckFailure("This address did not return a ready Seerr API.")
					}
				}.recoverCatching { throw (it as? SeerrEndpointCheckFailure ?: (it as? IOException)?.let { error ->
					SeerrConnectionIssue.from(error).let { issue -> SeerrEndpointCheckFailure("${issue.description} Retry the connection test.", issue.retryable) }
				} ?: SeerrEndpointCheckFailure("This address did not return a ready Seerr API.")) }
				if (continuation.isActive) continuation.resumeWith(result)
			}
		})
	}
