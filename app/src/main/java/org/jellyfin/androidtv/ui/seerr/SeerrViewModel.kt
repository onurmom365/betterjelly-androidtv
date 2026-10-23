package org.jellyfin.androidtv.ui.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class SeerrUiState(
	val user: SeerrUser? = null, val busy: Boolean = false, val submitting: Boolean = false,
	val message: String? = null, val pinCode: String? = null,
	val restoringSession: Boolean = false,
	val submissionStep: String? = null,
	val query: String = "", val type: String = "movie", val page: Int = 1, val totalPages: Int = 1,
	val results: List<SeerrTitle> = emptyList(), val title: SeerrTitle? = null,
	val libraryMatches: List<BaseItemDto> = emptyList(), val libraryChecked: Boolean = false, val exactLibraryMatch: Boolean = false,
	val is4k: Boolean = false, val seasons: Set<Int> = emptySet(),
	val services: List<SeerrService> = emptyList(), val service: SeerrServiceDetails? = null,
	val profileId: Int? = null, val rootFolder: String? = null, val tags: Set<Int>? = null,
	val optionsLoaded: Boolean = false, val requestOpen: Boolean = false, val reviewing: Boolean = false, val needsRefresh: Boolean = false,
	val historyMode: Boolean = false, val historyFilter: String = "all", val historyPage: Int = 1, val historyPages: Int = 1,
	val history: List<SeerrHistoryEntry> = emptyList(), val historyWarning: String? = null,
) {
	val effectiveTags get() = tags ?: service?.server?.activeTags.orEmpty().toSet()
	fun requestBody(): SeerrRequestBody? = runCatching {
		require(!is4k && optionsLoaded && libraryChecked && !needsRefresh && !(exactLibraryMatch && title?.mediaType == "movie"))
		val account = requireNotNull(user)
		if (account.canChooseService) require(service != null)
		prepareNormalSeerrRequest(account, requireNotNull(title), seasons, service, profileId, rootFolder, tags)
	}.getOrNull()
}

class SeerrViewModel(private val api: SeerrApi, private val library: SeerrLibrary) : ViewModel() {
	private val _state = MutableStateFlow(SeerrUiState(restoringSession = true))
	val state = _state.asStateFlow()
	private var action: Job? = null
	private var started = false
	private var initialType: String? = null
	private var initialId: Int? = null
	private val plexIdentifier = UUID.randomUUID().toString()

	private fun perform(block: suspend () -> Unit) {
		if (_state.value.busy) return
		_state.update { it.copy(busy = true, message = null) }
		action = viewModelScope.launch {
			try { block() }
			catch (error: CancellationException) { throw error }
			catch (error: Exception) { _state.update { it.copy(message = (error as? SeerrFailure)?.userMessage ?: "This action could not be completed. Please refresh and retry.") } }
			finally { if (coroutineContext.isActive) _state.update { it.copy(busy = false, pinCode = null) } }
		}
	}
	fun start(query: String, force: Boolean = false, type: String? = null, id: Int? = null, history: Boolean = false) {
		if (started && !force) return
		started = true
		if (type != null && id != null) { initialType = type; initialId = id }
		_state.update { it.copy(query = query, historyMode = history || it.historyMode, restoringSession = true) }
		perform {
			try {
				val user = api.me()
				_state.update { it.copy(user = user) }
				afterLogin()
			}
			catch (error: SeerrFailure) { if (error.status !in listOf(401, 403)) throw error }
			finally { _state.update { it.copy(restoringSession = false) } }
		}
	}
	private suspend fun afterLogin() {
		val type = initialType; val id = initialId
		if (type != null && id != null) openTitleNow(SeerrTitle(id, type))
		else if (_state.value.historyMode) historyNow(_state.value.historyFilter, 1)
		else browseNow(_state.value.query, "movie", 1)
	}
	fun login(email: String, password: String) = perform {
		val user = api.login(email.trim(), password)
		_state.update { it.copy(user = user) }
		afterLogin()
	}
	fun loginPlex() = perform {
		val pin = api.startPlex(plexIdentifier)
		_state.update { it.copy(pinCode = pin.code) }
		repeat(pin.expiresIn.coerceIn(10, 600) / 2) {
			delay(2_000)
			if (api.checkPlex(pin, plexIdentifier)) {
				val user = api.me()
				_state.update { it.copy(user = user, pinCode = null) }
				afterLogin(); return@perform
			}
		}
		_state.update { it.copy(message = "Plex code expired. Please try again.") }
	}
	fun cancelPlex() { if (_state.value.pinCode != null) leave() }
	fun leave() {
		if (_state.value.submitting) return
		if (_state.value.restoringSession) started = false
		action?.cancel()
		_state.update { it.copy(busy = false, pinCode = null, restoringSession = false) }
	}
	fun disconnect() {
		if (_state.value.busy) return
		api.disconnect()
		_state.value = SeerrUiState(historyMode = true)
	}
	fun browse(query: String, type: String, page: Int = 1) = perform { browseNow(query, type, page) }
	private suspend fun browseNow(query: String, type: String, page: Int) {
		val result = api.titles(query, type, page)
		_state.update { it.copy(query = query, type = type, page = page, totalPages = result.totalPages, results = result.results,
			title = null, historyMode = false, requestOpen = false, reviewing = false) }
	}
	fun openTitle(title: SeerrTitle) = perform { openTitleNow(title) }
	private suspend fun openTitleNow(title: SeerrTitle) {
		val details = api.details(title.mediaType, title.id)
		val matches = library.find(details)
		_state.update { it.copy(title = details, libraryMatches = matches.getOrNull().orEmpty(), libraryChecked = matches.isSuccess,
			exactLibraryMatch = matches.getOrNull().orEmpty().isNotEmpty(), is4k = false, seasons = emptySet(), services = emptyList(),
			service = null, profileId = null, rootFolder = null, tags = null, requestOpen = false, reviewing = false,
			optionsLoaded = false, needsRefresh = false, message = if (matches.isFailure) "Library check unavailable. Refresh before requesting." else null) }
	}
	fun back() {
		if (_state.value.submitting) return
		leave()
		_state.update { when {
			it.reviewing -> it.copy(reviewing = false)
			it.requestOpen -> it.copy(requestOpen = false)
			else -> it.copy(title = null, message = null)
		} }
	}
	fun beginRequest() = perform {
		val s = _state.value
		val title = requireNotNull(s.title)
		val user = requireNotNull(s.user)
		require(user.canRequest(title.mediaType, false))
		val services = normalSeerrServices(api.services(title.mediaType))
		if (services.isEmpty()) {
			_state.update { it.copy(message = "Seerr has no normal ${if (title.mediaType == "movie") "Radarr" else "Sonarr"} destination. Check its service settings before requesting.") }
			return@perform
		}
		val default = defaultSeerrService(services)
		if (!user.canChooseService && default == null) {
			_state.update { it.copy(message = "A default request destination must be configured in Seerr.") }
			return@perform
		}
		_state.update { it.copy(services = services, requestOpen = true, reviewing = false, optionsLoaded = !user.canChooseService) }
		if (user.canChooseService && default != null) selectServiceNow(default)
	}
	private suspend fun selectServiceNow(service: SeerrService) {
		val details = api.service(requireNotNull(_state.value.title).mediaType, service.id)
		require(!details.server.is4k)
		_state.update { it.copy(service = details, tags = null, optionsLoaded = true,
			profileId = details.server.activeProfileId?.takeIf { id -> details.profiles.any { it.id == id } },
			rootFolder = details.server.activeDirectory?.takeIf { path -> details.rootFolders.any { it.path == path } }) }
	}
	fun service(service: SeerrService) = perform {
		require(_state.value.services.any { it.id == service.id && !it.is4k })
		selectServiceNow(service)
	}
	fun season(number: Int) {
		if (!_state.value.busy) _state.update { it.copy(seasons = if (number in it.seasons) it.seasons - number else it.seasons + number) }
	}
	fun allSeasons() {
		if (!_state.value.busy) _state.update { it.copy(seasons = it.title?.normalRequestableSeasons().orEmpty().filter { it.seasonNumber > 0 }.map { it.seasonNumber }.toSet()) }
	}
	fun profile(id: Int) { if (!_state.value.busy) _state.update { it.copy(profileId = id) } }
	fun root(path: String) { if (!_state.value.busy) _state.update { it.copy(rootFolder = path) } }
	fun tag(id: Int) {
		if (!_state.value.busy) _state.update {
			val tags = it.effectiveTags
			it.copy(tags = if (id in tags) tags - id else tags + id)
		}
	}
	fun defaultTags() { if (!_state.value.busy) _state.update { it.copy(tags = null) } }
	fun review() { if (!_state.value.busy && _state.value.requestBody() != null) _state.update { it.copy(reviewing = true) } }
	fun history(filter: String = "all", page: Int = 1) = perform { historyNow(filter, page) }
	private suspend fun historyNow(filter: String, page: Int) {
		val result = api.history(requireNotNull(_state.value.user).id, filter, page)
		val limiter = Semaphore(3)
		val entries = coroutineScope { result.results.map { request -> async {
			val media = request.media
			val type = media?.mediaType?.ifBlank { request.type } ?: request.type
			val title = if (media != null && media.tmdbId > 0 && type in listOf("movie", "tv")) limiter.withPermit {
				try { api.details(type, media.tmdbId) }
				catch (error: CancellationException) { throw error }
				catch (_: Exception) { null }
			} else null
			SeerrHistoryEntry(request, title)
		} }.awaitAll() }
		_state.update { it.copy(historyMode = true, title = null, requestOpen = false, reviewing = false, history = entries,
			historyFilter = filter, historyPage = page, historyPages = result.pageInfo.pages,
			historyWarning = if (result.serviceErrors.radarr.isNotEmpty() || result.serviceErrors.sonarr.isNotEmpty())
				"Seerr reports a connection problem with a download manager. A saved request may not have reached it." else null) }
	}
	fun submit() {
		val snapshot = _state.value
		if (snapshot.busy || !snapshot.reviewing || snapshot.requestBody() == null) return
		perform {
			_state.update { it.copy(submitting = true) }
			try {
				val receipt = submitSeerrRequest(api, library, snapshot) { step -> _state.update { it.copy(submissionStep = step.label) } }
				_state.update { it.copy(requestOpen = false, reviewing = false, needsRefresh = true,
					title = snapshot.title?.copy(mediaInfo = receipt.media ?: snapshot.title.mediaInfo),
					message = "Confirmed request #${receipt.id} in Seerr · ${receipt.statusLabel}. Open Requests to follow its status.") }
			} catch (error: CancellationException) { throw error }
			catch (error: SeerrSubmissionFailure) { _state.update { it.submissionFailed(error) } }
			finally { _state.update { it.copy(submitting = false, submissionStep = null) } }
		}
	}
	fun checkRequestStatus() = perform {
		val snapshot = _state.value
		val oldTitle = requireNotNull(snapshot.title)
		val title = api.details(oldTitle.mediaType, oldTitle.id)
		val matches = library.find(title)
		val seasons = snapshot.seasons.intersect(title.normalRequestableSeasons().map { it.seasonNumber }.toSet())
		_state.update { it.copy(title = title, libraryMatches = matches.getOrNull().orEmpty(), libraryChecked = matches.isSuccess,
			exactLibraryMatch = matches.getOrNull().orEmpty().isNotEmpty(), seasons = seasons, needsRefresh = matches.isFailure,
			reviewing = false, message = if (matches.isFailure) "Library check failed. Request status could not be fully refreshed. Please try Check status again."
			else "Status refreshed · ${title.displayStatus()}. Seasons already requested are excluded. Review before requesting.") }
	}
}
