package org.jellyfin.androidtv.ui.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Retains navigation state until its account/address changes; old jobs and models are then cleared. */
class SeerrScopedModels : ViewModel(), ViewModelStoreOwner {
	override val viewModelStore = ViewModelStore()
	private var scope: String? = null

	fun provider(key: String, factory: ViewModelProvider.Factory): ViewModelProvider {
		if (scope != key) {
			viewModelStore.clear()
			scope = key
		}
		return ViewModelProvider(this, factory)
	}

	override fun onCleared() { viewModelStore.clear() }
}
