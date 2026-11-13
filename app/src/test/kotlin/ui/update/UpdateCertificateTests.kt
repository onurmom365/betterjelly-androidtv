package org.jellyfin.androidtv.ui.update

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

// PUBLIC certificate extracted from the verified 1.0.3 release APK; contains no private key.
private val releaseCertificate = object {}.javaClass.getResourceAsStream("/update-signer.der")!!.use { it.readBytes() }
private fun signer(bytes: ByteArray = releaseCertificate) = mockk<Signature> { every { toByteArray() } returns bytes }

@Suppress("DEPRECATION")
private fun packageInfo(sdk: Int, modern: Array<Signature>?, legacy: Array<Signature>?, downloaded: Boolean = false) = mockk<PackageInfo>().apply {
	packageName = UPDATE_PACKAGE
	versionName = if (downloaded) "1.0.4" else "1.0.2"
	versionCode = if (downloaded) 1000499 else 1000299
	signatures = legacy
	applicationInfo = mockk<ApplicationInfo>().apply { flags = 0; minSdkVersion = 21 }
	if (sdk >= 28) {
		signingInfo = modern?.let { certs -> mockk<SigningInfo> { every { apkContentsSigners } returns certs } }
		every { longVersionCode } returns versionCode.toLong()
	}
}

private val certificateRelease = ForkRelease("1.0.4", 1000499, 21,
	UpdateAsset("betterjelly-androidtv.apk", "https://github.com/$UPDATE_REPOSITORY/releases/download/v1.0.4/betterjelly-androidtv.apk", 1, "0".repeat(64)), "")
private fun rejected(block: () -> Unit): UpdateFailure {
	try { block() } catch (error: UpdateFailure) { return error }
	throw AssertionError("Expected update rejection")
}

@Suppress("DEPRECATION")
class UpdateCertificateTests : FunSpec({
	test("Android 9 and 10 archive collection regression uses both flags and passes the unchanged certificate pin") {
		sha256(releaseCertificate) shouldBe UPDATE_CERTIFICATE
		for (sdk in listOf(28, 29)) {
			val pm = mockk<PackageManager>()
			every { pm.getPackageArchiveInfo("update.apk", any<Int>()) } answers {
				// AOSP's old archive implementation collects certificates only for GET_SIGNATURES.
				val collected = secondArg<Int>() and PackageManager.GET_SIGNATURES != 0
				packageInfo(sdk, if (collected) arrayOf(signer()) else null, if (collected) arrayOf(signer()) else null, true)
			}
			val installed = packageInfo(sdk, arrayOf(signer()), null).updateArchive(sdk)
			val oldRead = pm.getPackageArchiveInfo("update.apk", PackageManager.GET_SIGNING_CERTIFICATES)!!.updateArchive(sdk)
			oldRead.certificates shouldBe emptyList()
			rejected { verifyUpdateArchive(certificateRelease, oldRead, installed, sdk) }
			val corrected = pm.getPackageArchiveInfo("update.apk", updatePackageFlags(sdk))!!.updateArchive(sdk)
			corrected.certificates shouldBe listOf(UPDATE_CERTIFICATE)
			verifyUpdateArchive(certificateRelease, corrected, installed, sdk)
		}
	}
	test("pre Android 9 uses only supported legacy flags and metadata") {
		for (sdk in listOf(21, 23, 24, 27)) {
			updatePackageFlags(sdk) shouldBe PackageManager.GET_SIGNATURES
			val installed = packageInfo(sdk, null, arrayOf(signer())).updateArchive(sdk)
			val archive = packageInfo(sdk, null, arrayOf(signer()), true).updateArchive(sdk)
			archive.code shouldBe 1000499L
			verifyUpdateArchive(certificateRelease, archive, installed, sdk)
		}
	}
	test("modern Android prefers current signers over a different legacy history") {
		for (sdk in listOf(28, 29, 30, 33, 35)) {
			updatePackageFlags(sdk) and PackageManager.GET_SIGNATURES shouldBe PackageManager.GET_SIGNATURES
			updatePackageFlags(sdk) and PackageManager.GET_SIGNING_CERTIFICATES shouldBe PackageManager.GET_SIGNING_CERTIFICATES
			val info = packageInfo(sdk, arrayOf(signer()), arrayOf(signer(byteArrayOf(1, 2))), true).updateArchive(sdk)
			info.certificates shouldBe listOf(UPDATE_CERTIFICATE)
		}
	}
	test("missing modern signing metadata may use a real legacy certificate") {
		for (modern in listOf(null, emptyArray<Signature>())) {
			val installed = packageInfo(28, arrayOf(signer()), null).updateArchive(28)
			val archive = packageInfo(28, modern, arrayOf(signer()), true).updateArchive(28)
			verifyUpdateArchive(certificateRelease, archive, installed, 28)
		}
	}
	test("a mismatched current signer cannot be bypassed with a pinned legacy signer") {
		val installed = packageInfo(28, arrayOf(signer()), null).updateArchive(28)
		val archive = packageInfo(28, arrayOf(signer(byteArrayOf(3, 4))), arrayOf(signer()), true).updateArchive(28)
		rejected { verifyUpdateArchive(certificateRelease, archive, installed, 28) }.userMessage.contains("does not match") shouldBe true
	}
	test("missing signing evidence fails closed with an accurate recovery message") {
		val installed = packageInfo(28, arrayOf(signer()), null).updateArchive(28)
		val archive = packageInfo(28, null, null, true).updateArchive(28)
		val error = rejected { verifyUpdateArchive(certificateRelease, archive, installed, 28) }
		error.userMessage.contains("could not read") shouldBe true
		error.userMessage.contains("8769319") shouldBe true
	}
	test("wrong legacy and multiple signers remain rejected") {
		val installed = packageInfo(28, arrayOf(signer()), null).updateArchive(28)
		for (archive in listOf(
			packageInfo(28, null, arrayOf(signer(byteArrayOf(5))), true),
			packageInfo(28, null, arrayOf(signer(), signer()), true),
			packageInfo(28, arrayOf(signer(), signer()), arrayOf(signer()), true),
		)) rejected { verifyUpdateArchive(certificateRelease, archive.updateArchive(28), installed, 28) }
	}
	test("the installed app must also have the pinned signer") {
		val installed = packageInfo(28, null, arrayOf(signer(byteArrayOf(6)))).updateArchive(28)
		val archive = packageInfo(28, arrayOf(signer()), arrayOf(signer()), true).updateArchive(28)
		rejected { verifyUpdateArchive(certificateRelease, archive, installed, 28) }
	}
})
