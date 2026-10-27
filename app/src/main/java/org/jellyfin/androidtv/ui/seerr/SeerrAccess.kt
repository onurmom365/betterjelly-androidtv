package org.jellyfin.androidtv.ui.seerr

import android.content.Context
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import java.lang.ref.WeakReference

fun seerrAccountScope(session: Session?) = session?.let { "${it.serverId}/${it.userId}/$SEERR_ORIGIN" }

private object SeerrAccountApi {
	private var currentScope: String? = null
	private var current = WeakReference<SeerrApi>(null)
	@Synchronized fun get(scope: String, create: () -> SeerrApi): SeerrApi {
		if (scope == currentScope) current.get()?.let { return it }
		return create().also { currentScope = scope; current = WeakReference(it) }
	}
}

fun seerrApiForAccount(context: Context, sessions: SessionRepository, scope: String) = SeerrAccountApi.get(scope) {
	SeerrApi(AndroidSeerrSessionStore(context.applicationContext, scope),
		accessAllowed = { seerrAccountScope(sessions.currentSession.value) == scope })
}

fun tmdbImage(path: String?, size: String = "w500"): String? = path?.takeIf {
	it.startsWith('/') && !it.startsWith("//") && !it.contains("..") && !it.contains('?') && !it.contains('#')
}?.let { "https://image.tmdb.org/t/p/$size$it" }
