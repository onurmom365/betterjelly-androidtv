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
					if (hasSession) api.me()
					checked = true
				}
				if (!_state.value.connected) return@launch
				val results = loadSeerrSearch(api, query)
				_state.update { it.copy(titles = results.titles, message = results.message) }
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) {
				_state.update { it.copy(connected = it.connected && (error as? SeerrFailure)?.status !in listOf(401, 403),
					message = ((error as? SeerrFailure)?.userMessage ?: "Seerr results could not load. Please retry the search.") ) }
			} finally { if (kotlin.coroutines.coroutineContext[Job]?.isActive == true) _state.update { it.copy(loading = false) } }
		}
	}
}

internal data class SeerrSearchResults(val titles: List<SeerrTitle>, val message: String? = null)

internal suspend fun loadSeerrSearch(api: SeerrApi, query: String): SeerrSearchResults {
	if (query.isNotBlank()) return SeerrSearchResults(api.titles(query.trim(), "movie", 1).results)
	// A discovery category can fail independently of search and the other category.
	return coroutineScope {
		suspend fun category(type: String) = try { Result.success(api.titles("", type, 1).results.take(10)) }
		catch (error: CancellationException) { throw error }
		catch (error: Exception) { Result.failure(error) }
		val movies = async { category("movie") }
		val shows = async { category("tv") }
		val results = listOf(movies.await(), shows.await())
		SeerrSearchResults(results.flatMap { it.getOrNull().orEmpty() },
			if (results.any { it.isFailure }) "Some Seerr discovery titles could not load. You can still search for a title." else null)
	}
}
