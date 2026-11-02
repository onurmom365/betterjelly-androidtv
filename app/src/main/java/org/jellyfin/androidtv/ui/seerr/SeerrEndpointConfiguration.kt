package org.jellyfin.androidtv.ui.seerr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface SeerrEndpointStore {
	fun read(account: String): String?
	fun write(account: String, origin: String)
}

internal class SeerrEndpointConfiguration(private val store: SeerrEndpointStore) {
	private val _revision = MutableStateFlow(0L)
	val revision = _revision.asStateFlow()
	private val selected = mutableMapOf<String, SeerrEndpoint>()

	@Synchronized fun endpoint(account: String?): SeerrEndpoint = account?.let { key ->
		selected.getOrPut(key) { runCatching { store.read(key)?.let(SeerrEndpoint::parse) }.getOrNull() ?: defaultSeerrEndpoint }
	} ?: defaultSeerrEndpoint

	@Synchronized fun save(account: String, value: String) {
		val updated = SeerrEndpoint.parse(value)
		if (endpoint(account) == updated) return
		store.write(account, updated.origin)
		selected[account] = updated
		_revision.value++
	}
}

private object AndroidSeerrEndpoints {
	private var current: SeerrEndpointConfiguration? = null
	@Synchronized fun get(context: Context): SeerrEndpointConfiguration {
		current?.let { return it }
		val preferences = context.applicationContext.getSharedPreferences("seerr-addresses", Context.MODE_PRIVATE)
		return SeerrEndpointConfiguration(object : SeerrEndpointStore {
			override fun read(account: String) = preferences.getString(account, null)
			override fun write(account: String, origin: String) {
				check(preferences.edit().putString(account, origin).commit()) { "The Seerr address could not be saved. Please retry." }
			}
		}).also { current = it }
	}
}

internal fun seerrEndpoints(context: Context) = AndroidSeerrEndpoints.get(context)
