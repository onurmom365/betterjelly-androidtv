package org.jellyfin.androidtv.ui.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class SeerrUiState(
	val user: SeerrUser? = null,
	val busy: Boolean = false,
	val submitting: Boolean = false,
	val message: String? = null,
	val pinCode: String? = null,
	val query: String = "",
	val type: String = "movie",
	val page: Int = 1,
	val totalPages: Int = 1,
	val results: List<SeerrTitle> = emptyList(),
	val title: SeerrTitle? = null,
	val libraryMatches: List<BaseItemDto> = emptyList(),
	val libraryChecked: Boolean = false,
	val exactLibraryMatch: Boolean = false,
	val is4k: Boolean = false,
	val seasons: Set<Int> = emptySet(),
	val services: List<SeerrService>? = null,
	val service: SeerrServiceDetails? = null,
	val profileId: Int? = null,
	val rootFolder: String? = null,
	val reviewing: Boolean = false,
	val needsRefresh: Boolean = false,
) {
	fun requestBody(): SeerrRequestBody? = runCatching {
		require(libraryChecked && !needsRefresh && !(exactLibraryMatch && title?.mediaType == "movie"))
		prepareSeerrRequest(requireNotNull(user), requireNotNull(title), is4k, seasons, service, profileId, rootFolder)
	}.getOrNull()
}

class SeerrViewModel(private val api: SeerrApi, private val library: SeerrLibrary) : ViewModel() {
	private val _state = MutableStateFlow(SeerrUiState())
	val state = _state.asStateFlow()
	private var action: Job? = null
	private var started = false
	private val plexIdentifier = UUID.randomUUID().toString()

	private fun perform(block: suspend () -> Unit) {
		if (_state.value.busy) return
		_state.update { it.copy(busy = true, message = null) }
		action = viewModelScope.launch {
			try { block() }
			catch (error: CancellationException) { throw error }
			catch (error: Exception) { _state.update { it.copy(message = (error as? SeerrFailure)?.userMessage ?: "This action could not be completed. Please retry.") } }
			finally { if (coroutineContext.isActive) _state.update { it.copy(busy = false, pinCode = null) } }
		}
	}

	fun start(query: String, force: Boolean = false) {
		if (started && !force) return
		started = true
		perform {
		_state.update { it.copy(query = query) }
		try {
			val user = api.me()
			_state.update { it.copy(user = user) }
			browseNow(query, "movie", 1)
		} catch (error: SeerrFailure) {
			if (error.status !in listOf(401, 403)) throw error
		}
		}
	}

	fun login(email: String, password: String) = perform {
		val user = api.login(email.trim(), password)
		_state.update { it.copy(user = user) }
		browseNow(_state.value.query, _state.value.type, 1)
	}

	fun loginPlex() = perform {
		val pin = api.startPlex(plexIdentifier)
		_state.update { it.copy(pinCode = pin.code) }
		repeat((pin.expiresIn.coerceIn(10, 600) / 2)) {
			delay(2_000)
			if (api.checkPlex(pin, plexIdentifier)) {
				val user = api.me()
				_state.update { it.copy(user = user, pinCode = null) }
				browseNow(_state.value.query, _state.value.type, 1)
				return@perform
			}
		}
		_state.update { it.copy(message = "Plex code expired. Choose Sign in with Plex to try again.") }
	}

	fun cancelPlex() {
		if (_state.value.pinCode == null) return
		action?.cancel()
		_state.update { it.copy(busy = false, pinCode = null) }
	}
	fun leave() {
		if (_state.value.submitting) return
		action?.cancel()
		_state.update { it.copy(busy = false, pinCode = null) }
	}
	fun disconnect() {
		if (_state.value.busy) return
		api.disconnect() // Forget this app's session only; no server logout/device deletion.
		_state.value = SeerrUiState()
	}
	fun browse(query: String, type: String, page: Int = 1) = perform { browseNow(query, type, page) }
	private suspend fun browseNow(query: String, type: String, page: Int) {
		val result = api.titles(query, type, page)
		_state.update { it.copy(query = query, type = type, page = page, totalPages = result.totalPages, results = result.results, title = null, reviewing = false) }
	}

	fun openTitle(title: SeerrTitle) = perform { openTitleNow(title) }
	private suspend fun openTitleNow(title: SeerrTitle) {
		val details = api.details(title.mediaType, title.id)
		val matches = library.find(details)
		val exact = matches.getOrNull().orEmpty().isNotEmpty()
		_state.update {
			it.copy(title = details, libraryMatches = matches.getOrNull().orEmpty(), libraryChecked = matches.isSuccess,
				exactLibraryMatch = exact, is4k = false, seasons = emptySet(), services = null, service = null,
				profileId = null, rootFolder = null, reviewing = false, needsRefresh = false,
				message = if (matches.isFailure) "Library check unavailable. Refresh this title before requesting." else null)
		}
	}
	fun back() {
		if (_state.value.submitting) return
		leave()
		_state.update { if (it.reviewing) it.copy(reviewing = false) else it.copy(title = null, message = null) }
	}
	fun quality(is4k: Boolean) {
		if (_state.value.busy) return
		_state.update { it.copy(is4k = is4k, seasons = emptySet(), services = null, service = null, profileId = null, rootFolder = null, reviewing = false) }
	}
	fun season(number: Int) {
		if (_state.value.busy) return
		_state.update { it.copy(seasons = if (number in it.seasons) it.seasons - number else it.seasons + number) }
	}
	fun advanced() = perform {
		val s = _state.value
		if (s.user?.canChooseService != true) return@perform
		val list = api.services(requireNotNull(s.title).mediaType).filter { it.is4k == s.is4k }
		_state.update { it.copy(services = list) }
	}
	fun service(service: SeerrService?) {
		if (_state.value.busy) return
		if (service == null) { _state.update { it.copy(service = null, profileId = null, rootFolder = null) }; return }
		perform {
			val details = api.service(requireNotNull(_state.value.title).mediaType, service.id)
			_state.update { it.copy(service = details,
				profileId = details.server.activeProfileId?.takeIf { id -> details.profiles.any { it.id == id } },
				rootFolder = details.server.activeDirectory?.takeIf { path -> details.rootFolders.any { it.path == path } }) }
		}
	}
	fun profile(id: Int) { if (!_state.value.busy) _state.update { it.copy(profileId = id) } }
	fun root(path: String) { if (!_state.value.busy) _state.update { it.copy(rootFolder = path) } }
	fun review() { if (_state.value.requestBody() != null && !_state.value.busy) _state.update { it.copy(reviewing = true) } }
	fun submit() {
		val snapshot = _state.value
		if (snapshot.busy || !snapshot.reviewing || snapshot.requestBody() == null) return
		perform {
			_state.update { it.copy(submitting = true) }
			try {
				// Re-read permissions and availability immediately before the one POST; Seerr remains approval/quota authority.
				val user = api.me()
				val title = api.details(requireNotNull(snapshot.title).mediaType, snapshot.title.id)
				val libraryItems = library.find(title).getOrElse { throw SeerrFailure() }
				require(title.mediaType != "movie" || libraryItems.isEmpty())
				val service = snapshot.service?.let { api.service(title.mediaType, it.server.id) }
				val body = prepareSeerrRequest(user, title, snapshot.is4k, snapshot.seasons, service, snapshot.profileId, snapshot.rootFolder)
				val receipt = api.submit(body)
				_state.update { it.copy(reviewing = false, needsRefresh = true, message = "Request #${receipt.id}: ${receipt.statusLabel}. Refresh to see availability.") }
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) {
				_state.update { it.copy(reviewing = false, needsRefresh = true) }
				throw error
			}
			finally { _state.update { it.copy(submitting = false) } }
		}
	}
}
