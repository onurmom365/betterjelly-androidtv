package org.jellyfin.androidtv.ui.seerr

import kotlinx.serialization.Serializable

@Serializable
data class SeerrUser(val id: Int, val displayName: String = "", val permissions: Long = 0) {
	fun permits(vararg flags: Long) = permissions and 2L != 0L || flags.any { permissions and it != 0L }
	fun canRequest(type: String, is4k: Boolean) = if (is4k) permits(1024, if (type == "movie") 2048 else 4096)
		else permits(32, if (type == "movie") 262144 else 524288)
	val canChooseService get() = permits(16, 8192)
}

@Serializable
data class SeerrSeason(val seasonNumber: Int, val name: String = "", val episodeCount: Int = 0)

@Serializable
data class SeerrSeasonStatus(val seasonNumber: Int, val status: Int = 0, val status4k: Int = 0)

@Serializable
data class SeerrSeasonRequest(val seasonNumber: Int, val status: Int = 0)

@Serializable
data class SeerrRequest(
	val id: Int,
	val status: Int = 0,
	val is4k: Boolean = false,
	val seasons: List<SeerrSeasonRequest> = emptyList(),
	val media: SeerrMediaInfo? = null,
) {
	val statusLabel get() = when (status) {
		1 -> "Pending approval"
		2 -> "Approved"
		3 -> "Declined"
		4 -> "Failed"
		5 -> "Completed"
		else -> "Unknown"
	}
}

@Serializable
data class SeerrMediaInfo(
	val tmdbId: Int = 0,
	val mediaType: String = "",
	val status: Int = 0,
	val status4k: Int = 0,
	val seasons: List<SeerrSeasonStatus> = emptyList(),
	val requests: List<SeerrRequest> = emptyList(),
)

@Serializable
data class SeerrTitle(
	val id: Int,
	val mediaType: String = "",
	val title: String = "",
	val name: String = "",
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val overview: String = "",
	val posterPath: String? = null,
	val seasons: List<SeerrSeason> = emptyList(),
	val mediaInfo: SeerrMediaInfo? = null,
) {
	val displayTitle get() = title.ifBlank { name }
	val year get() = (releaseDate ?: firstAirDate).orEmpty().take(4)
	private val blocked get() = mediaInfo?.status == 6 || mediaInfo?.status4k == 6
	fun status(is4k: Boolean = false) = if (is4k) mediaInfo?.status4k ?: 0 else mediaInfo?.status ?: 0
	fun statusLabel(is4k: Boolean = false) = when (status(is4k)) {
		2 -> "Pending approval"
		3 -> "Processing"
		4 -> "Partly available"
		5 -> "Available"
		6 -> "Blocked in Seerr"
		7 -> "Previously deleted"
		else -> "Not requested"
	}

	fun requestableSeasons(is4k: Boolean): List<SeerrSeason> = seasons.filter { season ->
		val state = mediaInfo?.seasons?.find { it.seasonNumber == season.seasonNumber }
		val availability = if (is4k) state?.status4k else state?.status
		val pending = mediaInfo?.requests.orEmpty().any { request ->
			request.is4k == is4k && request.status in listOf(1, 2) && request.seasons.any { it.seasonNumber == season.seasonNumber }
		}
		!blocked && season.seasonNumber >= 0 && season.episodeCount > 0 && availability !in listOf(2, 3, 5) && !pending
	}
	fun movieRequestable(is4k: Boolean) = !blocked && status(is4k) !in listOf(2, 3, 5) &&
		mediaInfo?.requests.orEmpty().none { it.is4k == is4k && it.status in listOf(1, 2) }
}

@Serializable
data class SeerrPage(val results: List<SeerrTitle> = emptyList(), val totalPages: Int = 1)

@Serializable
data class SeerrService(
	val id: Int, val name: String = "", val is4k: Boolean = false, val isDefault: Boolean = false,
	val activeProfileId: Int? = null, val activeDirectory: String? = null,
)
@Serializable
data class SeerrProfile(val id: Int, val name: String = "")
@Serializable
data class SeerrRootFolder(val path: String)
@Serializable
data class SeerrServiceDetails(
	val server: SeerrService,
	val profiles: List<SeerrProfile> = emptyList(),
	val rootFolders: List<SeerrRootFolder> = emptyList(),
)

// Intentionally no tags, userId, approval or quota overrides in the native request payload.
@Serializable
data class SeerrRequestBody(
	val mediaType: String,
	val mediaId: Int,
	val seasons: List<Int>? = null,
	val is4k: Boolean = false,
	val serverId: Int? = null,
	val profileId: Int? = null,
	val rootFolder: String? = null,
)

fun prepareSeerrRequest(
	user: SeerrUser, title: SeerrTitle, is4k: Boolean, seasons: Set<Int>,
	service: SeerrServiceDetails? = null, profileId: Int? = null, rootFolder: String? = null,
): SeerrRequestBody {
	require(title.id > 0 && title.mediaType in listOf("movie", "tv"))
	require(user.canRequest(title.mediaType, is4k))
	if (title.mediaType == "movie") require(title.movieRequestable(is4k))
	else require(seasons.isNotEmpty() && seasons.all { chosen -> title.requestableSeasons(is4k).any { it.seasonNumber == chosen } })
	if (service != null) {
		require(user.canChooseService && service.server.is4k == is4k)
		require(profileId != null && service.profiles.any { it.id == profileId })
		require(rootFolder != null && service.rootFolders.any { it.path == rootFolder })
	} else require(profileId == null && rootFolder == null)
	return SeerrRequestBody(title.mediaType, title.id, seasons.sorted().takeIf { title.mediaType == "tv" }, is4k,
		service?.server?.id, profileId, rootFolder)
}
