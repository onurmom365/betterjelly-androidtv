package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.ParserException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackFailureDiagnosticTests : FunSpec({
	test("classifies nested HEVC parser failure without returning messages or frame text") {
		val cause = ArrayIndexOutOfBoundsException("private-url?token=secret").apply {
			stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.HevcConfig", "private-method", "private-file", 123))
		}
		val error = ParserException.createForMalformedContainer("private-title", cause).apply {
			stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.mkv.MatroskaExtractor", "private-method", "private-file", 1))
		}
		val summary = summarizePlaybackFailure(error)
		summary.causes shouldBe listOf(FailureCause.PARSER, FailureCause.ARRAY_BOUNDS)
		summary.parser shouldBe ParserComponent.HEVC_CONFIG
		summary.parserLine shouldBe 123
		summary.toString().contains("private") shouldBe false
		summary.toString().contains("secret") shouldBe false
	}
	test("unknown exception and application frames remain opaque") {
		val error = RuntimeException("private-token").apply {
			stackTrace = arrayOf(StackTraceElement("private.Class", "privateMethod", "privateFile", 1))
		}
		summarizePlaybackFailure(error) shouldBe SafePlaybackFailure(listOf(FailureCause.OTHER), ParserComponent.OTHER)
	}
	test("cyclic exception causes terminate") {
		val first = RuntimeException()
		val second = IllegalStateException()
		first.initCause(second)
		second.initCause(first)
		summarizePlaybackFailure(first).causes shouldBe listOf(FailureCause.OTHER, FailureCause.STATE)
	}
	test("media metadata accepts only known codec and container names") {
		PlaybackCapabilitiesDiagnostic.safeVideoCodec("HEVC") shouldBe "hevc"
		PlaybackCapabilitiesDiagnostic.safeContainer("MKV") shouldBe "mkv"
		PlaybackCapabilitiesDiagnostic.safeVideoCodec("private-title") shouldBe "other"
		PlaybackCapabilitiesDiagnostic.safeContainer("https://private/?token=secret") shouldBe "other"
	}
})
