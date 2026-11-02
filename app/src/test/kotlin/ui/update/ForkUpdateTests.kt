package org.jellyfin.androidtv.ui.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

// Match the repository's pinned JVM 8 test target without inlining JVM 11 assertion helpers.
private inline fun <reified T : Throwable> expectUpdateFailure(block: () -> Unit): T {
    try { block() } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.simpleName}", error)
    }
    throw AssertionError("Expected ${T::class.simpleName}")
}

private val apkBytes = ByteArray(100003) { (it % 251).toByte() }
private val apkUrl = "https://github.com/$UPDATE_REPOSITORY/releases/download/v1.0.1/betterjelly-androidtv.apk"
private val manifestUrl = "https://github.com/$UPDATE_REPOSITORY/releases/download/v1.0.1/release.json"
private fun manifest(change: (MutableMap<String, JsonElement>) -> Unit = {}): ByteArray {
	val fields = mutableMapOf<String, JsonElement>(
		"schemaVersion" to JsonPrimitive(1), "versionName" to JsonPrimitive("1.0.1"), "versionCode" to JsonPrimitive(1000199),
		"applicationId" to JsonPrimitive(UPDATE_PACKAGE), "minSdk" to JsonPrimitive(21), "debuggable" to JsonPrimitive(false),
		"signingCertificateSha256" to JsonPrimitive(UPDATE_CERTIFICATE), "sourceTag" to JsonPrimitive("v1.0.1"),
		"apkUrl" to JsonPrimitive(apkUrl), "sha256" to JsonPrimitive(sha256(apkBytes)))
	change(fields)
	return JsonObject(fields).toString().toByteArray()
}
private fun listing(bytes: ByteArray = manifest(), change: (MutableMap<String, JsonElement>) -> Unit = {}): String {
	fun asset(name: String, url: String, content: ByteArray) = buildJsonObject {
		put("name", name); put("browser_download_url", url); put("state", "uploaded"); put("size", content.size); put("digest", "sha256:${sha256(content)}")
	}
	val fields = mutableMapOf<String, JsonElement>("tag_name" to JsonPrimitive("v1.0.1"), "draft" to JsonPrimitive(false),
		"prerelease" to JsonPrimitive(false), "body" to JsonPrimitive("Update notes"),
		"assets" to JsonArray(listOf(asset("release.json", manifestUrl, bytes), asset("betterjelly-androidtv.apk", apkUrl, apkBytes))))
	change(fields)
	return JsonObject(fields).toString()
}
private fun candidate() = parseUpdateManifest(manifest(), parseUpdateListing(listing()))
private fun factory(handler: (Request) -> Response) = Call.Factory { request ->
	mockk<Call> {
		every { cancel() } returns Unit
		every { enqueue(any()) } answers {
			val callback = firstArg<Callback>()
			try { callback.onResponse(this@mockk, handler(request)) }
			catch (error: IOException) { callback.onFailure(this@mockk, error) }
		}
	}
}
private fun reply(request: Request, body: ByteArray, code: Int = 200, location: String? = null) = Response.Builder()
	.request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
	.body(body.toResponseBody("application/octet-stream".toMediaType())).apply { if (location != null) header("Location", location) }.build()

class ForkUpdateTests : FunSpec({
	test("stable release API and hashed manifest agree on fork identity version and APK") {
		val release = candidate()
		release.version shouldBe "1.0.1"
		release.code shouldBe 1000199
		release.asset.sha256 shouldBe sha256(apkBytes)
	}
	test("draft prerelease malformed and missing asset releases are rejected") {
		for (text in listOf("<html>private-response</html>", "{}", listing { it["draft"] = JsonPrimitive(true) },
			listing { it["prerelease"] = JsonPrimitive(true) }, listing { it["assets"] = JsonArray(emptyList()) },
			listing { it["tag_name"] = JsonPrimitive("v1.0.1-rc.1") })) {
			expectUpdateFailure<UpdateFailure> { parseUpdateListing(text) }.userMessage.contains("private-response") shouldBe false
		}
	}
	test("duplicate or externally hosted API assets are not accepted") {
		val root = Json.parseToJsonElement(listing()).jsonObject
		val assets = root["assets"]!!.jsonArray
		expectUpdateFailure<UpdateFailure> { parseUpdateListing(listing { it["assets"] = JsonArray(assets + assets.last()) }) }
		for (url in listOf("http://github.com/$UPDATE_REPOSITORY/releases/download/v1.0.1/betterjelly-androidtv.apk",
			"https://example.invalid/file.apk", apkUrl.replace("onurmom365", "other"), apkUrl + "?token=anything")) {
			val changed = assets.last().jsonObject.toMutableMap().apply { put("browser_download_url", JsonPrimitive(url)) }
			expectUpdateFailure<UpdateFailure> { parseUpdateListing(listing { it["assets"] = JsonArray(listOf(assets.first(), JsonObject(changed))) }) }
		}
	}
	test("missing digests or oversized APK declarations cannot offer downloads") {
		for (change in listOf<Pair<String, JsonElement>>("digest" to JsonPrimitive("md5:1234"), "size" to JsonPrimitive(MAX_APK_BYTES + 1), "size" to JsonPrimitive(0))) {
			val root = Json.parseToJsonElement(listing()).jsonObject
			val assets = root["assets"]!!.jsonArray
			val changed = assets.last().jsonObject.toMutableMap().apply { put(change.first, change.second) }
			expectUpdateFailure<UpdateFailure> { parseUpdateListing(listing { it["assets"] = JsonArray(listOf(assets.first(), JsonObject(changed))) }) }
		}
	}
	test("tampered manifest bytes are rejected before interpreting their contents") {
		expectUpdateFailure<UpdateFailure> { parseUpdateManifest(manifest() + ' '.code.toByte(), parseUpdateListing(listing())) }
	}
	test("wrong package signer version APK hash or URL in hashed metadata is rejected") {
		for ((key, value) in listOf("applicationId" to JsonPrimitive("org.jellyfin.androidtv"),
			"signingCertificateSha256" to JsonPrimitive("a".repeat(64)), "versionCode" to JsonPrimitive(1),
			"versionName" to JsonPrimitive("2.0.0"), "sourceTag" to JsonPrimitive("v9.9.9"),
			"debuggable" to JsonPrimitive(true), "minSdk" to JsonPrimitive(0), "schemaVersion" to JsonPrimitive(2),
			"apkUrl" to JsonPrimitive("https://example.invalid/update.apk"), "sha256" to JsonPrimitive("b".repeat(64)))) {
			val bytes = manifest { it[key] = value }
			expectUpdateFailure<UpdateFailure> { parseUpdateManifest(bytes, parseUpdateListing(listing(bytes))) }
		}
	}
	test("only original GitHub URL or exact HTTPS asset CDN hosts may receive redirects") {
		allowedUpdateUrl(apkUrl.toHttpUrl(), apkUrl, true) shouldBe true
		for (host in listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
			allowedUpdateUrl("https://$host/path?signature=test".toHttpUrl(), apkUrl, true) shouldBe true
		for (url in listOf("http://release-assets.githubusercontent.com/file", "https://github.com.evil.invalid/file", "https://example.invalid/file",
			"https://user:password@objects.githubusercontent.com/file", "https://objects.githubusercontent.com:444/file", "https://objects.githubusercontent.com/file#fragment"))
			allowedUpdateUrl(url.toHttpUrl(), apkUrl, true) shouldBe false
		allowedUpdateUrl("https://objects.githubusercontent.com/file".toHttpUrl(), RELEASES_API, false) shouldBe false
		allowedUpdateUrl("https://example.invalid/file".toHttpUrl(), "https://example.invalid/file", true) shouldBe false
	}
	test("archive validation accepts only newer signed non-debuggable fork package") {
		val installed = UpdateArchive(UPDATE_PACKAGE, "1.0.0", 1000099, listOf(UPDATE_CERTIFICATE), false, 21)
		val downloaded = installed.copy(version = "1.0.1", code = 1000199)
		verifyUpdateArchive(candidate(), downloaded, installed, 30)
		for (bad in listOf(downloaded.copy(packageName = "org.jellyfin.androidtv"), downloaded.copy(certificates = listOf("a".repeat(64))),
			downloaded.copy(certificates = emptyList()), downloaded.copy(certificates = listOf(UPDATE_CERTIFICATE, UPDATE_CERTIFICATE)),
			downloaded.copy(debuggable = true), downloaded.copy(code = 1000099), downloaded.copy(version = "9.0.0"), downloaded.copy(minSdk = 31)))
			expectUpdateFailure<UpdateFailure> { verifyUpdateArchive(candidate(), bad, installed, 30) }
	}
	test("official app wrong current signing identity and already installed versions cannot be updated") {
		val installed = UpdateArchive(UPDATE_PACKAGE, "1.0.0", 1000099, listOf(UPDATE_CERTIFICATE), false, 21)
		val downloaded = installed.copy(version = "1.0.1", code = 1000199)
		for (bad in listOf(installed.copy(packageName = "org.jellyfin.androidtv"), installed.copy(certificates = listOf("b".repeat(64))), downloaded))
			expectUpdateFailure<UpdateFailure> { verifyUpdateArchive(candidate(), downloaded, bad, 30) }
		expectUpdateFailure<UpdateFailure> { verifyUpdateArchive(candidate().copy(minSdk = 31), downloaded, installed, 30) }
	}
	test("transfer verifies exact length and hash and coalesces progress") {
		val output = ByteArrayOutputStream()
		val progress = mutableListOf<Int>()
		copyVerifiedUpdate(apkBytes.inputStream(), output, candidate().asset, progress::add) {}
		output.toByteArray().contentEquals(apkBytes) shouldBe true
		progress.last() shouldBe 100
		(progress.size <= 101 && progress.zipWithNext().all { (a, b) -> a < b }) shouldBe true
		for (bytes in listOf(apkBytes.dropLast(1).toByteArray(), apkBytes + 1.toByte(), apkBytes.copyOf().apply { this[0] = 99 }))
			expectUpdateFailure<UpdateFailure> { copyVerifiedUpdate(bytes.inputStream(), ByteArrayOutputStream(), candidate().asset, {}) {} }
	}
	test("transfer honors cancellation before another read and does not replay bytes") {
		var checks = 0
		val output = ByteArrayOutputStream()
		expectUpdateFailure<CancellationException> {
			copyVerifiedUpdate(apkBytes.inputStream(), output, candidate().asset, {}) { if (++checks == 2) throw CancellationException() }
		}
		checks shouldBe 2
		(output.size() in 1 until apkBytes.size) shouldBe true
	}
	test("latest uses only anonymous GET requests to the exact repository") {
		val paths = mutableListOf<String>()
		val client = ForkUpdateClient(factory { request ->
			request.method shouldBe "GET"
			for (header in listOf("Cookie", "Authorization", "X-Api-Key")) request.header(header) shouldBe null
			paths += request.url.toString()
			reply(request, if (request.url.toString() == RELEASES_API) listing().toByteArray() else manifest())
		})
		client.latest().code shouldBe 1000199
		paths shouldBe listOf(RELEASES_API, manifestUrl)
	}
	test("rate limit offline redirects and malformed reads never create success or expose raw responses") {
		for (status in listOf(403, 404, 429, 500, 302)) {
			var calls = 0
			val client = ForkUpdateClient(factory { request -> calls++; reply(request, "private-data".toByteArray(), status, "https://example.invalid/private") })
			expectUpdateFailure<UpdateFailure> { client.latest() }.userMessage.contains("private-data") shouldBe false
			calls shouldBe 1
		}
		expectUpdateFailure<IOException> { ForkUpdateClient(factory { throw IOException("private-details") }).latest() }
		updateError(IOException("private-details")).contains("private-details") shouldBe false
	}
	test("cancelling pending metadata cancels its HTTP call") {
		coroutineScope {
			val entered = CompletableDeferred<Unit>()
			var cancelled = false
			val client = ForkUpdateClient(Call.Factory {
				mockk<Call> { every { cancel() } answers { cancelled = true }; every { enqueue(any()) } answers { entered.complete(Unit); Unit } }
			})
			val pending = async { client.latest() }
			entered.await(); pending.cancel(); pending.join()
			cancelled shouldBe true
		}
	}
	test("download saves verified bytes atomically and removes partial files on failure") {
		val directory = Files.createTempDirectory("fork-update-test-").toFile()
		try {
			val client = ForkUpdateClient(factory { request -> reply(request, apkBytes) })
			val file = client.download(candidate(), directory) {}
			verifyDownloadedUpdate(file, candidate().asset) shouldBe true
			directory.listFiles()!!.map { it.extension } shouldBe listOf("apk")
			file.writeText("corrupted")
			verifyDownloadedUpdate(file, candidate().asset) shouldBe false
			verifyDownloadedUpdate(client.download(candidate(), directory) {}, candidate().asset) shouldBe true
			val bad = ForkUpdateClient(factory { request -> reply(request, apkBytes.dropLast(1).toByteArray()) })
			expectUpdateFailure<UpdateFailure> { bad.download(candidate(), directory) {} }
			directory.listFiles()!!.any { it.extension == "part" } shouldBe false
		} finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
	}
	test("cached notices appear only on visible Home with automatic checks enabled for an unseen compatible update") {
        fun allowed(enabled: Boolean = true, home: Boolean = true, code: Int = 1000199, installed: Int = 1000099,
            shown: Int = 0, minSdk: Int = 21, sdk: Int = 30) = updateNoticeEligible(enabled, home, code, installed, shown, minSdk, sdk)
        allowed() shouldBe true
        allowed(enabled = false) shouldBe false
        allowed(home = false) shouldBe false
        allowed(code = 1000099) shouldBe false
        allowed(shown = 1000199) shouldBe false
        allowed(minSdk = 31) shouldBe false
        allowed(installed = 1000299) shouldBe false
    }
})
