package org.jellyfin.androidtv.ui.seerr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject

class SeerrFragment : Fragment() {
	private val sessions by inject<SessionRepository>()
	private val libraryApi by inject<ApiClient>()
	private val navigation by inject<NavigationRepository>()
	private val scopedModels by lazy { ViewModelProvider(this)[SeerrScopedModels::class.java] }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		JellyfinTheme {
			val session by sessions.currentSession.collectAsState()
			val settings = remember { seerrEndpoints(requireContext()) }
			val revision by settings.revision.collectAsState()
			val identity = seerrAccountIdentity(session)
			val endpoint = remember(identity, revision) { settings.endpoint(identity) }
			val scope = seerrAccountScope(session, endpoint)
			if (scope == null) { Text("Sign in to your Jellyfin library first."); return@JellyfinTheme }
			var serverOpen by remember { mutableStateOf(false) }
			val model = remember(scope, revision) {
				val api = seerrApiForAccount(requireContext(), sessions, scope, endpoint)
				scopedModels.provider("$scope/$revision", object : ViewModelProvider.Factory {
					@Suppress("UNCHECKED_CAST")
					override fun <T : ViewModel> create(modelClass: Class<T>): T = SeerrViewModel(api, JellyfinSeerrLibrary(libraryApi)) as T
				})["seerr-$scope", SeerrViewModel::class.java]
			}
			val state by model.state.collectAsState()
			LaunchedEffect(model) {
				model.start(arguments?.getString("query").orEmpty(), force = true, type = arguments?.getString("type"),
					id = arguments?.getInt("mediaId")?.takeIf { it > 0 }, history = arguments?.getBoolean("history") == true)
			}
			val secure = state.user == null
			DisposableEffect(secure) {
				val window = requireActivity().window
				val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
				if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
				onDispose { if (secure && !wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
			}
			DisposableEffect(model) {
				val owner = viewLifecycleOwner
				var wasPaused = false
				val observer = LifecycleEventObserver { _, event ->
					if (event == Lifecycle.Event.ON_PAUSE) wasPaused = true
					if (event == Lifecycle.Event.ON_RESUME && wasPaused) { wasPaused = false; model.resumed() }
				}
				owner.lifecycle.addObserver(observer)
				onDispose { owner.lifecycle.removeObserver(observer); model.leave() }
			}
			val back = {
				if (!state.submitting) {
					if (state.requestPrompt != null) model.dismissRequestAction()
					else if (state.pinCode != null) model.cancelPlex()
					else if (state.requestOpen || state.reviewing || (state.title != null && state.historyMode)) model.back()
					else { model.leave(); if (!navigation.goBack()) navigation.navigate(Destinations.search(), replace = true) }
				}
			}
			BackHandler { back() }
			SeerrScreen(state, model, back,
				onSearch = { model.leave(); navigation.navigate(Destinations.search(state.query)) },
				onLibraryItem = { navigation.navigate(Destinations.itemDetails(it)) },
				serverOrigin = endpoint.origin, onServer = { if (!state.submitting) serverOpen = true })
			if (serverOpen && identity != null) SeerrServerDialog(endpoint, settings, identity,
				onBeforeSave = { model.leave() }, onDismiss = { serverOpen = false })
		}
	}
}
