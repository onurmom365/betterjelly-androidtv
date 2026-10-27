package org.jellyfin.androidtv.ui.seerr

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

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
	val type: String = "",
	val profileName: String? = null,
	val createdAt: String = "",
	val requestedBy: SeerrRequestOwner? = null,
	@kotlinx.serialization.Transient val confirmationRecovered: Boolean = false,
) {
	fun matchesSavedRequest(body: SeerrRequestBody) = id > 0 && type == body.mediaType && is4k == body.is4k &&
		status in 1..5 && media?.tmdbId == body.mediaId && media.mediaType == body.mediaType &&
		(body.mediaType != "tv" || body.seasons.orEmpty().isNotEmpty() &&
			body.seasons.orEmpty().all { chosen -> seasons.any { it.seasonNumber == chosen && it.status in 1..5 } })
	val savedMessage get() = when (status) {
		4 -> "Request #$id is saved in Seerr, but Seerr reports a failure while processing it for ${if (type == "tv") "Sonarr" else "Radarr"}. Check Seerr's error before retrying."
		else -> "Confirmed request #$id in Seerr · $statusLabel. " +
			if (confirmationRecovered) "Found by checking Seerr; no repeat submission was made." else "Open Requests to follow its status."
	}
	val statusLabel get() = when (status) {
		1 -> "Pending approval"
		2 -> "Requested"
		3 -> "Declined"
		4 -> "Failed"
		5 -> "Completed"
		else -> "Unknown"
	}
}

@Serializable data class SeerrRequestOwner(val id: Int)

enum class SeerrRequestAction { RETRY, DELETE }
data class SeerrRequestPrompt(val action: SeerrRequestAction, val request: SeerrRequest, val title: String)

fun SeerrUser.canRetry(request: SeerrRequest) = request.id > 0 && request.status == 4 && permits(16)
fun SeerrUser.canDelete(request: SeerrRequest) = request.id > 0 &&
	(permits(16) || request.status == 1 && request.requestedBy?.id == id)

internal fun SeerrTitle.existingRequest(body: SeerrRequestBody, userId: Int, includeCompleted: Boolean = false): SeerrRequest? {
	val media = mediaInfo ?: return null
	if (id != body.mediaId || mediaType != body.mediaType || media.tmdbId != id || media.mediaType != mediaType) return null
	return media.requests.firstOrNull { request ->
		request.id > 0 && request.type == body.mediaType && request.is4k == body.is4k && request.requestedBy?.id == userId &&
		request.status in (if (includeCompleted) listOf(1, 2, 4, 5) else listOf(1, 2, 4)) &&
		(body.mediaType == "movie" || body.seasons.orEmpty().isNotEmpty() && body.seasons.orEmpty().all { selected ->
			request.seasons.any { it.seasonNumber == selected && it.status in 1..5 }
		})
	}?.copy(media = media, confirmationRecovered = true)
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
	val backdropPath: String? = null,
	val voteAverage: Double = 0.0,
	val runtime: Int? = null,
	val genres: List<SeerrGenre> = emptyList(),
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
		val existingRequest = mediaInfo?.requests.orEmpty().any { request ->
			request.is4k == is4k && request.status in listOf(1, 2, 4) && request.seasons.any { it.seasonNumber == season.seasonNumber }
		}
		!blocked && season.seasonNumber >= 0 && season.episodeCount > 0 && availability !in listOf(2, 3, 5) && !existingRequest
	}
	fun movieRequestable(is4k: Boolean) = !blocked && status(is4k) !in listOf(2, 3, 5) &&
		mediaInfo?.requests.orEmpty().none { it.is4k == is4k && it.status in listOf(1, 2, 4) }
	fun normalMovieRequestable() = movieRequestable(false) && status(true) !in listOf(2, 3, 5) &&
		mediaInfo?.requests.orEmpty().none { it.status in listOf(1, 2, 4) }
	fun normalRequestableSeasons(): List<SeerrSeason> {
		val otherLane = requestableSeasons(true).map { it.seasonNumber }.toSet()
		return requestableSeasons(false).filter { it.seasonNumber in otherLane }
	}
	fun displayStatus(): String = when {
		blocked -> "Blocked"
		status() == 5 || status(true) == 5 -> "Available"
		status() == 4 || status(true) == 4 -> "Partly available"
		mediaInfo?.requests.orEmpty().any { it.status == 1 } -> "Pending approval"
		mediaInfo?.requests.orEmpty().any { it.status == 2 } -> "Requested"
		mediaInfo?.requests.orEmpty().any { it.status == 4 } -> "Failed"
		status() == 3 || status(true) == 3 -> "Requested"
		status() == 2 || status(true) == 2 -> "Pending approval"
		else -> "Not requested"
	}
}

@Serializable data class SeerrGenre(val name: String = "")
@Serializable data class SeerrTag(val id: Int, val label: String)

@Serializable
data class SeerrPage(val results: List<SeerrTitle> = emptyList(), val totalPages: Int = 1)

@Serializable
data class SeerrService(
	val id: Int, val name: String = "", val is4k: Boolean = false, val isDefault: Boolean = false,
	val activeProfileId: Int? = null, val activeDirectory: String? = null,
	val activeTags: List<Int> = emptyList(),
)
@Serializable
data class SeerrProfile(val id: Int, val name: String = "")
@Serializable
data class SeerrRootFolder(val path: String, val freeSpace: Long = 0)
@Serializable
data class SeerrServiceDetails(
	val server: SeerrService,
	val profiles: List<SeerrProfile> = emptyList(),
	val rootFolders: List<SeerrRootFolder> = emptyList(),
	val tags: List<SeerrTag> = emptyList(),
)

fun normalSeerrServices(services: List<SeerrService>) = services.filterNot { it.is4k }
fun defaultSeerrService(services: List<SeerrService>): SeerrService? {
	val normal = normalSeerrServices(services)
	return normal.singleOrNull { it.isDefault } ?: normal.singleOrNull()
}

@Serializable data class SeerrRequestPageInfo(val pages: Int = 1)
@Serializable data class SeerrServiceErrors(val radarr: List<SeerrProfile> = emptyList(), val sonarr: List<SeerrProfile> = emptyList())
@Serializable data class SeerrRequestPage(
	val results: List<SeerrRequest> = emptyList(),
	val pageInfo: SeerrRequestPageInfo = SeerrRequestPageInfo(),
	val serviceErrors: SeerrServiceErrors = SeerrServiceErrors(),
)
data class SeerrHistoryEntry(val request: SeerrRequest, val title: SeerrTitle?)

// Tags are existing Seerr-provided IDs only. No userId, approval or quota overrides.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class SeerrRequestBody(
	val mediaType: String,
	val mediaId: Int,
	val seasons: List<Int>? = null,
	@EncodeDefault val is4k: Boolean = false,
	val serverId: Int? = null,
	val profileId: Int? = null,
	val rootFolder: String? = null,
	val tags: List<Int>? = null,
)

fun prepareSeerrRequest(
	user: SeerrUser, title: SeerrTitle, is4k: Boolean, seasons: Set<Int>,
	service: SeerrServiceDetails? = null, profileId: Int? = null, rootFolder: String? = null,
	tags: Set<Int>? = null,
): SeerrRequestBody {
	require(title.id > 0 && title.mediaType in listOf("movie", "tv"))
	require(user.canRequest(title.mediaType, is4k))
	if (title.mediaType == "movie") require(title.movieRequestable(is4k))
	else require(seasons.isNotEmpty() && seasons.all { chosen -> title.requestableSeasons(is4k).any { it.seasonNumber == chosen } })
	if (service != null) {
		require(user.canChooseService && service.server.is4k == is4k)
		require(profileId != null && service.profiles.any { it.id == profileId })
		require(rootFolder != null && service.rootFolders.any { it.path == rootFolder })
		if (tags != null) require(tags.all { id -> service.tags.any { it.id == id } })
	} else require(profileId == null && rootFolder == null && tags == null)
	return SeerrRequestBody(title.mediaType, title.id, seasons.sorted().takeIf { title.mediaType == "tv" }, is4k,
		service?.server?.id, profileId, rootFolder, tags?.sorted())
}

fun prepareNormalSeerrRequest(
	user: SeerrUser, title: SeerrTitle, seasons: Set<Int>, service: SeerrServiceDetails? = null,
	profileId: Int? = null, rootFolder: String? = null, tags: Set<Int>? = null,
): SeerrRequestBody {
	if (title.mediaType == "movie") require(title.normalMovieRequestable())
	else require(seasons.all { selected -> title.normalRequestableSeasons().any { it.seasonNumber == selected } })
	return prepareSeerrRequest(user, title, false, seasons, service, profileId, rootFolder, tags)
}
