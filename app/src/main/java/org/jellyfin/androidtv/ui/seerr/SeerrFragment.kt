package org.jellyfin.androidtv.ui.seerr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.koin.android.ext.android.inject
import org.jellyfin.sdk.api.client.ApiClient

class SeerrFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val libraryApi by inject<ApiClient>()
	private val navigation by inject<NavigationRepository>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		JellyfinTheme {
			val session by sessionRepository.currentSession.collectAsState()
			val scope = session?.let { "${it.serverId}/${it.userId}/$SEERR_ORIGIN" }
			if (scope == null) {
				Text("Sign in to your Jellyfin library first.", Modifier.padding(48.dp))
				return@JellyfinTheme
			}
			val model = remember(scope) {
				val store = AndroidSeerrSessionStore(requireContext().applicationContext, scope)
				val api = SeerrApi(store, accessAllowed = {
					sessionRepository.currentSession.value?.let { "${it.serverId}/${it.userId}/$SEERR_ORIGIN" } == scope
				})
				ViewModelProvider(this, object : ViewModelProvider.Factory {
					@Suppress("UNCHECKED_CAST")
					override fun <T : ViewModel> create(modelClass: Class<T>): T = SeerrViewModel(api, JellyfinSeerrLibrary(libraryApi)) as T
				})["seerr-$scope", SeerrViewModel::class.java]
			}
			val state by model.state.collectAsState()
			LaunchedEffect(model) { model.start(arguments?.getString("query").orEmpty()) }
			DisposableEffect(model) {
				val window = requireActivity().window
				val alreadySecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
				window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
				onDispose {
					model.leave()
					if (!alreadySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
				}
			}
			BackHandler(enabled = state.title != null || state.pinCode != null) {
				if (state.pinCode != null) model.cancelPlex() else model.back()
			}
			SeerrScreen(state, model,
				onLibrary = { navigation.navigate(Destinations.search(it)) },
				onLibraryItem = { navigation.navigate(Destinations.itemDetails(it)) })
		}
	}
}

@Composable
private fun SeerrScreen(s: SeerrUiState, model: SeerrViewModel, onLibrary: (String) -> Unit, onLibraryItem: (java.util.UUID) -> Unit) {
	var query by remember(s.query) { mutableStateOf(s.query) }
	var email by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") } // Deliberately not saveable or persisted.
	val firstFocus = remember { FocusRequester() }
	LaunchedEffect(s.user?.id, s.title?.id, s.reviewing) { firstFocus.requestFocus() }
	LazyColumn(
		modifier = Modifier.fillMaxSize().background(JellyfinTheme.colorScheme.background).padding(horizontal = 48.dp, vertical = 24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item {
			Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
				Text("Seerr", fontSize = 26.sp)
				Button(onClick = { if (s.title != null) model.back() else { model.leave(); onLibrary(query) } },
					modifier = Modifier.focusRequester(firstFocus), enabled = !s.submitting) {
					Text(if (s.title != null) "Back" else "Search your library")
				}
				if (s.user != null) Action("Disconnect", !s.busy) { model.disconnect() }
			}
		}
		if (s.busy) item { Text(if (s.pinCode != null) "Waiting for Plex sign-in…" else "Loading…") }
		s.message?.let { message -> item { Text(message, color = JellyfinTheme.colorScheme.onBackground, fontSize = 18.sp) } }
		if (s.user == null) {
			if (s.pinCode != null) {
				item { Text("On your phone or computer, open https://plex.tv/link and enter:", fontSize = 20.sp) }
				item { Text(s.pinCode, fontSize = 40.sp) }
				item { Action("Cancel Plex sign-in") { model.cancelPlex() } }
			} else {
				item { Text("Connect your Seerr account. Your library remains available without Seerr.") }
				item { Action("Retry connection", !s.busy) { model.start(query, force = true) } }
				item { Action("Sign in with Plex", !s.busy) { password = ""; model.loginPlex() } }
				item { Text("Or use your Seerr email and password", fontSize = 18.sp) }
				item { SeerrInput("Email", email, false, !s.busy) { email = it } }
				item { SeerrInput("Password", password, true, !s.busy) { password = it } }
				item { Action("Sign in", !s.busy && email.isNotBlank() && password.isNotBlank()) {
					val secret = password
					password = ""
					model.login(email, secret)
				} }
			}
		} else if (s.title == null) {
			item { Text("Search for a missing movie or show, or browse popular titles.") }
			item {
				SearchTextInput(query, { query = it }, { model.browse(query, s.type) }, Modifier.fillMaxWidth())
			}
			item {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Action("Search Seerr", !s.busy && query.isNotBlank()) { model.browse(query, s.type) }
					Action("Popular movies", !s.busy) { query = ""; model.browse("", "movie") }
					Action("Popular shows", !s.busy) { query = ""; model.browse("", "tv") }
				}
			}
			items(s.results, key = { "${it.mediaType}-${it.id}" }) { title ->
				Button(onClick = { model.openTitle(title) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
					Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
						title.posterPath?.takeIf { it.startsWith('/') }?.let { path ->
							AsyncImage("https://image.tmdb.org/t/p/w185$path", contentDescription = null,
								modifier = Modifier.size(54.dp, 80.dp), contentScale = ContentScale.Crop)
						}
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(title.displayTitle, fontSize = 20.sp)
							Text("${title.year} · ${if (title.mediaType == "tv") "TV" else "Movie"} · ${title.statusLabel()}")
						}
					}
				}
			}
			if (!s.busy && s.results.isEmpty()) item { Text("No results. Try another search or return to your library.") }
			item {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Action("Previous", !s.busy && s.page > 1) { model.browse(s.query, s.type, s.page - 1) }
					Text("Page ${s.page} of ${s.totalPages.coerceAtLeast(1)}")
					Action("Next", !s.busy && s.page < s.totalPages) { model.browse(s.query, s.type, s.page + 1) }
				}
			}
		} else {
			val title = s.title
			item { Text(title.displayTitle + " " + title.year, fontSize = 24.sp) }
			item { Text(title.overview, maxLines = 5, fontSize = 17.sp) }
			item { Text("${if (s.is4k) "4K" else "Standard"}: ${title.statusLabel(s.is4k)}", fontSize = 20.sp) }
			items(title.mediaInfo?.requests.orEmpty(), key = { "request-${it.id}" }) { request ->
				itemRequestLabel(request)
			}
			if (s.reviewing) {
				item { Text("Confirm request", fontSize = 22.sp) }
				item { Text(if (title.mediaType == "tv") "Seasons: ${s.seasons.sorted().joinToString()}" else "Movie") }
				item { Text("Quality: ${if (s.is4k) "4K" else "Standard"}") }
				item { Text(s.service?.let { "${it.server.name} · ${it.profiles.find { p -> p.id == s.profileId }?.name}\n${s.rootFolder}" } ?: "Use Seerr's default server, quality profile and folder") }
				item { Text("Seerr applies your account's request limits and approval rules.") }
				item {
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						Action("Edit", !s.busy) { model.back() }
						Action("Submit request", !s.busy && s.requestBody() != null) { model.submit() }
					}
				}
			} else {
				item { Action("Refresh status", !s.busy) { model.openTitle(title) } }
				if (s.libraryMatches.isNotEmpty()) item { Text("Matches in your Jellyfin library", fontSize = 19.sp) }
				items(s.libraryMatches, key = { "library-${it.id}" }) { match ->
					Action(match.name.orEmpty(), !s.busy) { onLibraryItem(match.id) }
				}
				item { Action("Search your library", !s.busy) { onLibrary(title.displayTitle) } }
				if (s.exactLibraryMatch && title.mediaType == "movie") item { Text("This movie is already in your Jellyfin library.") }
				item {
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						Action(if (!s.is4k) "✓ Standard" else "Standard", !s.busy) { model.quality(false) }
						if (s.user.canRequest(title.mediaType, true)) Action(if (s.is4k) "✓ 4K" else "4K", !s.busy) { model.quality(true) }
					}
				}
				if (!s.user.canRequest(title.mediaType, s.is4k)) item { Text("Your Seerr account does not have permission to request this quality/type.") }
				if (title.mediaType == "tv") {
					item { Text("Choose seasons (available or pending seasons are omitted)", fontSize = 18.sp) }
					items(title.requestableSeasons(s.is4k), key = { "season-${it.seasonNumber}" }) { season ->
						Action("${if (season.seasonNumber in s.seasons) "✓ " else ""}${season.name.ifBlank { "Season ${season.seasonNumber}" }} · ${season.episodeCount} episodes", !s.busy) { model.season(season.seasonNumber) }
					}
				}
				if (s.user.canChooseService) {
					item { Action("Advanced request options", !s.busy) { model.advanced() } }
					if (s.services != null) {
						item { Action(if (s.service == null) "✓ Seerr defaults" else "Seerr defaults", !s.busy) { model.service(null) } }
						items(s.services, key = { "service-${it.id}" }) { service ->
							Action("${if (s.service?.server?.id == service.id) "✓ " else ""}${service.name}", !s.busy) { model.service(service) }
						}
					}
					s.service?.let { service ->
						item { Text("Quality profile", fontSize = 18.sp) }
						items(service.profiles, key = { "profile-${it.id}" }) { profile ->
							Action("${if (s.profileId == profile.id) "✓ " else ""}${profile.name}", !s.busy) { model.profile(profile.id) }
						}
						item { Text("Root folder", fontSize = 18.sp) }
						items(service.rootFolders, key = { "root-${it.path}" }) { root ->
							Action("${if (s.rootFolder == root.path) "✓ " else ""}${root.path}", !s.busy) { model.root(root.path) }
						}
					}
				}
				item { Action("Review request", !s.busy && s.requestBody() != null) { model.review() } }
			}
		}
	}
}

@Composable
private fun itemRequestLabel(request: SeerrRequest) = Text("Request #${request.id}: ${request.statusLabel} · ${if (request.is4k) "4K" else "Standard"}" +
	request.seasons.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · Seasons ") { it.seasonNumber.toString() }.orEmpty())

@Composable
private fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
	Button(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
private fun SeerrInput(label: String, value: String, secret: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(label)
		BasicTextField(value, onChange, enabled = enabled, singleLine = true,
			modifier = Modifier.fillMaxWidth().border(2.dp, JellyfinTheme.colorScheme.inputFocused, RoundedCornerShape(8.dp)).padding(12.dp),
			textStyle = JellyfinTheme.typography.default.copy(color = JellyfinTheme.colorScheme.onBackground, fontSize = 18.sp),
			keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Email, autoCorrectEnabled = false),
			visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
	}
}
