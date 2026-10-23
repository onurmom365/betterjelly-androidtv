package org.jellyfin.androidtv.ui.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SeerrSearchState(
	val connected: Boolean = false,
	val titles: List<SeerrTitle> = emptyList(),
	val loading: Boolean = false,
	val message: String? = null,
)

/** Independent from native library searches: an unavailable Seerr cannot delay or clear library results. */
class SeerrSearchViewModel(private val api: SeerrApi) : ViewModel() {
	private val _state = MutableStateFlow(SeerrSearchState())
	val state = _state.asStateFlow()
	private var checked = false
	private var job: Job? = null

	fun refresh(query: String) { checked = false; search(query, immediate = true) }
	fun search(query: String, immediate: Boolean = false) {
		job?.cancel()
		_state.update { it.copy(titles = emptyList(), loading = true, message = null) }
		job = viewModelScope.launch {
			try {
				if (!immediate) delay(600)
				if (!checked) {
					val hasSession = withContext(Dispatchers.IO) { api.restoreSession() }
					_state.update { it.copy(connected = hasSession) }
					checked = true
					if (hasSession) api.me()
				}
				if (!_state.value.connected) return@launch
				val titles = if (query.isBlank()) coroutineScope {
					val movies = async { api.titles("", "movie", 1).results }
					val shows = async { api.titles("", "tv", 1).results }
					movies.await().take(10) + shows.await().take(10)
				} else api.titles(query.trim(), "movie", 1).results
				_state.update { it.copy(titles = titles) }
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) {
				_state.update { it.copy(connected = it.connected && (error as? SeerrFailure)?.status !in listOf(401, 403),
					message = "Seerr is unavailable. Your library results remain available below.") }
			} finally { if (kotlin.coroutines.coroutineContext[Job]?.isActive == true) _state.update { it.copy(loading = false) } }
		}
	}
}
