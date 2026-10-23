package org.jellyfin.androidtv.ui.seerr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SeerrSessionStore {
	fun read(): List<String>
	fun write(cookies: List<String>)
	fun clear()
}

/** AES-GCM session cookies only; no passwords or Plex tokens. Bound to the Jellyfin user and Seerr origin. */
class AndroidSeerrSessionStore(context: Context, scope: String) : SeerrSessionStore {
	private val scopeBytes = scope.toByteArray(Charsets.UTF_8)
	private val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeBytes).joinToString("") { "%02x".format(it) }
	private val file = AtomicFile(File(context.noBackupFilesDir, "seerr-$scopeHash.session"))
	private val alias = "seerr-session-$scopeHash"

	private fun key(): SecretKey {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getKey(alias, null) as? SecretKey)?.let { return it }
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
			init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256).build())
		}.generateKey()
	}

	@Synchronized override fun read(): List<String> = try {
		if (!file.baseFile.exists()) emptyList()
		else SeerrSessionCipher.decrypt(key(), file.readFully(), scopeBytes).toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
	} catch (_: Exception) {
		// A lost key or damaged file requires sign-in; never fall back to plaintext.
		file.delete()
		emptyList()
	}

	@Synchronized override fun write(cookies: List<String>) {
		if (cookies.isEmpty()) { clear(); return }
		val encrypted = SeerrSessionCipher.encrypt(key(), cookies.joinToString("\n").toByteArray(Charsets.UTF_8), scopeBytes)
		val stream = file.startWrite()
		try { stream.write(encrypted); file.finishWrite(stream) }
		catch (error: Exception) { file.failWrite(stream); throw error }
	}

	@Synchronized override fun clear() = file.delete()
}

internal object SeerrSessionCipher {
	fun encrypt(key: SecretKey, plain: ByteArray, scope: ByteArray): ByteArray {
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, key)
		cipher.updateAAD(scope)
		return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
	}
	fun decrypt(key: SecretKey, bytes: ByteArray, scope: ByteArray): ByteArray {
		require(bytes.size >= 29 && bytes[0] == 1.toByte())
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
		cipher.updateAAD(scope)
		return cipher.doFinal(bytes, 13, bytes.size - 13)
	}
}
