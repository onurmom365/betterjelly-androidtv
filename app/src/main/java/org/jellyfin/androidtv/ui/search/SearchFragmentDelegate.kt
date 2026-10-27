package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.seerr.SeerrTitle
import org.jellyfin.androidtv.ui.seerr.SeerrPosterPresenter

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())
	private var seerrRow: ListRow? = null
	private val seerrItems = MutableObjectAdapter<SeerrTitle>(SeerrPosterPresenter())
	private var seerrHeading: String? = null
	var onSeerrTitleClick: (SeerrTitle) -> Unit = {}

	fun showSeerrResults(titles: List<SeerrTitle>, heading: String) {
		seerrItems.replaceAll(titles, areItemsTheSame = { old, new -> old.id == new.id && old.mediaType == new.mediaType })
		if (titles.isEmpty()) { seerrRow?.let { rowsAdapter.remove(it) }; seerrRow = null; return }
		if (seerrRow != null && seerrHeading == heading) return
		seerrRow?.let { rowsAdapter.remove(it) }
		seerrRow = ListRow(HeaderItem(heading), seerrItems)
		seerrHeading = heading
		rowsAdapter.add(0, requireNotNull(seerrRow))
	}

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		rowsAdapter.clear()
		seerrRow?.let { rowsAdapter.add(it) }
		val adapters = mutableListOf<ItemRowAdapter>()
		for ((labelRes, baseItems) in searchResultGroups) {
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				rowsAdapter,
				QueryType.Search
			).apply {
				setRow(ListRow(HeaderItem(context.getString(labelRes)), this))
			}
			adapters.add(adapter)
		}
		for (adapter in adapters) adapter.Retrieve()
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item is SeerrTitle) { onSeerrTitleClick(item); return@OnItemViewClickedListener }
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		row as ListRow
		val adapter = row.adapter as ItemRowAdapter
		itemLauncher.launch(item as BaseRowItem?, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val baseItem = (item as? BaseRowItem)?.baseItem
		if (baseItem != null) {
			backgroundService.setBackground(baseItem)
		} else {
			backgroundService.clearBackgrounds()
		}
	}
}
