package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.CancellationException
import java.io.IOException

internal enum class SeerrSubmissionStep(val label: String) {
	SESSION("Checking Seerr sign-in"), TITLE("Refreshing title status"), LIBRARY("Checking your Jellyfin library"),
	DESTINATION("Refreshing request destinations"), OPTIONS("Checking profile, folder and tags"),
	SEND("Sending request to Seerr"), CONFIRM("Confirming the saved request"),
}

internal class SeerrSubmissionFailure(val step: SeerrSubmissionStep, val failure: Exception) : IOException() {
	val needsStatusCheck get() = step == SeerrSubmissionStep.SEND || step == SeerrSubmissionStep.CONFIRM
	val userMessage get() = "${step.label}: " + when {
		failure is SeerrFailure -> failure.userMessage
		step == SeerrSubmissionStep.LIBRARY -> "The library check failed. No request was sent. Refresh and try again."
		needsStatusCheck -> "The result is unconfirmed. Check status before trying again; do not submit a duplicate."
		else -> "The title or selected options changed. No request was sent. Check status and review your selections."
	}
}

internal fun SeerrUiState.submissionFailed(error: SeerrSubmissionFailure) = copy(
	requestOpen = true, needsRefresh = true, message = error.userMessage,
)

/** Complete preflight plus one POST and one saved-record read; never replays a media request. */
internal suspend fun submitSeerrRequest(
	api: SeerrApi, library: SeerrLibrary, snapshot: SeerrUiState,
	onStep: (SeerrSubmissionStep) -> Unit = {},
): SeerrRequest {
	var step = SeerrSubmissionStep.SESSION
	var sentBody: SeerrRequestBody? = null
	fun stage(next: SeerrSubmissionStep) { step = next; onStep(next) }
	try {
		stage(SeerrSubmissionStep.SESSION)
		val user = api.me()
		stage(SeerrSubmissionStep.TITLE)
		val title = api.details(requireNotNull(snapshot.title).mediaType, snapshot.title.id)
		val selection = SeerrRequestBody(title.mediaType, title.id, snapshot.seasons.sorted().takeIf { title.mediaType == "tv" })
		title.existingRequest(selection, user.id)?.let { return it }
		stage(SeerrSubmissionStep.LIBRARY)
		val local = library.find(title).getOrElse { throw IOException() }
		require(title.mediaType != "movie" || local.isEmpty())
		stage(SeerrSubmissionStep.DESTINATION)
		val services = normalSeerrServices(api.services(title.mediaType))
		require(services.isNotEmpty())
		stage(SeerrSubmissionStep.OPTIONS)
		val service = snapshot.service?.let { selected ->
			require(services.any { it.id == selected.server.id })
			api.service(title.mediaType, selected.server.id)
		}
		if (service == null) require(defaultSeerrService(services) != null)
		val body = prepareNormalSeerrRequest(user, title, snapshot.seasons, service, snapshot.profileId, snapshot.rootFolder, snapshot.tags)
		stage(SeerrSubmissionStep.SEND)
		sentBody = body
		val receipt = try { api.submit(body) }
		catch (error: CancellationException) { throw error }
		catch (error: SeerrFailure) {
			if (error.submissionUncertain || error.status in listOf(202, 409)) {
				try { api.details(title.mediaType, title.id).existingRequest(body, user.id, includeCompleted = true)?.let { return it } }
				catch (cancelled: CancellationException) { throw cancelled }
				catch (_: Exception) { }
			}
			throw error
		}
		stage(SeerrSubmissionStep.CONFIRM)
		if (!receipt.matchesSavedRequest(body)) throw SeerrFailure(submissionUncertain = true, requestId = receipt.id.takeIf { it > 0 })
		return api.confirmRequest(body, receipt.id)
	} catch (error: CancellationException) { throw error }
	catch (error: Exception) {
		val body = sentBody
		val id = (error as? SeerrFailure)?.requestId
		if (step == SeerrSubmissionStep.CONFIRM && body != null && id != null) {
			try { api.requestRecord(id).takeIf { it.matchesSavedRequest(body) }?.let { return it.copy(confirmationRecovered = true) } }
			catch (cancelled: CancellationException) { throw cancelled }
			catch (_: Exception) { }
		}
		throw SeerrSubmissionFailure(step, error)
	}
}
