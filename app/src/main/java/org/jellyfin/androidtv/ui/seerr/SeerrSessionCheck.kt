package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface SeerrSessionCheck {
	data class Connected(val user: SeerrUser) : SeerrSessionCheck
	data object SignInRequired : SeerrSessionCheck
	data class Unavailable(val message: String) : SeerrSessionCheck
}

internal suspend fun checkSavedSeerrSession(api: SeerrApi): SeerrSessionCheck = try {
	if (!withContext(Dispatchers.IO) { api.restoreSession() }) SeerrSessionCheck.SignInRequired
	else SeerrSessionCheck.Connected(api.meForNavigation())
} catch (error: CancellationException) { throw error }
catch (error: SeerrFailure) {
	if (error.status in listOf(401, 403)) SeerrSessionCheck.SignInRequired
	else SeerrSessionCheck.Unavailable(error.userMessage)
}

internal fun SeerrUiState.checkedSession(result: SeerrSessionCheck): SeerrUiState = when (result) {
	is SeerrSessionCheck.Connected -> copy(user = result.user, sessionCheckFailed = false, message = null)
	SeerrSessionCheck.SignInRequired -> copy(user = null, sessionCheckFailed = false)
	is SeerrSessionCheck.Unavailable -> copy(sessionCheckFailed = true, message = result.message)
}
