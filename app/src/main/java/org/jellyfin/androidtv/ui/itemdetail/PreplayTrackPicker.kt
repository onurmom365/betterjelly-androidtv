package org.jellyfin.androidtv.ui.itemdetail

import android.app.AlertDialog
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.selection.PreplayTrackChoice
import org.jellyfin.androidtv.ui.playback.selection.PreplayTrackSelection
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.android.ext.android.inject

/** Normal user-initiated, lifecycle-bound metadata reads; never changes server preferences. */
fun FullDetailsFragment.showPreplayTracks(audio: Boolean) {
	val preferences by inject<UserPreferences>()
	if (preferences[UserPreferences.useExternalPlayer] || preferences[UserPreferences.playbackRewriteVideoEnabled]) {
		Toast.makeText(requireContext(), R.string.preplay_internal_player, Toast.LENGTH_LONG).show()
		return
	}
	val selections by inject<PreplayTrackSelection>()
	val identity = selections.accountIdentity() ?: return
	if (mTrackPickerOpen) return
	mTrackPickerOpen = true
	val owner = mBaseItem
	val type = if (audio) MediaStreamType.AUDIO else MediaStreamType.SUBTITLE
	val series = owner.type == BaseItemKind.SERIES
	val title = getString(if (audio) R.string.preplay_audio else R.string.preplay_subtitles)
	Toast.makeText(requireContext(), R.string.preplay_loading, Toast.LENGTH_SHORT).show()
	viewLifecycleOwner.lifecycleScope.launch {
		val api by inject<ApiClient>()
		var shown = false
		try {
			val reference = if (series) {
				getNextUpEpisode() ?: withContext(Dispatchers.IO) {
					api.tvShowsApi.getEpisodes(seriesId = owner.id, isMissing = false, limit = 1,
						fields = ItemRepository.itemFields).content.items.firstOrNull()
				}
			} else owner
			val item = reference?.let {
				if (it.mediaSources.orEmpty().any { !it.mediaStreams.isNullOrEmpty() }) it
				else withContext(Dispatchers.IO) { api.userLibraryApi.getItem(it.id).content }
			}
			if (!isResumed || mBaseItem.id != owner.id || selections.accountIdentity() != identity) return@launch
			val source = item?.let(selections::source)
			val streams = source?.mediaStreams.orEmpty().filter { it.type == type }
			val current = selections.read(owner.id, type)
			val choices = mutableListOf<PreplayTrackChoice?>()
			val labels = mutableListOf<String>()
			// An episode can inherit the series choice OR explicitly return to server defaults.
			if (!series && owner.seriesId != null) {
				choices += null
				labels += getString(R.string.preplay_inherit)
			}
			choices += PreplayTrackChoice()
			labels += getString(R.string.preplay_default)
			if (!audio) {
				choices += PreplayTrackChoice(mode = "off")
				labels += getString(R.string.preplay_off)
			}
			if (item != null && source != null) for (stream in streams) {
				choices += PreplayTrackChoice.track(item, source, stream)
				labels += stream.displayTitle ?: stream.title ?: stream.language ?: "#${stream.index}"
			}
			var checked = choices.indexOfFirst { choice ->
				when {
					current == null -> choice == null || (choices.first() != null && choice?.mode == "default")
					choice?.mode != current.mode -> false
					current.mode != "track" -> true
					item == null || source == null -> false
					else -> choice?.index == current.resolve(item, source, type)
				}
			}
			if (checked < 0 && current != null) {
				choices += current
				labels += getString(R.string.preplay_unavailable, current.label ?: title)
				checked = choices.lastIndex
			}
			val scope = getString(if (series) R.string.preplay_series_scope else R.string.preplay_item_scope)
			val dialog = AlertDialog.Builder(requireContext())
				.setTitle("$title · $scope")
				.setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
					if (selections.accountIdentity() == identity && mBaseItem.id == owner.id) {
						selections.save(owner.id, type, choices[index])
					}
					dialog.dismiss()
				}
				.setNegativeButton(R.string.lbl_cancel, null)
				.create()
			dialog.show()
			shown = true
			dialog.listView.apply { if (checked >= 0) setSelection(checked); requestFocus() }
			val lifecycle = viewLifecycleOwner.lifecycle
			val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
				override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { dialog.dismiss() }
			}
			lifecycle.addObserver(observer)
			dialog.setOnDismissListener { mTrackPickerOpen = false; lifecycle.removeObserver(observer) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			if (isResumed) Toast.makeText(requireContext(), R.string.preplay_load_failed, Toast.LENGTH_LONG).show()
		} finally {
			if (!shown) mTrackPickerOpen = false
		}
	}
}
