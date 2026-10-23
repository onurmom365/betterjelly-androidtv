package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest

fun interface SeerrLibrary {
	suspend fun find(title: SeerrTitle): Result<List<BaseItemDto>>
}

class JellyfinSeerrLibrary(private val api: ApiClient) : SeerrLibrary {
	override suspend fun find(title: SeerrTitle): Result<List<BaseItemDto>> = try {
		val result = api.itemsApi.getItems(GetItemsRequest(
			includeItemTypes = setOf(if (title.mediaType == "movie") BaseItemKind.MOVIE else BaseItemKind.SERIES),
			searchTerm = title.displayTitle,
			fields = setOf(ItemFields.PROVIDER_IDS), recursive = true, limit = 100,
		)).content.items
		// This pinned SDK has no provider-ID query filter. Verify returned IDs locally; never treat
		// a same-name remake as a match. Native library search remains the first entry point.
		Result.success(result.filter { item -> item.providerIds?.entries?.any {
			it.key.equals("Tmdb", ignoreCase = true) && it.value == title.id.toString()
		} == true })
	} catch (error: CancellationException) { throw error }
	catch (_: Exception) { Result.failure(SeerrFailure()) }
}
