package org.jellyfin.androidtv.ui.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

@Suppress("DEPRECATION")
private fun PackageInfo.updateArchive(): UpdateArchive {
	val signers = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
	return UpdateArchive(packageName, versionName, if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
		signers?.map { sha256(it.toByteArray()) }.orEmpty(),
		(applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
		if (Build.VERSION.SDK_INT >= 24) applicationInfo?.minSdkVersion else null)
}

/** Re-read the actual installed and downloaded packages immediately before granting an installer URI. */
@Suppress("DEPRECATION")
internal fun verifyInstallFile(context: Context, file: File, release: ForkRelease) {
	val directory = File(context.cacheDir, "fork-updates").canonicalFile
	updateRequire(file.canonicalFile.parentFile == directory && file.name.endsWith(".apk"))
	updateRequire(verifyDownloadedUpdate(file, release.asset), "The saved update failed verification. Download it again before installing.")
	val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
	val installed = context.packageManager.getPackageInfo(context.packageName, flags).updateArchive()
	val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.updateArchive()
		?: throw UpdateFailure("Android could not verify this APK. Nothing was installed.")
	verifyUpdateArchive(release, archive, installed, Build.VERSION.SDK_INT)
}

internal fun installPermissionGranted(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

internal fun updateInstallIntent(context: Context, file: File): Intent {
	val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
	return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Jellyfin update", uri) }
}
