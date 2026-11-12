package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.ParserException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

private fun zlibFailure() = ParserException.createForMalformedContainer("ContentCompAlgo 0 not supported", null).apply {
	stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.mkv.MatroskaExtractor", "integerElement", "MatroskaExtractor.java", 1016))
}

private val movie = UUID(0, 1)
private fun ContainerRemuxRecovery.direct(item: UUID = movie, source: String = "source", live: Boolean = false) =
	prepare(item, source, PlayMethod.DIRECT_PLAY, "mkv", live)
private fun ContainerRemuxRecovery.retry(item: UUID = movie, source: String = "source") =
	prepare(item, source, PlayMethod.TRANSCODE, "mkv", false)
private fun encode() = RemuxVideoTrack(0, 1, 3840, 2076, 14000000, 24f, true, true, CopyPermission.DENIED, CopyPermission.UNSPECIFIED)
private fun remux() = encode().copy(track = 0, selected = false, videoCopy = CopyPermission.UNSPECIFIED)

class ContainerRemuxRecoveryTests : FunSpec({
	test("only a same-source retry following the known MKV rejection enables recovery") {
		val state = ContainerRemuxRecovery()
		state.direct()
		state.enabled shouldBe false
		state.onError(zlibFailure())
		state.retry()
		state.enabled shouldBe true
	}
	test("a fresh direct start or a different item or media source clears recovery") {
		for (reset in listOf<(ContainerRemuxRecovery) -> Unit>(
			{ it.direct(); it.retry() }, { it.retry(item = UUID(0, 2)) }, { it.retry(source = "other") },
		)) {
			val state = ContainerRemuxRecovery()
			state.direct(); state.onError(zlibFailure()); reset(state)
			state.enabled shouldBe false
		}
	}
	test("an error on the remux retry restores ordinary fallback and cannot rearm from the same parser text") {
		val state = ContainerRemuxRecovery()
		state.direct(); state.onError(zlibFailure()); state.retry()
		state.onError(zlibFailure()); state.retry()
		state.enabled shouldBe false
	}
	test("live sources or unidentified items cannot enable recovery") {
		for (prepare in listOf<(ContainerRemuxRecovery) -> Unit>(
			{ it.direct(live = true) }, { it.prepare(null, "source", PlayMethod.DIRECT_PLAY, "mkv", false) },
			{ it.prepare(movie, null, PlayMethod.DIRECT_PLAY, "mkv", false) },
			{ it.prepare(movie, "source", PlayMethod.DIRECT_PLAY, "mp4", false) },
		)) {
			val state = ContainerRemuxRecovery()
			prepare(state); state.onError(zlibFailure()); state.retry()
			state.enabled shouldBe false
		}
	}
	test("other parser and decoder errors do not change normal fallback") {
		val state = ContainerRemuxRecovery()
		state.direct(); state.onError(IllegalStateException("private")); state.retry()
		state.enabled shouldBe false
	}
	test("prefers the unique fully supported same-quality remux over forced encode") {
		selectSupportedRemux(listOf(encode(), remux())) shouldBe remux()
	}
	test("unhandled or automatically forced encoding is never preferred") {
		selectSupportedRemux(listOf(encode(), remux().copy(handled = false))) shouldBe null
		selectSupportedRemux(listOf(encode(), remux().copy(autoCopy = CopyPermission.DENIED))) shouldBe null
		selectSupportedRemux(listOf(encode(), remux().copy(videoCopy = CopyPermission.INVALID))) shouldBe null
	}
	test("does not increase bandwidth resolution or frame rate or guess across ambiguous candidates") {
		for (different in listOf(remux().copy(bitrate = 20000000), remux().copy(width = 4096), remux().copy(height = 2160), remux().copy(frameRate = 60f))) {
			selectSupportedRemux(listOf(encode(), different)) shouldBe null
		}
		selectSupportedRemux(listOf(encode(), remux(), remux().copy(track = 3))) shouldBe null
	}
	test("leaves an already selected remux or unknown copy decision and incomplete metadata alone") {
		selectSupportedRemux(listOf(remux().copy(selected = true), encode().copy(selected = false))) shouldBe null
		selectSupportedRemux(listOf(encode().copy(videoCopy = CopyPermission.UNSPECIFIED), remux())) shouldBe null
		selectSupportedRemux(listOf(encode().copy(bitrate = -1), remux().copy(bitrate = -1))) shouldBe null
	}
})
