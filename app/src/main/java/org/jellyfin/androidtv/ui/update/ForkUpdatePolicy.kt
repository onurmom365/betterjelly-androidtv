package org.jellyfin.androidtv.ui.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import java.io.IOException
import java.security.MessageDigest

internal const val UPDATE_REPOSITORY = "onurmom365/betterjelly-androidtv"
internal const val UPDATE_PACKAGE = "org.jellyfin.androidtv.debug"
internal const val UPDATE_CERTIFICATE = "6644d28e5c9249280a7552f404c675bde1316508eef2438fa2217b10121d8272"
internal const val MAX_APK_BYTES = 150L * 1024 * 1024
internal const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
internal const val RELEASES_API = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"

internal class UpdateFailure(val userMessage: String) : IOException(userMessage)
internal fun updateRequire(value: Boolean, message: String = "Release information could not be verified. Please try again later.") {
	if (!value) throw UpdateFailure(message)
}
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal data class UpdateAsset(val name: String, val url: String, val size: Long, val sha256: String)
internal data class UpdateListing(val tag: String, val notes: String, val manifest: UpdateAsset, val apk: UpdateAsset)
internal data class ForkRelease(val version: String, val code: Int, val minSdk: Int, val asset: UpdateAsset, val notes: String)
internal data class UpdateArchive(val packageName: String, val version: String?, val code: Long, val certificates: List<String>,
	val debuggable: Boolean, val minSdk: Int?)

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull ?: throw UpdateFailure("Release information is incomplete.")
private fun digest(value: String): String {
	updateRequire(Regex("[a-f0-9]{64}").matches(value))
	return value
}

internal fun releaseAssetUrl(value: String, tag: String, name: String): Boolean =
	value == "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/$name"

/** API/manifest URLs are exact; only GitHub's asset CDN may receive a redirected APK GET. */
internal fun allowedUpdateUrl(url: HttpUrl, initial: String, asset: Boolean): Boolean {
	val initialAsset = Regex("https://github\\.com/$UPDATE_REPOSITORY/releases/download/v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}/(release\\.json|betterjelly-androidtv\\.apk)").matches(initial)
	if ((!asset && initial != RELEASES_API) || (asset && !initialAsset)) return false
	if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
	if (url.toString() == initial) return true
	return asset && url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
}

internal fun parseUpdateListing(text: String): UpdateListing {
	try {
		val root = Json.parseToJsonElement(text).jsonObject
		updateRequire(root["draft"]?.jsonPrimitive?.booleanOrNull == false && root["prerelease"]?.jsonPrimitive?.booleanOrNull == false)
		val tag = root.string("tag_name")
		updateRequire(Regex("v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}").matches(tag))
		val assets = root["assets"]?.jsonArray ?: throw UpdateFailure("This release has no downloadable files.")
		fun asset(name: String, max: Long): UpdateAsset {
			val matches = assets.map { it.jsonObject }.filter { it.string("name") == name }
			updateRequire(matches.size == 1)
			val entry = matches.single()
			val url = entry.string("browser_download_url")
			val size = entry["size"]?.jsonPrimitive?.longOrNull ?: 0
			updateRequire(entry.string("state") == "uploaded" && size in 1..max && releaseAssetUrl(url, tag, name))
			val hash = entry.string("digest")
			updateRequire(hash.startsWith("sha256:"))
			return UpdateAsset(name, url, size, digest(hash.removePrefix("sha256:")))
		}
		return UpdateListing(tag, root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(12000),
			asset("release.json", 32 * 1024), asset("betterjelly-androidtv.apk", MAX_APK_BYTES))
	} catch (error: UpdateFailure) { throw error }
	catch (_: Exception) { throw UpdateFailure("Release information could not be read. Please try again later.") }
}

internal fun parseUpdateManifest(bytes: ByteArray, listing: UpdateListing): ForkRelease {
	updateRequire(bytes.size.toLong() == listing.manifest.size && sha256(bytes) == listing.manifest.sha256)
	try {
		val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
		val version = root.string("versionName")
		val code = root["versionCode"]?.jsonPrimitive?.intOrNull ?: 0
		val minSdk = root["minSdk"]?.jsonPrimitive?.intOrNull ?: 0
		updateRequire(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1 && root.string("applicationId") == UPDATE_PACKAGE)
		updateRequire(root.string("signingCertificateSha256") == UPDATE_CERTIFICATE && root["debuggable"]?.jsonPrimitive?.booleanOrNull == false)
		updateRequire(listing.tag == "v$version" && root.string("sourceTag") == listing.tag && minSdk >= 21 && code > 0)
		updateRequire(root.string("apkUrl") == listing.apk.url && digest(root.string("sha256")) == listing.apk.sha256)
		val parts = version.split('.').map(String::toInt)
		updateRequire(code == parts[0] * 1000000 + parts[1] * 10000 + parts[2] * 100 + 99)
		return ForkRelease(version, code, minSdk, listing.apk, listing.notes)
	} catch (error: UpdateFailure) { throw error }
	catch (_: Exception) { throw UpdateFailure("Release information could not be verified. Please try again later.") }
}

internal fun verifyUpdateArchive(release: ForkRelease, archive: UpdateArchive, installed: UpdateArchive, sdk: Int) {
	updateRequire(installed.packageName == UPDATE_PACKAGE && archive.packageName == UPDATE_PACKAGE,
		"This update is for a different app. Nothing was installed.")
	updateRequire(installed.certificates == listOf(UPDATE_CERTIFICATE) && archive.certificates == installed.certificates,
		"The update's signing identity does not match this app. Nothing was installed.")
	updateRequire(archive.code == release.code.toLong() && archive.version == release.version && archive.code > installed.code,
		"This file is not a newer version of this app. Check for updates again.")
	updateRequire(!archive.debuggable, "This is not a release APK. Nothing was installed.")
	updateRequire(release.minSdk <= sdk && (archive.minSdk == null || archive.minSdk <= sdk),
		"This update requires a newer Android version.")
}

internal fun updateNoticeEligible(enabled: Boolean, homeVisible: Boolean, code: Int, installed: Int, shown: Int, minSdk: Int, sdk: Int) =
	enabled && homeVisible && code > installed && code != shown && minSdk in 21..sdk
