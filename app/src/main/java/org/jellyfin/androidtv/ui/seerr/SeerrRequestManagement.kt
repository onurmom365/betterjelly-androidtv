package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.CancellationException
import java.io.IOException

internal sealed interface SeerrManagedResult {
	data class Retried(val request: SeerrRequest) : SeerrManagedResult
	data object Deleted : SeerrManagedResult
}

internal class SeerrManagementFailure(val uncertain: Boolean, val userMessage: String) : IOException()

private fun SeerrRequest.sameRecord(expected: SeerrRequest) = id == expected.id && type == expected.type && is4k == expected.is4k &&
	(expected.media == null || media?.tmdbId == expected.media.tmdbId)

/** Uses the existing request ID; never creates a replacement request or replays a mutation. */
internal suspend fun manageSeerrRequest(api: SeerrApi, prompt: SeerrRequestPrompt): SeerrManagedResult {
	val expected = prompt.request
	var sent = false
	try {
		val user = api.me()
		val current = api.requestRecord(expected.id)
		require(current.sameRecord(expected))
		when (prompt.action) {
			SeerrRequestAction.RETRY -> {
				require(user.canRetry(current))
				sent = true
				val receipt = api.retryRequest(current.id)
				require(receipt.sameRecord(current) && receipt.status in 1..5)
				val confirmed = api.requestRecord(current.id)
				require(confirmed.sameRecord(current) && confirmed.status in 1..5)
				return SeerrManagedResult.Retried(confirmed)
			}
			SeerrRequestAction.DELETE -> {
				require(user.canDelete(current))
				sent = true
				api.deleteRequest(current.id)
				try { api.requestRecord(current.id) }
				catch (error: SeerrFailure) { if (error.status == 404) return SeerrManagedResult.Deleted else throw error }
				throw IOException()
			}
		}
	} catch (error: CancellationException) { throw error }
	catch (error: Exception) {
		if (sent) {
			// A response may be lost after Seerr saved the change. Reconcile with a read, never another mutation.
			try {
				val record = api.requestRecord(expected.id)
				if (prompt.action == SeerrRequestAction.RETRY && record.sameRecord(expected) && record.status in listOf(1, 2, 3, 5))
					return SeerrManagedResult.Retried(record)
			} catch (cancelled: CancellationException) { throw cancelled }
			catch (readError: SeerrFailure) {
				if (prompt.action == SeerrRequestAction.DELETE && readError.status == 404) return SeerrManagedResult.Deleted
			} catch (_: Exception) { }
		}
		val uncertain = sent && (error !is SeerrFailure || error.status !in listOf(400, 401, 403, 404, 409, 202))
		throw SeerrManagementFailure(uncertain, if (uncertain)
			"The ${if (prompt.action == SeerrRequestAction.RETRY) "retry" else "deletion"} result for request #${expected.id} could not be confirmed. Use Check status before repeating it."
		else (error as? SeerrFailure)?.userMessage ?: "This request's status or your permissions changed. Refresh its status before trying again.")
	}
}
