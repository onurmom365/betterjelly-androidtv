package org.jellyfin.androidtv.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

class SeerrSubmissionTests : FunSpec({
	val titleJson = """{"id":10,"name":"Test series","seasons":[{"seasonNumber":1,"episodeCount":12}]}"""
	val receiptJson = """{"id":42,"status":2,"type":"tv","is4k":false,"seasons":[{"seasonNumber":1,"status":2}],"media":{"tmdbId":10,"mediaType":"tv","status":3}}"""
	val snapshot = SeerrUiState(user = SeerrUser(4, permissions = 32), title = SeerrTitle(10, "tv"), seasons = setOf(1))
	val library = SeerrLibrary { Result.success(emptyList()) }
	fun factory(handler: (Request) -> Pair<Int, String>) = Call.Factory { request ->
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
	fun api(calls: MutableList<String>, responseOverride: (Request) -> Pair<Int, String>? = { null }) =
		SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			calls += "${request.method} ${request.url.encodedPath}"
			responseOverride(request) ?: when (request.url.encodedPath) {
				"/api/v1/auth/me" -> 200 to """{"id":4,"permissions":32}"""
				"/api/v1/tv/10" -> 200 to titleJson
				"/api/v1/service/sonarr" -> 200 to """[{"id":0,"isDefault":true,"is4k":false}]"""
				"/api/v1/request" -> {
					request.method shouldBe "POST"
					val body = seerrJson.decodeFromString<SeerrRequestBody>(Buffer().also { request.body?.writeTo(it) }.readUtf8())
					body.mediaId shouldBe 10
					body.seasons shouldBe listOf(1)
					body.is4k shouldBe false
					201 to receiptJson
				}
				"/api/v1/request/42" -> 200 to receiptJson
				else -> error("Unexpected test route")
			}
		})

	test("season request succeeds only after one POST and a matching saved-record GET") {
		val calls = mutableListOf<String>()
		val steps = mutableListOf<SeerrSubmissionStep>()
		submitSeerrRequest(api(calls), library, snapshot) { steps += it }.id shouldBe 42
		calls shouldBe listOf("GET /api/v1/auth/me", "GET /api/v1/tv/10", "GET /api/v1/service/sonarr", "POST /api/v1/request", "GET /api/v1/request/42")
		steps.last() shouldBe SeerrSubmissionStep.CONFIRM
	}
	test("library failure identifies the library step and sends no media request") {
		val calls = mutableListOf<String>()
		val failure = expectFailure<SeerrSubmissionFailure> {
			submitSeerrRequest(api(calls), SeerrLibrary { Result.failure(IOException("sensitive detail")) }, snapshot)
		}
		failure.step shouldBe SeerrSubmissionStep.LIBRARY
		failure.userMessage.contains("No request was sent") shouldBe true
		failure.userMessage.contains("sensitive") shouldBe false
		calls.any { it.startsWith("POST") } shouldBe false
	}
	test("failed submission keeps its dialog selections and error visible and blocks another POST until status refresh") {
		val before = snapshot.copy(requestOpen = true, reviewing = true, profileId = 7, rootFolder = "/shows", tags = setOf(3))
		val after = before.submissionFailed(SeerrSubmissionFailure(SeerrSubmissionStep.SEND, SeerrFailure(500)))
		after.requestOpen shouldBe true
		after.reviewing shouldBe true
		after.seasons shouldBe setOf(1)
		after.profileId shouldBe 7
		after.rootFolder shouldBe "/shows"
		after.tags shouldBe setOf(3)
		after.needsRefresh shouldBe true
		after.requestBody() shouldBe null
		after.message?.contains("Sending request") shouldBe true
	}
	test("changed season availability prevents POST and identifies validation step") {
		val calls = mutableListOf<String>()
		val client = api(calls) { request ->
			if (request.url.encodedPath == "/api/v1/tv/10") 200 to """{"id":10,"seasons":[{"seasonNumber":1,"episodeCount":12}],"mediaInfo":{"seasons":[{"seasonNumber":1,"status":5}]}}""" else null
		}
		expectFailure<SeerrSubmissionFailure> { submitSeerrRequest(client, library, snapshot) }.step shouldBe SeerrSubmissionStep.OPTIONS
		calls.any { it.startsWith("POST") } shouldBe false
	}
	test("server rejection identifies sending stage and never attempts confirmation or retries") {
		val calls = mutableListOf<String>()
		val client = api(calls) { request -> if (request.method == "POST") 403 to "private server text" else null }
		val failure = expectFailure<SeerrSubmissionFailure> { submitSeerrRequest(client, library, snapshot) }
		failure.step shouldBe SeerrSubmissionStep.SEND
		failure.userMessage.contains("permission denied") shouldBe true
		calls.count { it.startsWith("POST") } shouldBe 1
		calls.any { it.endsWith("/42") } shouldBe false
	}
	test("missing saved record after a valid POST remains unconfirmed and never resubmits") {
		val calls = mutableListOf<String>()
		val client = api(calls) { request -> if (request.url.encodedPath.endsWith("/42")) 404 to "private server text" else null }
		val failure = expectFailure<SeerrSubmissionFailure> { submitSeerrRequest(client, library, snapshot) }
		failure.step shouldBe SeerrSubmissionStep.CONFIRM
		failure.needsStatusCheck shouldBe true
		failure.userMessage.contains("#42") shouldBe true
		failure.userMessage.contains("private") shouldBe false
		calls.count { it.startsWith("POST") } shouldBe 1
	}
	test("a wrong title lane unknown status or missing season cannot confirm a saved request") {
		for (bad in listOf(
			receiptJson.replace("\"tmdbId\":10", "\"tmdbId\":11"),
			receiptJson.replace("\"is4k\":false", "\"is4k\":true"),
			receiptJson.replace("\"status\":2", "\"status\":0"),
			receiptJson.replace("\"seasonNumber\":1", "\"seasonNumber\":2"),
		)) {
			val calls = mutableListOf<String>()
			val client = api(calls) { request -> if (request.url.encodedPath.endsWith("/42")) 200 to bad else null }
			expectFailure<SeerrSubmissionFailure> { submitSeerrRequest(client, library, snapshot) }.step shouldBe SeerrSubmissionStep.CONFIRM
			calls.count { it.startsWith("POST") } shouldBe 1
		}
	}
	test("a request that fails during Seerr processing is still confirmed as saved without resubmitting") {
		for (failedAtPost in listOf(false, true)) {
			val calls = mutableListOf<String>()
			val failed = receiptJson.replace("\"status\":2", "\"status\":4")
			val client = api(calls) { request -> when {
				request.url.encodedPath.endsWith("/42") -> 200 to failed
				failedAtPost && request.method == "POST" -> 201 to failed
				else -> null
			} }
			val receipt = submitSeerrRequest(client, library, snapshot)
			receipt.id shouldBe 42
			receipt.statusLabel shouldBe "Failed"
			receipt.savedMessage.contains("saved in Seerr") shouldBe true
			receipt.savedMessage.contains("Sonarr") shouldBe true
			calls.count { it.startsWith("POST") } shouldBe 1
		}
	}
	test("title status shows a failed handoff instead of stale processing while preserving available and active statuses") {
		val failed = SeerrRequest(42, status = 4)
		val title = SeerrTitle(10, "tv", mediaInfo = SeerrMediaInfo(status = 3, requests = listOf(failed)))
		title.displayStatus() shouldBe "Failed"
		title.copy(mediaInfo = title.mediaInfo!!.copy(status = 5)).displayStatus() shouldBe "Available"
		title.copy(mediaInfo = title.mediaInfo!!.copy(requests = listOf(failed, SeerrRequest(43, status = 2)))).displayStatus() shouldBe "Requested"
	}
	test("an incomplete POST receipt can only confirm through a matching saved-record read") {
		val calls = mutableListOf<String>()
		val client = api(calls) { request -> if (request.method == "POST") 201 to """{"id":42}""" else null }
		val receipt = submitSeerrRequest(client, library, snapshot)
		receipt.id shouldBe 42
		receipt.confirmationRecovered shouldBe true
		calls.count { it.startsWith("POST") } shouldBe 1
	}
	test("lost submission response is reconciled against the signed-in user's saved season selection with one POST") {
		val calls = mutableListOf<String>()
		var sent = false
		val owned = receiptJson.replace("\"id\":42", "\"id\":42,\"requestedBy\":{\"id\":4}")
		val client = api(calls) { request -> when {
			request.method == "POST" -> { sent = true; throw IOException("response lost") }
			sent && request.url.encodedPath.endsWith("/tv/10") -> 200 to titleJson.dropLast(1) + ",\"mediaInfo\":{\"tmdbId\":10,\"mediaType\":\"tv\",\"requests\":[$owned]}}"
			else -> null
		} }
		val receipt = submitSeerrRequest(client, library, snapshot)
		receipt.confirmationRecovered shouldBe true
		receipt.id shouldBe 42
		calls.count { it.startsWith("POST") } shouldBe 1
	}
	test("an already saved request found during preflight is returned without another POST") {
		val calls = mutableListOf<String>()
		val owned = receiptJson.replace("\"id\":42", "\"id\":42,\"requestedBy\":{\"id\":4}")
		val client = api(calls) { request -> if (request.url.encodedPath.endsWith("/tv/10"))
			200 to titleJson.dropLast(1) + ",\"mediaInfo\":{\"tmdbId\":10,\"mediaType\":\"tv\",\"requests\":[$owned]}}" else null }
		submitSeerrRequest(client, library, snapshot).confirmationRecovered shouldBe true
		calls.any { it.startsWith("POST") } shouldBe false
	}
	test("reconciliation cannot claim another user's request or an unrequested season") {
		val body = SeerrRequestBody("tv", 10, listOf(1))
		val other = SeerrRequest(42, 2, seasons = listOf(SeerrSeasonRequest(1, 2)), type = "tv", requestedBy = SeerrRequestOwner(9))
		val title = SeerrTitle(10, "tv", mediaInfo = SeerrMediaInfo(tmdbId = 10, mediaType = "tv", requests = listOf(other)))
		title.existingRequest(body, 4) shouldBe null
		title.existingRequest(body.copy(seasons = listOf(2)), 9) shouldBe null
		title.existingRequest(body, 9)?.id shouldBe 42
		title.copy(id = 11).existingRequest(body, 9) shouldBe null
		title.copy(mediaInfo = title.mediaInfo!!.copy(tmdbId = 11)).existingRequest(body, 9) shouldBe null
	}
	test("catalog null metadata and unrelated people do not discard valid title results") {
		val client = SeerrApi(MemorySeerrStore(), callFactory = factory { 200 to """{"results":[{"id":1,"mediaType":"person","name":null},{"id":10,"mediaType":"tv","name":"Test series","overview":null,"voteAverage":null,"firstAirDate":null,"posterPath":null}]}""" })
		val result = loadSeerrSearch(client, "Test")
		result.titles.single().id shouldBe 10
		result.titles.single().overview shouldBe ""
		result.message shouldBe null
	}
	test("a discovery category failure preserves the other category without declaring Seerr unavailable") {
		val client = SeerrApi(MemorySeerrStore(), callFactory = factory { request ->
			if (request.url.encodedPath.endsWith("/movies")) 500 to "private server text"
			else 200 to """{"results":[{"id":10,"name":"Test series"}]}"""
		})
		val result = loadSeerrSearch(client, "")
		result.titles.single().mediaType shouldBe "tv"
		result.message?.contains("can still search") shouldBe true
		result.message?.contains("unavailable") shouldBe false
	}
	test("invalid required catalog IDs stay strict and data errors are distinct from connection failures") {
		val client = SeerrApi(MemorySeerrStore(), callFactory = factory { 200 to """{"results":[{"id":null,"mediaType":"tv"}]}""" })
		val failure = expectFailure<SeerrFailure> { client.titles("Test", "movie", 1) }
		failure.invalidResponse shouldBe true
		failure.userMessage.contains("responded") shouldBe true
	}
})
