package org.jellyfin.androidtv.ui.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Anonymous, isolated transport: no Jellyfin/Seerr client, cookies, tokens or shared interceptors. */
internal class ForkUpdateClient(private val calls: Call.Factory = OkHttpClient.Builder()
	.cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
	.connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()) {
	private val active = AtomicReference<Call?>()
	fun cancel() { active.getAndSet(null)?.cancel() }

	private suspend fun response(url: String, initial: String, asset: Boolean): Response {
		val parsed = url.toHttpUrl()
		updateRequire(allowedUpdateUrl(parsed, initial, asset), "The update download redirected outside GitHub. Nothing was installed.")
		val request = Request.Builder().url(parsed).header("User-Agent", "betterjelly-androidtv-updater")
			.header("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json").build()
		return suspendCancellableCoroutine { continuation ->
			val call = calls.newCall(request)
			active.set(call)
			continuation.invokeOnCancellation { call.cancel() }
			call.enqueue(object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					if (continuation.isActive) continuation.resumeWithException(e)
				}
				override fun onResponse(call: Call, response: Response) {
					continuation.resume(response) { _, value, _ -> value.close() }
				}
			})
		}
	}

	private suspend fun open(initial: String, asset: Boolean): Response {
		var url = initial
		repeat(6) {
			val response = response(url, initial, asset)
			if (response.code in setOf(301, 302, 303, 307, 308)) {
				val next = response.header("Location")?.let(response.request.url::resolve)
				response.close()
				updateRequire(asset && next != null)
				url = next.toString()
			} else {
				if (!response.isSuccessful) {
					val code = response.code
					response.close()
					throw UpdateFailure(when (code) {
						403, 429 -> "GitHub is limiting update checks. Please try again later."
						404 -> "No published update is available yet. Please try again later."
						else -> "GitHub could not provide the update (HTTP $code). Please try again later."
					})
				}
				return response
			}
		}
		throw UpdateFailure("Too many redirects while fetching the update.")
	}

	private suspend fun read(url: String, limit: Int, asset: Boolean): ByteArray = withContext(Dispatchers.IO) {
		open(url, asset).use { response ->
			val body = response.body ?: throw UpdateFailure("The update server returned an empty response.")
			updateRequire(body.contentLength() <= limit)
			val output = ByteArrayOutputStream()
			body.byteStream().use { input ->
				val buffer = ByteArray(8192)
				while (true) {
					currentCoroutineContext().ensureActive()
					val count = input.read(buffer)
					if (count == -1) break
					updateRequire(output.size() + count <= limit)
					output.write(buffer, 0, count)
				}
			}
			output.toByteArray()
		}
	}

	suspend fun latest(): ForkRelease {
		val listing = parseUpdateListing(read(RELEASES_API, 512 * 1024, false).toString(Charsets.UTF_8))
		return parseUpdateManifest(read(listing.manifest.url, 32 * 1024, true), listing)
	}

	suspend fun download(release: ForkRelease, directory: File, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
		updateRequire(directory.isDirectory || directory.mkdirs(), "Could not create update storage. Please free some space and retry.")
		updateRequire(directory.usableSpace >= release.asset.size + 5 * 1024 * 1024, "Not enough space for the update. Please free some space and retry.")
		val partial = File.createTempFile("download-", ".part", directory)
		try {
			open(release.asset.url, true).use { response ->
				val body = response.body ?: throw UpdateFailure("The update download was empty.")
				updateRequire(body.contentLength() == -1L || body.contentLength() == release.asset.size)
				body.byteStream().use { input ->
					partial.outputStream().use { output -> copyVerifiedUpdate(input, output, release.asset, progress) { currentCoroutineContext().ensureActive() } }
				}
			}
			currentCoroutineContext().ensureActive()
			val target = File(directory, "update-${release.code}-${release.asset.sha256.take(16)}.apk")
			if (!target.exists() || !verifyDownloadedUpdate(target, release.asset)) {
				updateRequire(!target.exists() || target.delete(), "The stored update could not be replaced. Please retry.")
				updateRequire(partial.renameTo(target), "The update could not be saved. Please retry.")
			}
			target
		} finally { partial.delete() }
	}
}

internal suspend fun copyVerifiedUpdate(input: java.io.InputStream, output: java.io.OutputStream, asset: UpdateAsset,
	progress: (Int) -> Unit, checkCancelled: suspend () -> Unit) {
	updateRequire(asset.size in 1..MAX_APK_BYTES)
	val digest = MessageDigest.getInstance("SHA-256")
	val buffer = ByteArray(64 * 1024)
	var total = 0L
	var lastPercent = -1
	while (true) {
		checkCancelled()
		val count = input.read(buffer)
		if (count == -1) break
		total += count
		updateRequire(total <= asset.size, "The update download has an unexpected size. Please retry.")
		digest.update(buffer, 0, count)
		output.write(buffer, 0, count)
		val percent = (total * 100 / asset.size).toInt()
		if (percent != lastPercent) { lastPercent = percent; progress(percent) }
	}
	updateRequire(total == asset.size && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256,
		"The update download failed its integrity check. Nothing was installed. Please retry.")
}

internal fun verifyDownloadedUpdate(file: File, asset: UpdateAsset): Boolean {
	if (!file.isFile || file.length() != asset.size) return false
	val digest = MessageDigest.getInstance("SHA-256")
	file.inputStream().use { input ->
		val buffer = ByteArray(64 * 1024)
		while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
	}
	return digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
}
