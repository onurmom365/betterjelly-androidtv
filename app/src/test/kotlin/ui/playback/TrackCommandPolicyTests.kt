package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.androidtv.ui.playback.selection.TrackCommandPolicy
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

class TrackCommandPolicyTests : FunSpec({
	val audio = MediaStreamType.AUDIO
	val subtitles = MediaStreamType.SUBTITLE
	test("null and empty controlling identities are unattributed while a real user is identified") {
		TrackCommandPolicy.hasControllingUser(null) shouldBe false
		TrackCommandPolicy.hasControllingUser(UUID(0, 0)) shouldBe false
		TrackCommandPolicy.hasControllingUser(UUID.randomUUID()) shouldBe true
	}
	test("no current playback rejects commands safely regardless of origin") {
		for (user in listOf(true, false)) for (type in listOf(audio, subtitles))
			TrackCommandPolicy.accepts(null, type, user) shouldBe false
	}
	test("manual subtitle protection leaves default audio under automatic control") {
		val options = VideoOptions().apply { manualSubtitleSelection = true }
		TrackCommandPolicy.accepts(options, subtitles, false) shouldBe false
		TrackCommandPolicy.accepts(options, audio, false) shouldBe true
		TrackCommandPolicy.accepts(options, subtitles, true) shouldBe true
	}
	test("controls can independently claim audio and subtitle intent") {
		val options = VideoOptions()
		TrackCommandPolicy.markManual(options, audio)
		TrackCommandPolicy.accepts(options, audio, false) shouldBe false
		TrackCommandPolicy.accepts(options, subtitles, false) shouldBe true
		TrackCommandPolicy.markManual(options, subtitles)
		TrackCommandPolicy.accepts(options, subtitles, false) shouldBe false
	}
	test("unsupported command types cannot bypass policy or change manual track flags") {
		val options = VideoOptions()
		for (user in listOf(true, false)) TrackCommandPolicy.accepts(options, MediaStreamType.VIDEO, user) shouldBe false
		TrackCommandPolicy.markManual(options, MediaStreamType.VIDEO)
		options.manualAudioSelection shouldBe false
		options.manualSubtitleSelection shouldBe false
	}
})
