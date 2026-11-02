package org.jellyfin.androidtv.util.profile

import org.jellyfin.androidtv.preference.UserPreferences

/** Keep the advertised profile and the real renderer gated together, including native ABI availability. */
object AssPlaybackSupport {
	private val nativeAvailable by lazy {
		try { Class.forName("io.github.peerless2012.ass.Ass"); true }
		catch (_: LinkageError) { false }
		catch (_: ReflectiveOperationException) { false }
	}
	@JvmStatic fun enabled(preferences: UserPreferences) = preferences[UserPreferences.assDirectPlay] && nativeAvailable
}
