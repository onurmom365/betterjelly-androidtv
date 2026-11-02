package org.jellyfin.androidtv.ui.seerr

import android.content.Context
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import java.lang.ref.WeakReference

fun seerrAccountIdentity(session: Session?) = session?.let { "${it.serverId}/${it.userId}" }
fun seerrAccountScope(session: Session?, endpoint: SeerrEndpoint = defaultSeerrEndpoint) =
	seerrAccountIdentity(session)?.let { "$it/${endpoint.origin}" }

private object SeerrAccountApi {
	private var currentScope: String? = null
	private var current = WeakReference<SeerrApi>(null)
	@Synchronized fun get(scope: String, create: () -> SeerrApi): SeerrApi {
		if (scope == currentScope) current.get()?.let { return it }
		return create().also { currentScope = scope; current = WeakReference(it) }
	}
}

fun seerrApiForAccount(context: Context, sessions: SessionRepository, scope: String, endpoint: SeerrEndpoint = defaultSeerrEndpoint) = SeerrAccountApi.get(scope) {
	val appContext = context.applicationContext
	SeerrApi(AndroidSeerrSessionStore(appContext, scope),
		accessAllowed = {
			val session = sessions.currentSession.value
			val current = seerrEndpoints(appContext).endpoint(seerrAccountIdentity(session))
			seerrAccountScope(session, current) == scope
		}, endpoint = endpoint)
}

fun tmdbImage(path: String?, size: String = "w500"): String? = path?.takeIf {
	it.startsWith('/') && !it.startsWith("//") && !it.contains("..") && !it.contains('?') && !it.contains('#')
}?.let { "https://image.tmdb.org/t/p/$size$it" }
