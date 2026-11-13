package org.jellyfin.androidtv.ui.update

import android.content.ClipData
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** Android 9's archive parser collects certificates only when GET_SIGNATURES is included. */
@Suppress("DEPRECATION")
internal fun updatePackageFlags(sdk: Int): Int = PackageManager.GET_SIGNATURES or
	if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0

// Production callers pass Build.VERSION.SDK_INT; the argument permits old-framework regression tests.
@SuppressLint("NewApi")
@Suppress("DEPRECATION")
internal fun PackageInfo.updateArchive(sdk: Int): UpdateArchive {
	val current = if (sdk >= 28) signingInfo?.apkContentsSigners else null
	// Prefer current signers even if they fail the pin. Legacy is only for absent modern metadata.
	val signers = current?.takeIf { it.isNotEmpty() } ?: signatures
	return UpdateArchive(packageName, versionName, if (sdk >= 28) longVersionCode else versionCode.toLong(),
		signers?.map { sha256(it.toByteArray()) }.orEmpty(),
		(applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
		if (sdk >= 24) applicationInfo?.minSdkVersion else null)
}

/** Re-read the actual installed and downloaded packages immediately before granting an installer URI. */
@Suppress("DEPRECATION")
internal fun verifyInstallFile(context: Context, file: File, release: ForkRelease) {
	val directory = File(context.cacheDir, "fork-updates").canonicalFile
	updateRequire(file.canonicalFile.parentFile == directory && file.name.endsWith(".apk"))
	updateRequire(verifyDownloadedUpdate(file, release.asset), "The saved update failed verification. Download it again before installing.")
	val sdk = Build.VERSION.SDK_INT
	val flags = updatePackageFlags(sdk)
	val installed = context.packageManager.getPackageInfo(context.packageName, flags).updateArchive(sdk)
	val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.updateArchive(sdk)
		?: throw UpdateFailure("Android could not verify this APK. Nothing was installed.")
	verifyUpdateArchive(release, archive, installed, sdk)
}

internal fun installPermissionGranted(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

internal fun updateInstallIntent(context: Context, file: File): Intent {
	val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
	return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Jellyfin update", uri) }
}
