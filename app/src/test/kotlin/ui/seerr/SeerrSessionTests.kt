package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.crypto.KeyGenerator

internal class MemorySeerrStore : SeerrSessionStore {
	var values = emptyList<String>()
	override fun read() = values
	override fun write(cookies: List<String>) { values = cookies }
	override fun clear() { values = emptyList() }
}

class SeerrSessionTests : FunSpec({
	val origin = SEERR_ORIGIN.toHttpUrl()
	val scope = "user-a/server-a/$SEERR_ORIGIN".toByteArray()
	fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

	test("session encrypts with fresh random IV and decrypts correctly") {
		val secretKey = key()
		val plain = "connect.sid=unit-test-cookie".toByteArray()
		val first = SeerrSessionCipher.encrypt(secretKey, plain, scope)
		val second = SeerrSessionCipher.encrypt(secretKey, plain, scope)
		first.contentEquals(second) shouldBe false
		first.toString(Charsets.UTF_8).contains("unit-test-cookie") shouldBe false
		SeerrSessionCipher.decrypt(secretKey, first, scope).contentEquals(plain) shouldBe true
	}
	test("tampering wrong user origin or key fail authentication") {
		val secretKey = key()
		val encrypted = SeerrSessionCipher.encrypt(secretKey, byteArrayOf(1, 2), scope)
		expectFailure<Throwable> { SeerrSessionCipher.decrypt(secretKey, encrypted, "other-user".toByteArray()) }
		expectFailure<Throwable> { SeerrSessionCipher.decrypt(key(), encrypted, scope) }
		encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
		expectFailure<Throwable> { SeerrSessionCipher.decrypt(secretKey, encrypted, scope) }
	}
	test("truncated or unknown-format encrypted sessions are rejected") {
		expectFailure<Throwable> { SeerrSessionCipher.decrypt(key(), byteArrayOf(1), scope) }
		expectFailure<Throwable> { SeerrSessionCipher.decrypt(key(), ByteArray(30), scope) }
	}
	test("cookies never leave the exact Seerr origin including port and scheme") {
		val store = MemorySeerrStore()
		val jar = SeerrCookieJar(origin, store)
		val cookie = Cookie.Builder().name("connect.sid").value("unit-test-cookie").hostOnlyDomain(origin.host).path("/").build()
		jar.saveFromResponse(origin, listOf(cookie))
		jar.loadForRequest("$SEERR_ORIGIN/api/v1/auth/me".toHttpUrl()).size shouldBe 1
		for (url in listOf("http://192.168.1.199:8096", "https://192.168.1.199:5055", "https://plex.tv", "https://image.tmdb.org"))
			jar.loadForRequest(url.toHttpUrl()) shouldBe emptyList()
	}
	test("foreign response cookies and foreign domains cannot replace a session") {
		val store = MemorySeerrStore()
		val jar = SeerrCookieJar(origin, store)
		val cookie = Cookie.Builder().name("connect.sid").value("unit-test-cookie").domain("example.invalid").build()
		jar.saveFromResponse(origin, listOf(cookie))
		jar.loadForRequest(origin) shouldBe emptyList()
		jar.saveFromResponse("https://plex.tv".toHttpUrl(), listOf(cookie))
		store.values shouldBe emptyList()
	}
	test("expired cookies are dropped and disconnect clears persisted session") {
		val store = MemorySeerrStore()
		val jar = SeerrCookieJar(origin, store)
		val cookie = Cookie.Builder().name("connect.sid").value("unit-test-cookie").hostOnlyDomain(origin.host).expiresAt(1).build()
		jar.saveFromResponse(origin, listOf(cookie))
		jar.loadForRequest(origin) shouldBe emptyList()
		jar.clear()
		store.values shouldBe emptyList()
	}
})
