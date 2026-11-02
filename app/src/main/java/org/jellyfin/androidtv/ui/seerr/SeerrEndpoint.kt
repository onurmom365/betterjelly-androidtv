package org.jellyfin.androidtv.ui.seerr

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val SEERR_REMOTE_ORIGIN = "https://seerrplexrequest.synology.me"

data class SeerrEndpoint private constructor(val url: HttpUrl) {
	// Keep the legacy LAN scope byte-for-byte identical so existing encrypted sessions survive.
	val origin: String get() = url.toString().removeSuffix("/")

	companion object {
		fun parse(value: String): SeerrEndpoint {
			val url = value.trim().toHttpUrlOrNull()
				?: throw IllegalArgumentException("Enter a complete http:// or https:// Seerr address.")
			require(url.username.isEmpty() && url.password.isEmpty()) { "Enter the server address without a username or password." }
			require(url.encodedPath == "/" && url.encodedQuery == null && url.encodedFragment == null) {
				"Use the Seerr server address only, without a page path, query or fragment."
			}
			require(url.scheme == "https" || localHost(url.host)) { "Use HTTPS for a remote Seerr server." }
			return SeerrEndpoint(url)
		}

		private fun localHost(host: String): Boolean {
			if (host == "localhost" || host == "::1") return true
			val labels = host.split('.')
			if (labels.size != 4) return false
			val parts = labels.map { it.toIntOrNull() ?: return false }
			return parts.all { it in 0..255 } &&
				(parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
		}
	}
}

internal val defaultSeerrEndpoint get() = SeerrEndpoint.parse(SEERR_ORIGIN)
