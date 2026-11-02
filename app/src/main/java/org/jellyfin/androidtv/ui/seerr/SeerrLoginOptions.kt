package org.jellyfin.androidtv.ui.seerr

import kotlinx.serialization.Serializable

/** Public server capabilities; never infer an authentication provider from the client platform. */
@Serializable
data class SeerrLoginOptions(
	val initialized: Boolean = false,
	val mediaServerType: Int = 0,
	val mediaServerLogin: Boolean = false,
	val jellyfinConfigured: Boolean = false,
	val localLogin: Boolean = false,
) {
	val jellyfin get() = initialized && mediaServerLogin && mediaServerType == 2 && jellyfinConfigured
	val plex get() = initialized && mediaServerLogin && mediaServerType == 1
	val local get() = initialized && localLogin
}
