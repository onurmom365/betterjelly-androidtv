package org.jellyfin.androidtv.ui.playback

import android.net.Uri
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import com.google.common.collect.ImmutableList
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import androidx.media3.common.ParserException
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

class RemuxMappingTests : FunSpec({
	beforeSpec {
		mockkStatic(TextUtils::class)
		every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
		every { TextUtils.equals(any(), any()) } answers { firstArg<CharSequence?>()?.toString() == secondArg<CharSequence?>()?.toString() }
	}
	afterSpec { unmockkStatic(TextUtils::class) }
	fun armed(): ContainerRemuxRecovery {
		val state = ContainerRemuxRecovery()
		val item = UUID(0, 1)
		state.prepare(item, "private-source", PlayMethod.DIRECT_PLAY, "mkv", false)
		state.onError(ParserException.createForMalformedContainer("ContentCompAlgo 0 not supported", null).apply {
			stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.mkv.MatroskaExtractor", "integerElement", "MatroskaExtractor.java", 1016))
		})
		state.prepare(item, "private-source", PlayMethod.TRANSCODE, "mkv", false)
		return state
	}
	fun fixture(prefixIds: Boolean = false, ambiguous: Boolean = false, differentRate: Boolean = false): Pair<Tracks, HlsManifest> {
		val codecs = listOf("dvh1.08.06,ec-3", "hvc1.1.6.L153.B0,ec-3", "avc1.640033,ec-3")
		val formats = codecs.mapIndexed { index, codec -> Format.Builder().setId(index.toString()).setCodecs(codec)
			.setWidth(3840).setHeight(2076).setAverageBitrate(14000000).setPeakBitrate(14000000).setFrameRate(23.976f).build() }
		val variants = formats.mapIndexed { index, format ->
			val uri = mockk<Uri>()
			every { uri.toString() } returns ("https://private.example/main.m3u8?api_key=secret&SegmentContainer=ts" + if (index == 0) "" else "&AllowVideoStreamCopy=false")
			HlsMultivariantPlaylist.Variant(uri, format, null, null, null, null)
		}
		val playlistVariants = if (ambiguous) variants + HlsMultivariantPlaylist.Variant(
			variants[1].url, formats[0].buildUpon().setId("duplicate").build(), null, null, null, null) else variants
		val playlist = HlsMultivariantPlaylist("https://private.example/master.m3u8", emptyList(), playlistVariants,
			emptyList(), emptyList(), emptyList(), emptyList(), null, null, false, emptyMap(), emptyList())
		val constructor = HlsManifest::class.java.getDeclaredConstructor(HlsMultivariantPlaylist::class.java, HlsMediaPlaylist::class.java)
		constructor.isAccessible = true
		val manifest = constructor.newInstance(playlist, mockk<HlsMediaPlaylist>())
		val group = TrackGroup("0:main", *formats.mapIndexed { index, format -> format.buildUpon()
			.setId(if (prefixIds) "other:$index" else format.id).setAverageBitrate(if (differentRate && index == 0) 15000000 else format.averageBitrate)
			.setPeakBitrate(if (differentRate && index == 0) 15000000 else format.peakBitrate)
			.setSampleMimeType(MimeTypes.getVideoMediaMimeType(format.codecs)).build() }.toTypedArray())
		return Tracks(ImmutableList.of(Tracks.Group(group, true, intArrayOf(4, 4, 4), booleanArrayOf(false, true, false)))) to manifest
	}
	test("maps the real Media3 HLS format and track objects to the supported copy variant") {
		val (tracks, manifest) = fixture()
		val recovery = armed()
		recovery.findOverride(tracks, manifest)?.trackIndices shouldBe listOf(0)
		recovery.diagnostic().gate shouldBe RemuxGate.CHOSEN
	}
	test("player reassigned IDs still map through a unique equivalent video format") {
		val (tracks, manifest) = fixture(prefixIds = true)
		val recovery = armed()
		recovery.findOverride(tracks, manifest)?.trackIndices shouldBe listOf(0)
		recovery.diagnostic().idMatches shouldBe 0
		recovery.diagnostic().candidates shouldBe 3
	}
	test("duplicate equivalent video formats cannot choose between different copy policies") {
		val (tracks, manifest) = fixture(prefixIds = true, ambiguous = true)
		armed().findOverride(tracks, manifest) shouldBe null
	}
	test("changed bitrate cannot use a metadata match merely because codec and size agree") {
		val (tracks, manifest) = fixture(prefixIds = true, differentRate = true)
		armed().findOverride(tracks, manifest) shouldBe null
	}
	test("state diagnostics never contain media identities or source data") {
		val safe = armed().diagnostic().toString()
		safe.contains("private") shouldBe false
		safe.contains("secret") shouldBe false
		safe.contains(UUID(0, 1).toString()) shouldBe false
	}
})
