package org.jellyfin.androidtv.ui.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

private const val DAILY_UPDATE_WORK = "fork-daily-update-check"

/** Device-wide schedule survives app/account changes. No APK download, installer or server session is used. */
internal fun configureUpdateSchedule(context: Context) {
	val manager = WorkManager.getInstance(context)
	if (!context.getSharedPreferences("fork-updates", 0).getBoolean("automatic", true)) {
		manager.cancelUniqueWork(DAILY_UPDATE_WORK)
		return
	}
	manager.enqueueUniquePeriodicWork(DAILY_UPDATE_WORK, ExistingPeriodicWorkPolicy.KEEP,
		PeriodicWorkRequestBuilder<DailyUpdateWorker>(CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
}

class DailyUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		val preferences = applicationContext.getSharedPreferences("fork-updates", 0)
		if (!preferences.getBoolean("automatic", true)) return Result.success()
		val api = ForkUpdateClient()
		try {
			val release = api.latest()
			if (preferences.getBoolean("automatic", true)) preferences.edit()
				.putString("availableVersion", release.version).putInt("availableCode", release.code)
				.putInt("availableMinSdk", release.minSdk).putLong("lastSuccessfulCheck", System.currentTimeMillis()).apply()
		} catch (error: CancellationException) { throw error }
		catch (_: Exception) { /* Keep browsing/playback independent; manual Check for updates remains available. */ }
		finally { api.cancel() }
		return Result.success()
	}
}
