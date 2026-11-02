package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

class AssPlaybackTests : FunSpec({
	fun profile(ass: Boolean, downmix: Boolean = false) = createDeviceProfile(
		mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true), maxBitrate = 120000000,
		isAC3Enabled = true, downMixAudio = downmix, assDirectPlay = ass, pgsDirectPlay = true, jellyfinTenEleven = true)
	test("disabled libass retains burn-in without advertising embedded or external ASS") {
		val profiles = profile(false).subtitleProfiles.filter { it.format in setOf("ass", "ssa") }
		profiles.size shouldBe 2
		profiles.all { it.method == SubtitleDeliveryMethod.ENCODE } shouldBe true
	}
	test("enabled renderer advertises embedded and external ASS SSA with encoding fallback") {
		val profiles = profile(true).subtitleProfiles
		for (format in listOf("ass", "ssa")) profiles.filter { it.format == format }.map { it.method }.toSet() shouldBe
			setOf(SubtitleDeliveryMethod.EMBED, SubtitleDeliveryMethod.EXTERNAL, SubtitleDeliveryMethod.ENCODE)
	}
	test("Direct mode advertises Opus in MKV regardless of ASS toggle") {
		for (ass in listOf(false, true)) {
			profile(ass).directPlayProfiles.any { it.type == DlnaProfileType.VIDEO && "mkv" in it.container.orEmpty().split(',') &&
				"opus" in it.audioCodec.orEmpty().split(',') } shouldBe true
		}
	}
	test("explicit downmix policy is unchanged and does not silently enable codecs") {
		profile(true, downmix = true).directPlayProfiles.none { "opus" in it.audioCodec.orEmpty().split(',') } shouldBe true
	}
	test("enabling ASS does not change audio HEVC HDR bitrate or PGS profiles") {
		val off = profile(false)
		val on = profile(true)
		on.directPlayProfiles shouldBe off.directPlayProfiles
		on.codecProfiles shouldBe off.codecProfiles
		on.maxStreamingBitrate shouldBe off.maxStreamingBitrate
		on.subtitleProfiles.filter { it.format !in setOf("ass", "ssa") } shouldBe off.subtitleProfiles.filter { it.format !in setOf("ass", "ssa") }
	}
	fun stream(index: Int, codec: String, method: SubtitleDeliveryMethod, external: Boolean = false) =
		MediaStream(index = index, codec = codec, type = MediaStreamType.SUBTITLE, deliveryMethod = method, isExternal = external,
			isInterlaced = false, isDefault = false, isForced = false, isHearingImpaired = false,
			isTextSubtitleStream = true, supportsExternalStream = external)
	test("embedded indexing counts burned ASS before a selected SRT stream") {
		val ass = stream(2, "ass", SubtitleDeliveryMethod.ENCODE)
		val srt = stream(5, "srt", SubtitleDeliveryMethod.EMBED)
		embeddedSubtitleGroupIndex(listOf(ass, srt), srt) shouldBe 1
	}
	test("external subtitles do not offset embedded stream groups") {
		val external = stream(3, "srt", SubtitleDeliveryMethod.EXTERNAL, true)
		val selected = stream(9, "ass", SubtitleDeliveryMethod.EMBED)
		embeddedSubtitleGroupIndex(listOf(external, selected), selected) shouldBe 0
	}
	test("HLS group indexing retains delivery filtering and absent stream is unresolved") {
		val ass = stream(2, "ass", SubtitleDeliveryMethod.ENCODE)
		val selected = stream(5, "webvtt", SubtitleDeliveryMethod.HLS)
		embeddedSubtitleGroupIndex(listOf(ass, selected), selected) shouldBe 0
		embeddedSubtitleGroupIndex(listOf(ass), selected) shouldBe null
	}
	test("diagnostic extracts only known transcode reasons and never private URL fields") {
		val value = PlaybackCapabilitiesDiagnostic.safeReasons("/video/private-title/stream?api_key=private-secret&TranscodeReasons=AudioCodecNotSupported,private-secret,SubtitleCodecNotSupported")
		value shouldBe "AudioCodecNotSupported|Other|SubtitleCodecNotSupported"
		PlaybackCapabilitiesDiagnostic.safeReasons("/private?token=private-secret") shouldBe "None"
		PlaybackCapabilitiesDiagnostic.safeReasons(null) shouldBe "None"
		PlaybackCapabilitiesDiagnostic.safeCodec("OPUS") shouldBe "opus"
		PlaybackCapabilitiesDiagnostic.safeCodec("private-secret") shouldBe "other"
	}
})
