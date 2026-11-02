package org.jellyfin.androidtv.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.ui.seerr.SeerrSearchViewModel
import org.jellyfin.androidtv.ui.seerr.SeerrSearchState
import org.jellyfin.androidtv.ui.seerr.seerrAccountScope
import org.jellyfin.androidtv.ui.seerr.seerrApiForAccount
import org.jellyfin.androidtv.ui.seerr.seerrAccountIdentity
import org.jellyfin.androidtv.ui.seerr.seerrEndpoints
import org.jellyfin.androidtv.ui.seerr.SeerrScopedModels
import androidx.leanback.app.RowsSupportFragment
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.search.composable.SearchVoiceInput
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.androidtv.util.speech.rememberSpeechRecognizerAvailability
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

class SearchFragment : Fragment() {
	private val seerrModels by lazy { ViewModelProvider(this)[SeerrScopedModels::class.java] }
	companion object {
		const val EXTRA_QUERY = "query"
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		JellyfinTheme {
			val viewModel = koinViewModel<SearchViewModel>()
			val navigation = koinInject<NavigationRepository>()
			val searchFragmentDelegate = koinInject<SearchFragmentDelegate> { parametersOf(requireContext()) }
			var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
			val textInputFocusRequester = remember { FocusRequester() }
			val resultFocusRequester = remember { FocusRequester() }
			val speechRecognizerAvailability = rememberSpeechRecognizerAvailability()
			val sessions = koinInject<SessionRepository>()
			val session by sessions.currentSession.collectAsState()
			val seerrSettings = remember { seerrEndpoints(requireContext()) }
			val revision by seerrSettings.revision.collectAsState()
			val identity = seerrAccountIdentity(session)
			val endpoint = remember(identity, revision) { seerrSettings.endpoint(identity) }
			val scope = seerrAccountScope(session, endpoint)
			val seerrModel = remember(scope, revision) {
				scope?.let {
						val api = seerrApiForAccount(requireContext(), sessions, it, endpoint)
						seerrModels.provider("$it/$revision", object : ViewModelProvider.Factory {
						@Suppress("UNCHECKED_CAST")
						override fun <T : ViewModel> create(modelClass: Class<T>): T = SeerrSearchViewModel(api) as T
					})["search-seerr-$it", SeerrSearchViewModel::class.java]
				}
			}
			val seerrState = seerrModel?.state?.collectAsState()?.value ?: SeerrSearchState()
			val latestQuery by rememberUpdatedState(query.text)
			LaunchedEffect(seerrModel, query.text) { seerrModel?.search(query.text) }
			LaunchedEffect(seerrState.titles, query.text) {
				searchFragmentDelegate.showSeerrResults(seerrState.titles, if (query.text.isBlank()) "Discover on Seerr" else "Seerr results")
			}
			SideEffect {
				searchFragmentDelegate.onSeerrTitleClick = { title -> navigation.navigate(Destinations.seerr(type = title.mediaType, id = title.id)) }
			}
			DisposableEffect(seerrModel) {
				val owner = viewLifecycleOwner
				val observer = LifecycleEventObserver { _, event ->
					if (event == Lifecycle.Event.ON_RESUME) seerrModel?.refresh(latestQuery)
				}
				owner.lifecycle.addObserver(observer)
				onDispose { owner.lifecycle.removeObserver(observer); seerrModel?.leave() }
			}

			LaunchedEffect(Unit) {
				val extraQuery = arguments?.getString(EXTRA_QUERY)
				if (!extraQuery.isNullOrBlank()) {
					query = query.copy(text = extraQuery)
					viewModel.searchImmediately(extraQuery)
					resultFocusRequester.requestFocus()
				} else {
					textInputFocusRequester.requestFocus()
				}

				viewModel.searchResultsFlow.collect { results ->
					searchFragmentDelegate.showResults(results)
				}
			}

			Column {
				MainToolbar(MainToolbarActiveButton.Search)

				Row(
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.focusRestorer()
						.focusGroup()
						.padding(horizontal = 48.dp)
				) {
					if (speechRecognizerAvailability) {
						SearchVoiceInput(
							onQueryChange = { query = query.copy(text = it) },
							onQuerySubmit = {
								viewModel.searchImmediately(query.text)
								seerrModel?.search(query.text, immediate = true)
								resultFocusRequester.requestFocus()
							}
						)
					}

					SearchTextInput(
						query = query.text,
						onQueryChange = {
							query = query.copy(text = it)
							viewModel.searchDebounced(query.text)
						},
						onQuerySubmit = {
							viewModel.searchImmediately(query.text)
							seerrModel?.search(query.text, immediate = true)
							// Note: We MUST change the focus to somewhere else when the keyboard is submitted because some vendors (like Amazon)
							// will otherwise just keep showing a (fullscreen) keyboard, soft-locking the app.
							resultFocusRequester.requestFocus()
						},
						modifier = Modifier
							.weight(1f)
							.focusRequester(textInputFocusRequester),
					)
				}
				if (seerrState.message != null) Text(seerrState.message, Modifier.padding(horizontal = 48.dp, vertical = 6.dp))
				if (!seerrState.connected && !seerrState.loading) {
					Button(onClick = { navigation.navigate(Destinations.seerr(query.text)) }, modifier = Modifier.padding(horizontal = 48.dp)) {
						Text("Connect Seerr")
					}
				}

				// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
				// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
				// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
				var rowsSupportFragment by remember { mutableStateOf<RowsSupportFragment?>(null) }

				AndroidFragment<RowsSupportFragment>(
					modifier = Modifier
						.focusGroup()
						.focusRequester(resultFocusRequester)
						.focusProperties {
							onExit = {
								val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
								if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
									cancelFocusChange()
								} else {
									rowsSupportFragment?.selectedPosition = 0
									rowsSupportFragment?.verticalGridView?.clearFocus()
								}
							}
						}
						.padding(top = 5.dp)
						.fillMaxSize(),
					onUpdate = { fragment ->
						rowsSupportFragment = fragment
						fragment.adapter = searchFragmentDelegate.rowsAdapter
						fragment.onItemViewClickedListener = searchFragmentDelegate.onItemViewClickedListener
						fragment.onItemViewSelectedListener = searchFragmentDelegate.onItemViewSelectedListener
					}
				)
			}
		}
	}
}
