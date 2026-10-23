package org.jellyfin.androidtv.ui.seerr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
	companion object { private val locks = ConcurrentHashMap<String, Any>() }
	private val scopeBytes = scope.toByteArray(Charsets.UTF_8)
	private val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeBytes).joinToString("") { "%02x".format(it) }
	// Search and details create separate store instances for the same file. AtomicFile needs a shared lock.
	private val lock = locks.getOrPut(scopeHash) { Any() }
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

	override fun read(): List<String> = synchronized(lock) {
		try {
			// openRead inside readFully also recovers AtomicFile's backup after an interrupted write.
			val bytes = file.readFully()
			SeerrSessionCipher.decrypt(key(), bytes, scopeBytes).toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
		} catch (_: FileNotFoundException) { emptyList() }
		catch (_: Exception) {
			// Preserve encrypted data on a temporary I/O/keystore failure; retry or explicit sign-in can recover.
			throw IOException("Secure session could not be read")
		}
	}

	override fun write(cookies: List<String>): Unit = synchronized(lock) {
		if (cookies.isEmpty()) { clear(); return@synchronized }
		val encrypted = SeerrSessionCipher.encrypt(key(), cookies.joinToString("\n").toByteArray(Charsets.UTF_8), scopeBytes)
		val stream = file.startWrite()
		try { stream.write(encrypted); file.finishWrite(stream) }
		catch (error: Exception) { file.failWrite(stream); throw error }
	}

	override fun clear() = synchronized(lock) { file.delete() }
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
