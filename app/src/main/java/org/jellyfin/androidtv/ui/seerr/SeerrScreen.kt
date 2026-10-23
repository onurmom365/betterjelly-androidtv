package org.jellyfin.androidtv.ui.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import java.util.UUID
import java.util.Locale

private val Ink = Color(0xFF090F1C)
private val Panel = Color(0xFF162136)
private val Line = Color(0xFF2D3B55)
private val Muted = Color(0xFFA9B7CF)
private val Accent = Color(0xFFA697FF)
private val Purple = Color(0xFF6C4BEF)
private val Green = Color(0xFF63E3B2)
private val Shape = RoundedCornerShape(16.dp)

@Composable
private fun Action(label: String, enabled: Boolean = true, primary: Boolean = false, selected: Boolean = false,
	modifier: Modifier = Modifier, onClick: () -> Unit) {
	Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(9.dp),
		colors = ButtonDefaults.colors(containerColor = if (primary || selected) Purple else Panel,
			contentColor = Color.White, focusedContainerColor = Accent, focusedContentColor = Ink,
			disabledContainerColor = Panel.copy(alpha = 0.5f), disabledContentColor = Muted.copy(alpha = 0.5f))) {
		Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
	}
}

@Composable
private fun Badge(label: String) {
	val color = when (label) { "Available" -> Green; "Failed", "Blocked" -> Color(0xFFFF9696); else -> Accent }
	Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
		modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
			.border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun SeerrScreen(s: SeerrUiState, model: SeerrViewModel, onBack: () -> Unit, onSearch: () -> Unit, onLibraryItem: (UUID) -> Unit) {
	val first = remember { FocusRequester() }
	LaunchedEffect(s.user?.id, s.title?.id) { first.requestFocus() }
	Column(Modifier.fillMaxSize().background(Ink)) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Action("‹ Back", enabled = !s.submitting, modifier = Modifier.focusRequester(first), onClick = onBack)
			Text("Seerr", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
			Spacer(Modifier.weight(1f))
			Action("Search", enabled = !s.submitting, onClick = onSearch)
			if (s.user != null) {
				Action("Requests", !s.busy, selected = s.historyMode && s.title == null) { model.history() }
				Action("Disconnect", !s.busy) { model.disconnect() }
			}
		}
		if (s.busy) Text(if (s.pinCode != null) "Waiting for Plex…" else "Loading…", color = Muted,
			modifier = Modifier.padding(start = 40.dp, bottom = 8.dp))
		s.message?.let { Message(it) }
		when {
			s.restoringSession -> Text("Checking your saved Seerr session…", color = Muted,
				modifier = Modifier.padding(horizontal = 40.dp, vertical = 18.dp))
			s.user == null -> SignIn(s, model)
			s.title != null -> TitleDetails(s, model, onLibraryItem)
			s.historyMode -> RequestHistory(s, model)
			else -> PosterGallery(s, model, onSearch)
		}
	}
	if (s.requestOpen && s.title != null) RequestDialog(s, model)
}

@Composable
private fun Message(message: String) = Text(message, color = Muted, fontSize = 15.sp,
	modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp))
		.background(Panel).padding(14.dp))

@Composable
private fun SignIn(s: SeerrUiState, model: SeerrViewModel) {
	var email by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	LazyColumn(Modifier.widthIn(max = 600.dp).padding(horizontal = 40.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
		item { Text("Your next movie starts here.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
		item { Text("Connect Seerr to discover titles and manage requests alongside your Jellyfin library.", color = Muted, fontSize = 17.sp) }
		if (s.pinCode != null) {
			item { Text("Open plex.tv/link on your phone or computer", color = Color.White, fontSize = 20.sp) }
			item { Text(s.pinCode, color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold) }
			item { Action("Cancel", onClick = model::cancelPlex) }
		} else {
			item { Action("Continue with Plex", !s.busy, primary = true) { password = ""; model.loginPlex() } }
			item { Text("Or sign in with your Seerr account", color = Muted) }
			item { CredentialInput("Email", email, false, !s.busy) { email = it } }
			item { CredentialInput("Password", password, true, !s.busy) { password = it } }
			item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				Action("Sign in", !s.busy && email.isNotBlank() && password.isNotBlank(), primary = true) {
					val secret = password; password = ""; model.login(email, secret)
				}
				Action("Retry connection", !s.busy) { model.start(s.query, force = true) }
			} }
		}
	}
}

@Composable
private fun CredentialInput(label: String, value: String, secret: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(label, color = Muted)
		BasicTextField(value, onChange, enabled = enabled, singleLine = true,
			modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(9.dp)).border(1.dp, Line, RoundedCornerShape(9.dp)).padding(14.dp),
			textStyle = JellyfinTheme.typography.default.copy(color = Color.White, fontSize = 18.sp),
			keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Email, autoCorrectEnabled = false),
			visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
	}
}

@Composable
private fun TitleDetails(s: SeerrUiState, model: SeerrViewModel, onLibraryItem: (UUID) -> Unit) {
	val title = requireNotNull(s.title)
	val requestable = s.user?.canRequest(title.mediaType, false) == true && s.libraryChecked && !s.needsRefresh &&
		if (title.mediaType == "movie") !s.exactLibraryMatch && title.normalMovieRequestable() else title.normalRequestableSeasons().isNotEmpty()
	LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		item {
			Box(Modifier.fillMaxWidth().heightIn(min = 310.dp)) {
				AsyncImage(tmdbImage(title.backdropPath, "w1280"), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
				Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Ink, Ink.copy(alpha = 0.75f), Ink.copy(alpha = 0.25f)))))
				Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink))))
				Row(Modifier.padding(horizontal = 40.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
					AsyncImage(tmdbImage(title.posterPath), title.displayTitle, Modifier.size(152.dp, 228.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
					Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
						Text(if (title.mediaType == "tv") "SERIES" else "MOVIE", color = Accent, fontSize = 12.sp, letterSpacing = 2.sp)
						Text(title.displayTitle, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2)
						Text(listOf(title.year, title.runtime?.takeIf { it > 0 }?.let { "$it min" }.orEmpty(), title.genres.take(3).joinToString(" · ") { it.name })
							.filter { it.isNotBlank() }.joinToString("  |  "), color = Muted, fontSize = 15.sp)
						Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
							Badge(title.displayStatus())
							if (title.voteAverage > 0) Text("★ ${String.format(Locale.US, "%.1f", title.voteAverage)}", color = Color(0xFFFFD477), fontSize = 16.sp)
						}
						Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
							Action(if (title.mediaType == "tv") "Request seasons" else "Request movie", !s.busy && requestable, primary = true) { model.beginRequest() }
							Action("Refresh status", !s.busy) { model.openTitle(title) }
						}
					}
				}
			}
		}
		item { Column(Modifier.padding(horizontal = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Overview", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
			Text(title.overview.ifBlank { "No overview available." }, color = Muted, fontSize = 17.sp)
		} }
		if (s.libraryMatches.isNotEmpty()) item { Text("In your Jellyfin library", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 40.dp)) }
		items(s.libraryMatches, key = { it.id }) { item ->
			Action("Play in library · ${item.name.orEmpty()}", !s.busy, modifier = Modifier.padding(horizontal = 40.dp)) { onLibraryItem(item.id) }
		}
		if (title.mediaInfo?.requests.orEmpty().any { it.is4k && it.status in listOf(1, 2) }) item {
			Message("An existing request uses Seerr's separate 4K route. Without a matching destination, Seerr can save it without sending it to Radarr/Sonarr. New requests use the normal route and your chosen quality profile.")
		}
		items(title.mediaInfo?.requests.orEmpty(), key = { "r-${it.id}" }) { request ->
			Text("Request #${request.id} · ${request.statusLabel}${if (request.is4k) " · Existing 4K route" else ""}", color = Muted, modifier = Modifier.padding(horizontal = 40.dp))
		}
		item { Spacer(Modifier.height(28.dp)) }
	}
}

@Composable
private fun PosterGallery(s: SeerrUiState, model: SeerrViewModel, onSearch: () -> Unit) {
	Column(Modifier.padding(horizontal = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(if (s.query.isBlank()) "Discover on Seerr" else "Results for “${s.query}”", color = Color.White, fontSize = 25.sp)
			Action("Search", onClick = onSearch)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Action("Movies", !s.busy, selected = s.type == "movie") { model.browse("", "movie") }
			Action("Series", !s.busy, selected = s.type == "tv") { model.browse("", "tv") }
			Action("Previous", !s.busy && s.page > 1) { model.browse(s.query, s.type, s.page - 1) }
			Action("Next", !s.busy && s.page < s.totalPages) { model.browse(s.query, s.type, s.page + 1) }
		}
		LazyVerticalGrid(GridCells.Adaptive(132.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
			items(s.results, key = { "${it.mediaType}-${it.id}" }) { title ->
				Button(onClick = { model.openTitle(title) }, enabled = !s.busy, shape = RoundedCornerShape(10.dp),
					contentPadding = PaddingValues(5.dp), colors = ButtonDefaults.colors(containerColor = Ink, focusedContainerColor = Panel, focusedContentColor = Color.White)) {
					Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
						Box {
							AsyncImage(tmdbImage(title.posterPath), title.displayTitle, Modifier.fillMaxWidth().aspectRatio(2f / 3).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
							Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { Badge(if (title.mediaType == "tv") "SERIES" else "MOVIE") }
						}
						Text(title.displayTitle, color = Color.White, maxLines = 2, fontSize = 16.sp)
						Text("${title.year} · ${title.displayStatus()}", color = Muted, fontSize = 12.sp, maxLines = 1)
					}
				}
			}
		}
	}
}

@Composable
private fun RequestHistory(s: SeerrUiState, model: SeerrViewModel) {
	Column(Modifier.padding(horizontal = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text("Your requests", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
			Spacer(Modifier.weight(1f)); Action("Refresh", !s.busy) { model.history(s.historyFilter, s.historyPage) }
		}
		LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
			items(listOf("all" to "All", "pending" to "Pending approval", "processing" to "Requested", "available" to "Available", "failed" to "Failed")) { (key, name) ->
				Action(name, !s.busy, selected = s.historyFilter == key) { model.history(key) }
			}
		}
		s.historyWarning?.let { Message(it) }
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
			Action("Previous", !s.busy && s.historyPage > 1) { model.history(s.historyFilter, s.historyPage - 1) }
			Text("Page ${s.historyPage} of ${s.historyPages.coerceAtLeast(1)}", color = Muted)
			Action("Next", !s.busy && s.historyPage < s.historyPages) { model.history(s.historyFilter, s.historyPage + 1) }
		}
		if (s.history.isEmpty() && !s.busy) Text("No requests in this view.", color = Muted, fontSize = 18.sp)
		LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			items(s.history, key = { it.request.id }) { entry ->
				val title = entry.title
				Button(onClick = { if (title != null) model.openTitle(title) }, enabled = !s.busy && title != null,
					modifier = Modifier.fillMaxWidth(), shape = Shape, contentPadding = PaddingValues(14.dp),
					colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Color(0xFF2E3B59), focusedContentColor = Color.White)) {
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						AsyncImage(tmdbImage(title?.posterPath), null, Modifier.size(58.dp, 87.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
						Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
							Text(title?.displayTitle ?: "Request #${entry.request.id}", color = Color.White, fontSize = 17.sp, maxLines = 2)
							val status = title?.displayStatus()?.takeIf { it in listOf("Available", "Partly available") } ?: entry.request.statusLabel
							Badge(status)
							entry.request.profileName?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 2) }
							if (entry.request.seasons.isNotEmpty()) Text("Seasons ${entry.request.seasons.joinToString { it.seasonNumber.toString() }}", color = Muted, fontSize = 12.sp, maxLines = 1)
						}
					}
				}
			}
		}
	}
}

private data class Picker(val kind: String, val label: String, val choices: List<Pair<String, String>>, val selected: String?)

@Composable
private fun RequestDialog(s: SeerrUiState, model: SeerrViewModel) {
	val title = requireNotNull(s.title)
	var picker by remember { mutableStateOf<Picker?>(null) }
	val cancelFocus = remember { FocusRequester() }
	LaunchedEffect(s.reviewing) { cancelFocus.requestFocus() }
	Dialog(onDismissRequest = { if (!s.submitting) model.back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		Column(Modifier.widthIn(max = 780.dp).fillMaxWidth(0.9f).heightIn(max = 492.dp).clip(Shape).background(Panel).border(1.dp, Line, Shape)) {
			Box(Modifier.fillMaxWidth().height(112.dp)) {
				AsyncImage(tmdbImage(title.backdropPath, "w1280"), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
				Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Panel.copy(alpha = 0.25f), Panel))))
				Column(Modifier.align(Alignment.BottomStart).padding(22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
					Text(if (s.reviewing) "REVIEW REQUEST" else if (title.mediaType == "tv") "REQUEST SERIES" else "REQUEST MOVIE", color = Accent, fontSize = 12.sp, letterSpacing = 2.sp)
					Text(title.displayTitle, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			}
			if (s.submitting) Text(s.submissionStep ?: "Preparing request…", color = Accent, fontSize = 14.sp,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
			s.message?.let { message -> Text(message, color = Color(0xFFFFC588), fontSize = 14.sp,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }
			LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
				if (s.busy && !s.submitting) item { Text("Loading request options…", color = Muted) }
				if (s.reviewing) {
					item { Summary("Destination", s.service?.server?.name ?: "Seerr default") }
					item { Summary("Quality profile", s.service?.profiles?.find { it.id == s.profileId }?.name ?: "Seerr default") }
					item { Summary("Root folder", s.rootFolder ?: "Seerr default") }
					item { Summary("Tags", tagNames(s)) }
					if (title.mediaType == "tv") item { Summary("Seasons", s.seasons.sorted().joinToString()) }
					item { Text(if (s.user?.permits(16, 128, if (title.mediaType == "movie") 256 else 512) == true)
						"Your Seerr account approves this request automatically." else "This request will wait for approval in Seerr.", color = Accent, fontSize = 14.sp) }
				} else {
					if (title.mediaType == "tv") {
						item { Text("Seasons · ${s.seasons.size} selected", color = Muted, fontSize = 14.sp) }
						item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							item { Action("All missing", !s.busy) { model.allSeasons() } }
							items(title.normalRequestableSeasons(), key = { it.seasonNumber }) { season ->
								Action(if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}", !s.busy,
									selected = season.seasonNumber in s.seasons) { model.season(season.seasonNumber) }
							}
						} }
					}
					if (s.user?.canChooseService == true) {
						item { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
							PickerField("Destination server", s.service?.server?.name ?: "Choose destination", Modifier.weight(1f), !s.busy) {
								picker = Picker("server", "Destination server", s.services.map { it.id.toString() to it.name }, s.service?.server?.id?.toString())
							}
							PickerField("Quality profile", s.service?.profiles?.find { it.id == s.profileId }?.name ?: "Choose profile", Modifier.weight(1f), !s.busy && s.service != null) {
								picker = Picker("profile", "Quality profile", s.service?.profiles.orEmpty().map { it.id.toString() to it.name }, s.profileId?.toString())
							}
						} }
						item { PickerField("Root folder", s.rootFolder ?: "Choose folder", enabled = !s.busy && s.service != null) {
							picker = Picker("root", "Root folder", s.service?.rootFolders.orEmpty().map { root ->
								root.path to (root.path + if (root.freeSpace > 0) " (${root.freeSpace / (1024L * 1024 * 1024)} GB free)" else "")
							}, s.rootFolder)
						} }
						item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text("Tags", color = Muted, fontSize = 14.sp)
							if (s.service?.tags.isNullOrEmpty()) Text("No preset tags available for this destination.", color = Muted, fontSize = 14.sp)
							else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
								items(s.service?.tags.orEmpty(), key = { it.id }) { tag ->
									Action((if (tag.id in s.effectiveTags) "✓ " else "") + tag.label, !s.busy, selected = tag.id in s.effectiveTags) { model.tag(tag.id) }
								}
								item { Action("Use defaults", !s.busy) { model.defaultTags() } }
							}
						} }
					} else item { Text("Your request will use the destination, profile and tags configured in Seerr.", color = Muted) }
				}
				item { Spacer(Modifier.height(4.dp)) }
			}
			Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
				Action(if (s.reviewing) "Edit" else "Cancel", !s.submitting, modifier = Modifier.focusRequester(cancelFocus)) { model.back() }
				Action(if (s.submitting) "Requesting…" else if (s.needsRefresh) "Check status" else if (s.reviewing) "Request" else "Review request",
					!s.busy && (s.needsRefresh || s.requestBody() != null), primary = true) {
					if (s.needsRefresh) model.checkRequestStatus() else if (s.reviewing) model.submit() else model.review()
				}
			}
		}
	}
	picker?.let { current ->
		ChoiceDialog(current, onDismiss = { picker = null }) { key ->
			picker = null
			when (current.kind) {
				"server" -> s.services.find { it.id.toString() == key }?.let { model.service(it) }
				"profile" -> model.profile(key.toInt())
				"root" -> model.root(key)
			}
		}
	}
}

private fun tagNames(s: SeerrUiState): String = s.service?.tags.orEmpty().filter { it.id in s.effectiveTags }.joinToString { it.label }
	.ifBlank { if (s.effectiveTags.isNotEmpty()) "Configured defaults" else "None" }

@Composable
private fun Summary(label: String, value: String) = Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
	Text(label, color = Muted, fontSize = 12.sp)
	Text(value, color = Color.White, fontSize = 17.sp)
}

@Composable
private fun PickerField(label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
		Text(label, color = Muted, fontSize = 14.sp)
		Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp),
			colors = ButtonDefaults.colors(containerColor = Ink.copy(alpha = 0.4f), contentColor = Color.White, focusedContainerColor = Accent, focusedContentColor = Ink)) {
			Text(value, Modifier.weight(1f), maxLines = 2, fontSize = 15.sp); Text("⌄")
		}
	}
}

@Composable
private fun ChoiceDialog(picker: Picker, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
	val index = picker.choices.indexOfFirst { it.first == picker.selected }.coerceAtLeast(0)
	val focus = remember { FocusRequester() }
	val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
	LaunchedEffect(Unit) { withFrameNanos { }; if (picker.choices.isNotEmpty()) focus.requestFocus() }
	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(0.8f).heightIn(max = 420.dp).clip(Shape).background(Panel).border(1.dp, Line, Shape).padding(22.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)) {
			Text(picker.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
			LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(7.dp)) {
				items(picker.choices, key = { it.first }) { (key, label) ->
					Action((if (key == picker.selected) "✓ " else "") + label, selected = key == picker.selected,
						modifier = Modifier.fillMaxWidth().then(if (picker.choices[index].first == key) Modifier.focusRequester(focus) else Modifier)) { onSelect(key) }
				}
			}
		}
	}
}
