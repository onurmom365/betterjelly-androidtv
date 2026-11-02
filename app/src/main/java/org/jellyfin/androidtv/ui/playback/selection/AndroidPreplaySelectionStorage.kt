package org.jellyfin.androidtv.ui.playback.selection

import android.content.Context

/** Non-secret manual preferences, sandboxed in this fork and scoped by server/user UUIDs. */
class AndroidPreplaySelectionStorage(context: Context) : PreplaySelectionStorage {
	private val preferences = context.getSharedPreferences("preplay_tracks", Context.MODE_PRIVATE)
	override fun read(key: String) = preferences.getString(key, null)
	override fun write(key: String, value: String?) {
		preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
	}
}
