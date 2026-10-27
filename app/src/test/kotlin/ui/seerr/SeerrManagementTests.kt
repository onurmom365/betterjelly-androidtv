package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

internal fun seerrFakeCalls(handler: (Request) -> Pair<Int, String>) = Call.Factory { request ->
	mockk<Call> {
		every { cancel() } returns Unit
		every { enqueue(any()) } answers {
			val callback = firstArg<Callback>()
			try {
				val (code, body) = handler(request)
				callback.onResponse(this@mockk, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
					.body(body.toResponseBody("application/json".toMediaType())).build())
			} catch (error: IOException) { callback.onFailure(this@mockk, error) }
		}
	}
}

private class FakeSeerrRequests {
	var permissions = 16L
	var record = SeerrRequest(42, status = 4, seasons = listOf(SeerrSeasonRequest(1, 2)),
		media = SeerrMediaInfo(tmdbId = 10, mediaType = "tv"), type = "tv", profileName = "Test HD", requestedBy = SeerrRequestOwner(4))
	var exists = true
	var dropResponse = false
	var stillFailed = false
	var ignoreDelete = false
	var unreadableAfterWrite = false
	val calls = mutableListOf<String>()
	var writes = 0
	fun client() = SeerrApi(MemorySeerrStore(), callFactory = seerrFakeCalls { request ->
		calls += "${request.method} ${request.url.encodedPath}"
		when {
			request.url.encodedPath.endsWith("/auth/me") -> 200 to """{"id":4,"permissions":$permissions}"""
			request.method == "GET" -> {
				if (unreadableAfterWrite && writes > 0) throw IOException("private read error")
				if (exists) 200 to seerrJson.encodeToString(record) else 404 to "{}"
			}
			request.method == "POST" -> {
				request.url.encodedPath shouldBe "/api/v1/request/42/retry"
				Buffer().also { request.body?.writeTo(it) }.readUtf8() shouldBe "{}"
				writes++
				if (!stillFailed) record = record.copy(status = 2)
				if (dropResponse) throw IOException("private lost response")
				200 to seerrJson.encodeToString(record)
			}
			request.method == "DELETE" -> {
				request.url.encodedPath shouldBe "/api/v1/request/42"
				request.url.querySize shouldBe 0
				writes++
				if (!ignoreDelete) exists = false
				if (dropResponse) throw IOException("private lost response")
				204 to ""
			}
			else -> error("Unexpected fake route")
		}
	})
	fun prompt(action: SeerrRequestAction) = SeerrRequestPrompt(action, record, "Test series")
}

class SeerrManagementTests : FunSpec({
	test("retry uses the existing failed request and confirms it without making a new media request") {
		val server = FakeSeerrRequests()
		val prompt = server.prompt(SeerrRequestAction.RETRY)
		val result = manageSeerrRequest(server.client(), prompt) as SeerrManagedResult.Retried
		result.request.id shouldBe 42
		result.request.status shouldBe 2
		result.request.seasons shouldBe prompt.request.seasons
		result.request.profileName shouldBe "Test HD"
		server.calls shouldBe listOf("GET /api/v1/auth/me", "GET /api/v1/request/42", "POST /api/v1/request/42/retry", "GET /api/v1/request/42")
		server.writes shouldBe 1
	}
	test("a retry response lost after saving is recovered with a read and one mutation") {
		val server = FakeSeerrRequests().apply { dropResponse = true }
		val result = manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.RETRY)) as SeerrManagedResult.Retried
		result.request.status shouldBe 2
		server.writes shouldBe 1
	}
	test("a retried request that Seerr marks failed is reported as failed and is not retried automatically") {
		val server = FakeSeerrRequests().apply { stillFailed = true }
		val result = manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.RETRY)) as SeerrManagedResult.Retried
		result.request.statusLabel shouldBe "Failed"
		server.writes shouldBe 1
	}
	test("nonfailed or unauthorized requests cannot reach the retry mutation") {
		for (server in listOf(FakeSeerrRequests().apply { record = record.copy(status = 2) }, FakeSeerrRequests().apply { permissions = 8192 })) {
			val failure = expectFailure<SeerrManagementFailure> { manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.RETRY)) }
			failure.uncertain shouldBe false
			server.writes shouldBe 0
		}
	}
	test("delete removes only the request endpoint and requires saved-record absence") {
		val server = FakeSeerrRequests()
		manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.DELETE)) shouldBe SeerrManagedResult.Deleted
		server.calls shouldBe listOf("GET /api/v1/auth/me", "GET /api/v1/request/42", "DELETE /api/v1/request/42", "GET /api/v1/request/42")
		server.writes shouldBe 1
	}
	test("a deletion with a lost response is confirmed through absence without another DELETE") {
		val server = FakeSeerrRequests().apply { dropResponse = true }
		manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.DELETE)) shouldBe SeerrManagedResult.Deleted
		server.writes shouldBe 1
	}
	test("a deletion that cannot be confirmed requires a status check and leaks no response detail") {
		val server = FakeSeerrRequests().apply { unreadableAfterWrite = true }
		val failure = expectFailure<SeerrManagementFailure> { manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.DELETE)) }
		failure.uncertain shouldBe true
		failure.userMessage.contains("Check status") shouldBe true
		failure.userMessage.contains("private") shouldBe false
		server.writes shouldBe 1
	}
	test("an acknowledged delete that leaves its record present remains unconfirmed") {
		val server = FakeSeerrRequests().apply { ignoreDelete = true }
		expectFailure<SeerrManagementFailure> { manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.DELETE)) }.uncertain shouldBe true
		server.writes shouldBe 1
	}
	test("basic users may delete their own pending request but not failed or other users' requests") {
		val basic = SeerrUser(4, permissions = 32)
		val failed = FakeSeerrRequests().record
		basic.canRetry(failed) shouldBe false
		basic.canDelete(failed) shouldBe false
		basic.canDelete(failed.copy(status = 1)) shouldBe true
		basic.canDelete(failed.copy(status = 1, requestedBy = SeerrRequestOwner(9))) shouldBe false
		SeerrUser(4, permissions = 2).canRetry(failed) shouldBe true
		val server = FakeSeerrRequests().apply { permissions = 32; record = record.copy(status = 1) }
		manageSeerrRequest(server.client(), server.prompt(SeerrRequestAction.DELETE)) shouldBe SeerrManagedResult.Deleted
	}
})
