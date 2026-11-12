package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FallbackStreamDiagnosticTests : FunSpec({
	test("summarizes copy flags and bounded constraints without retaining credentials or unknown fields") {
		val summary = summarizeCopyRequest("https://private.example/title?api_key=secret&AllowVideoStreamCopy=true&AllowAudioStreamCopy=false&EnableAutoStreamCopy=true&SegmentContainer=mp4&VideoBitrate=13000000&MaxWidth=3840&MaxHeight=2160&VideoCodec=private")
		summary shouldBe SafeCopyRequest(CopyPermission.ALLOWED, CopyPermission.DENIED, CopyPermission.ALLOWED, "mp4", 13000000, 3840, 2160)
		summary.toString().contains("private") shouldBe false
		summary.toString().contains("secret") shouldBe false
	}
	test("duplicate false copy flag is observable without assuming query case or exposing values") {
		summarizeCopyRequest("main.m3u8?allowvideostreamcopy=true&AllowVideoStreamCopy=false").videoCopy shouldBe CopyPermission.DENIED
		summarizeCopyRequest("main.m3u8?AllowVideoStreamCopy=private").videoCopy shouldBe CopyPermission.INVALID
		summarizeCopyRequest(null).videoCopy shouldBe CopyPermission.UNSPECIFIED
	}
	test("malformed, duplicate and excessive numeric values remain opaque") {
		val summary = summarizeCopyRequest("main.m3u8?VideoBitrate=2147483648&MaxWidth=10&MaxWidth=20&MaxHeight=secret&SegmentContainer=private")
		summary shouldBe SafeCopyRequest()
		safeVideoMime("video/hevc") shouldBe "hevc"
		safeVideoMime("private-title") shouldBe "other"
	}
})
