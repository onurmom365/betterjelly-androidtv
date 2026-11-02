package org.jellyfin.androidtv.ui.update

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.jellyfin.androidtv.BuildConfig

/** Daily work caches availability; UI observes it only while Home is visible, even if Home stays open all day. */
internal fun attachHomeUpdateNotice(fragment: Fragment) {
	val context = fragment.requireContext().applicationContext
	val preferences = context.getSharedPreferences("fork-updates", 0)
	val owner = fragment.viewLifecycleOwner
	var notice: AlertDialog? = null
	fun showCachedNotice() {
		if (!fragment.isAdded || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || notice?.isShowing == true) return
		val code = preferences.getInt("availableCode", 0)
		val version = preferences.getString("availableVersion", null) ?: return
		if (!Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}").matches(version)) return
		if (updateNoticeEligible(preferences.getBoolean("automatic", true), fragment.isResumed && fragment.isVisible,
			code, BuildConfig.VERSION_CODE, preferences.getInt("shownCode", 0), preferences.getInt("availableMinSdk", 0), android.os.Build.VERSION.SDK_INT) &&
			!fragment.parentFragmentManager.isStateSaved) {
			notice = AlertDialog.Builder(fragment.requireContext()).setTitle("Jellyfin update available")
				.setMessage("Version $version is available. Review and download it without losing your settings.")
				.setPositiveButton("View update") { _, _ -> fragment.startActivity(Intent(fragment.requireContext(), UpdateActivity::class.java)) }
				.setNegativeButton("Later", null).create().also { it.show(); it.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
			preferences.edit().putInt("shownCode", code).apply()
		}
	}
	val handler = Handler(Looper.getMainLooper())
	val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key == "lastSuccessfulCheck" || key == "automatic") handler.post { showCachedNotice() }
	}
	preferences.registerOnSharedPreferenceChangeListener(listener)
	val observer = LifecycleEventObserver { _, event ->
		when (event) {
			Lifecycle.Event.ON_RESUME -> showCachedNotice()
			Lifecycle.Event.ON_PAUSE -> { notice?.dismiss(); notice = null }
			Lifecycle.Event.ON_DESTROY -> { notice?.dismiss(); notice = null; preferences.unregisterOnSharedPreferenceChangeListener(listener) }
			else -> Unit
		}
	}
	owner.lifecycle.addObserver(observer)
}
