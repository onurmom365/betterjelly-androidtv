package org.jellyfin.androidtv.ui.seerr

import android.content.Context
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository

fun seerrAccountScope(session: Session?) = session?.let { "${it.serverId}/${it.userId}/$SEERR_ORIGIN" }

fun seerrApiForAccount(context: Context, sessions: SessionRepository, scope: String) = SeerrApi(
	AndroidSeerrSessionStore(context.applicationContext, scope),
	accessAllowed = { seerrAccountScope(sessions.currentSession.value) == scope },
)

fun tmdbImage(path: String?, size: String = "w500"): String? = path?.takeIf {
	it.startsWith('/') && !it.startsWith("//") && !it.contains("..") && !it.contains('?') && !it.contains('#')
}?.let { "https://image.tmdb.org/t/p/$size$it" }
