package org.jellyfin.androidtv.ui.playback

import android.util.Log
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.google.common.collect.ImmutableList
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleStartupTests : FunSpec({
	beforeSpec {
		mockkStatic(Log::class, TextUtils::class)
		every { Log.i(any(), any()) } returns 0
		every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
		every { TextUtils.equals(any(), any()) } answers { firstArg<CharSequence?>()?.toString() == secondArg<CharSequence?>()?.toString() }
	}
	afterSpec { unmockkStatic(Log::class, TextUtils::class) }
	fun fixture(): Triple<PlaybackController, ExoPlayer, TrackSelectionParameters.Builder> {
		val selected = MediaStream(index = 5, type = MediaStreamType.SUBTITLE, codec = "srt", deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
			isInterlaced = false, isDefault = false, isForced = false, isHearingImpaired = false,
			isExternal = true, isTextSubtitleStream = true, supportsExternalStream = true)
		val source = mockk<MediaSourceInfo> { every { mediaStreams } returns listOf(selected) }
		val builder = mockk<DefaultTrackSelector.Parameters.Builder>(relaxed = true)
		val parameters = mockk<DefaultTrackSelector.Parameters> { every { buildUpon() } returns builder }
		every { builder.clearOverridesOfType(any()) } returns builder
		every { builder.addOverride(any()) } returns builder
		every { builder.setTrackTypeDisabled(any(), any()) } returns builder
		every { builder.build() } returns parameters
		val selector = mockk<DefaultTrackSelector>(relaxed = true) { every { this@mockk.parameters } returns parameters }
		val player = mockk<ExoPlayer> { every { currentTracks } returns Tracks.EMPTY; every { trackSelector } returns selector }
		val video = mockk<VideoManager>().apply { mExoPlayer = player }
		val controller = mockk<PlaybackController> {
			every { currentMediaSource } returns source
		}.apply { mCurrentOptions = VideoOptions(); mVideoManager = video }
		return Triple(controller, player, builder)
	}
	test("startup without text groups retains the named request instead of silently selecting Off") {
		val (controller, _, _) = fixture()
		controller.setSubtitleIndex(5, true)
		controller.mCurrentOptions.subtitleStreamIndex shouldBe 5
		controller.mPendingSubtitleIndex shouldBe 5
	}
	test("late arriving text group applies the pending request and clears it before the override") {
		val (controller, player, builder) = fixture()
		controller.setSubtitleIndex(5, true)
		val group = TrackGroup("external", Format.Builder().setId("1:JF_EXTERNAL:5").setSampleMimeType("application/x-subrip").build())
		val tracks = Tracks(ImmutableList.of(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))))
		every { player.currentTracks } returns tracks
		controller.setSubtitleIndex(controller.mPendingSubtitleIndex!!, true)
		controller.mPendingSubtitleIndex shouldBe null
		controller.mCurrentOptions.subtitleStreamIndex shouldBe 5
		verify { builder.addOverride(match { it.mediaTrackGroup == group }) }
		verify { builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) }
	}
	test("Off cancels a pending subtitle so a late track event cannot re-enable it") {
		val (controller, _, builder) = fixture()
		controller.setSubtitleIndex(5, true)
		controller.setSubtitleIndex(-1, true)
		controller.mPendingSubtitleIndex shouldBe null
		controller.mCurrentOptions.subtitleStreamIndex shouldBe -1
		verify { builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
	}
	test("diagnostic selection modes cannot print arbitrary saved data") {
		PlaybackCapabilitiesDiagnostic.safeSelectionMode("private-title-or-token") shouldBe "none"
		PlaybackCapabilitiesDiagnostic.safeSelectionMode("track") shouldBe "track"
	}
})
