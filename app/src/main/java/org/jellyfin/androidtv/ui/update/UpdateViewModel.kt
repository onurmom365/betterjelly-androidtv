package org.jellyfin.androidtv.ui.update

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

internal data class UpdateUiState(val busy: Boolean = false, val release: ForkRelease? = null, val ready: Boolean = false,
	val progress: Int? = null, val message: String = "", val automatic: Boolean = true)

internal fun updateError(error: Exception): String = when (error) {
	is UpdateFailure -> error.userMessage
	is SocketTimeoutException -> "The update connection timed out. Please try again."
	is IOException -> "Could not connect or save the update. Check your connection and free space, then retry."
	else -> "The update could not be completed. Nothing was installed. Please try again."
}

internal class UpdateViewModel(application: Application) : AndroidViewModel(application) {
	private val api = ForkUpdateClient()
	private val preferences = application.getSharedPreferences("fork-updates", 0)
	private val _state = MutableStateFlow(UpdateUiState(automatic = preferences.getBoolean("automatic", true)))
	val state = _state.asStateFlow()
	private var job: Job? = null
	private var file: File? = null

	init { check() }

	fun check() {
		if (_state.value.busy) return
		file = null
		_state.value = UpdateUiState(busy = true, message = "Checking GitHub for updates…", automatic = _state.value.automatic)
		job = viewModelScope.launch {
			try {
				val release = api.latest()
				val newer = release.code > BuildConfig.VERSION_CODE
				_state.update { it.copy(busy = false, release = release.takeIf { newer }, message = when {
					!newer -> "You're up to date. Installed version: ${BuildConfig.VERSION_NAME}."
					release.minSdk > Build.VERSION.SDK_INT -> "Version ${release.version} requires a newer Android version."
					else -> "Version ${release.version} is available. Installed: ${BuildConfig.VERSION_NAME}."
				}) }
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) { _state.update { it.copy(busy = false, message = updateError(error)) } }
		}
	}

	fun download() {
		val release = _state.value.release ?: return
		if (_state.value.busy || release.minSdk > Build.VERSION.SDK_INT) return
		_state.update { it.copy(busy = true, ready = false, progress = 0, message = "Downloading ${release.version}…") }
		job = viewModelScope.launch {
			try {
				val operation = coroutineContext[Job]
				val saved = api.download(release, File(getApplication<Application>().cacheDir, "fork-updates")) { percent ->
					_state.update { if (operation?.isActive == true && it.busy) it.copy(progress = percent) else it }
				}
				withContext(Dispatchers.IO) { verifyInstallFile(getApplication(), saved, release) }
				file = saved
				_state.update { it.copy(busy = false, progress = null, ready = true,
					message = "Update verified. Choose Install update to open Android's installer. Your settings will be kept.") }
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) { _state.update { it.copy(busy = false, ready = false, progress = null, message = updateError(error)) } }
		}
	}

	fun install(launch: (File) -> Unit) {
		val release = _state.value.release ?: return
		val saved = file ?: return
		if (_state.value.busy || !_state.value.ready) return
		_state.update { it.copy(busy = true, message = "Verifying update before installation…") }
		job = viewModelScope.launch {
			try {
				withContext(Dispatchers.IO) { verifyInstallFile(getApplication(), saved, release) }
				_state.update { it.copy(busy = false, message = "Confirm Install in the system installer. If you cancel, you can try again here.") }
				launch(saved)
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) { file = null; _state.update { it.copy(busy = false, ready = false, message = updateError(error)) } }
		}
	}

	fun toggleAutomatic() {
		val enabled = !_state.value.automatic
		preferences.edit().putBoolean("automatic", enabled).apply()
		configureUpdateSchedule(getApplication())
		_state.update { it.copy(automatic = enabled) }
	}
	fun message(value: String) { _state.update { it.copy(message = value) } }
	fun cancel() {
		job?.cancel()
		api.cancel()
		_state.update { it.copy(busy = false, progress = null, message = "Update check/download cancelled. Playback and settings are unchanged.") }
	}
	override fun onCleared() { api.cancel(); super.onCleared() }
}
