package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.ui.playback.selection.PreplaySelectionStorage
import org.jellyfin.androidtv.ui.playback.selection.PreplayTrackChoice
import org.jellyfin.androidtv.ui.playback.selection.PreplayTrackSelection
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

class PreplayTrackSelectionTests : FunSpec({
	val audio = MediaStreamType.AUDIO
	val sub = MediaStreamType.SUBTITLE
	val series = UUID.randomUUID()
	fun stream(index: Int, type: MediaStreamType = sub, language: String = "eng", title: String = "Dialogue", forced: Boolean = false) =
		MediaStream(index = index, type = type, language = language, title = title,
			codec = if (type == audio) "opus" else "ass", channels = if (type == audio) 2 else null,
			isInterlaced = false, isDefault = false, isForced = forced, isHearingImpaired = false,
			isExternal = false, isTextSubtitleStream = type == sub, supportsExternalStream = false)
	fun source(id: String = "source", vararg streams: MediaStream) = mockk<MediaSourceInfo>(relaxed = true) {
		every { this@mockk.id } returns id
		every { mediaStreams } returns streams.toList()
	}
	fun item(source: MediaSourceInfo, seriesId: UUID? = series, id: UUID = UUID.randomUUID()) =
		BaseItemDto(id = id, type = if (seriesId == null) BaseItemKind.MOVIE else BaseItemKind.EPISODE,
			seriesId = seriesId, mediaSources = listOf(source))
	fun storage() = object : PreplaySelectionStorage {
		val values = mutableMapOf<String, String>()
		override fun read(key: String) = values[key]
		override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
	}
	test("unchosen playback leaves server defaults and legacy audio policy intact") {
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		val options = VideoOptions()
		choices.apply(item(source()), options)
		options.audioStreamIndex shouldBe null
		options.subtitleStreamIndex shouldBe null
		options.preplayAudioExplicit shouldBe false
	}
	test("manual item indices and source are negotiated before playback") {
		val s = source("exact", stream(7, audio), stream(11))
		val movie = item(s, null)
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(movie.id, audio, PreplayTrackChoice.track(movie, s, s.mediaStreams!![0]))
		choices.save(movie.id, sub, PreplayTrackChoice.track(movie, s, s.mediaStreams!![1]))
		val options = VideoOptions()
		choices.apply(movie, options)
		options.audioStreamIndex shouldBe 7
		options.subtitleStreamIndex shouldBe 11
		options.mediaSourceId shouldBe "exact"
		options.preplayAudioExplicit shouldBe true
	}
	test("Off remains explicit minus one even without metadata") {
		val episode = item(source())
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(series, sub, PreplayTrackChoice(mode = "off"))
		val options = VideoOptions()
		choices.apply(episode.copy(mediaSources = null), options)
		options.subtitleStreamIndex shouldBe -1
	}
	test("a saved title choice keeps its media version and never borrows another version's index") {
		val a = source("version-a", stream(2, audio), stream(3))
		val b = source("version-b", stream(7, audio), stream(9))
		val movie = item(b, null).copy(mediaSources = listOf(a, b))
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(movie.id, sub, PreplayTrackChoice.track(movie, b, b.mediaStreams!![1]))
		val options = VideoOptions()
		choices.apply(movie, options)
		options.mediaSourceId shouldBe "version-b"
		options.subtitleStreamIndex shouldBe 9
		options.audioStreamIndex shouldBe null
		choices.source(movie) shouldBe b
	}
	test("series choice resolves reordered tracks by identity rather than original index") {
		val firstSource = source("first", stream(2), stream(3, title = "Signs"))
		val first = item(firstSource)
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(series, sub, PreplayTrackChoice.track(first, firstSource, firstSource.mediaStreams!![0]))
		val next = item(source("next", stream(2, title = "Signs"), stream(9)))
		val options = VideoOptions()
		choices.apply(next, options)
		options.subtitleStreamIndex shouldBe 9
	}
	test("missing or ambiguous series match returns to server default") {
		val original = source("first", stream(2))
		val first = item(original)
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(series, sub, PreplayTrackChoice.track(first, original, original.mediaStreams!!.single()))
		for (next in listOf(source("missing", stream(2, language = "jpn")), source("ambiguous", stream(2), stream(8)))) {
			val options = VideoOptions()
			choices.apply(item(next), options)
			options.subtitleStreamIndex shouldBe null
		}
	}
	test("forced dialogue hearing impaired and codec identities are not interchangeable") {
		val s = source("first", stream(2, forced = true))
		val first = item(s)
		val choice = PreplayTrackChoice.track(first, s, s.mediaStreams!!.single())
		for (other in listOf(stream(2), stream(2, forced = true).copy(isHearingImpaired = true), stream(2, forced = true).copy(codec = "srt"))) {
			choice.resolve(item(source("next", other)), source("next", other), sub) shouldBe null
		}
	}
	test("same-item reused index with changed track metadata is rejected") {
		val s = source("same", stream(2))
		val first = item(s)
		val choice = PreplayTrackChoice.track(first, s, s.mediaStreams!!.single())
		choice.resolve(first, source("same", stream(2, title = "Signs")), sub) shouldBe null
	}
	test("same-item exact duplicate metadata can use original index; another episode cannot") {
		val s = source("same", stream(2), stream(9))
		val first = item(s)
		val choice = PreplayTrackChoice.track(first, s, s.mediaStreams!!.last())
		choice.resolve(first, s, sub) shouldBe 9
		choice.resolve(item(s), s, sub) shouldBe null
	}
	test("explicit episode Default overrides series Off; removing override inherits again") {
		val episode = item(source())
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(series, sub, PreplayTrackChoice(mode = "off"))
		choices.save(episode.id, sub, PreplayTrackChoice())
		val options = VideoOptions()
		choices.apply(episode, options)
		options.subtitleStreamIndex shouldBe null
		choices.save(episode.id, sub, null)
		choices.apply(episode, options)
		options.subtitleStreamIndex shouldBe -1
	}
	test("explicit audio Default suppresses last-language selection even with no index") {
		val episode = item(source())
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(series, audio, PreplayTrackChoice())
		val options = VideoOptions()
		choices.apply(episode, options)
		options.audioStreamIndex shouldBe null
		options.preplayAudioExplicit shouldBe true
	}
	test("series choice persists through a new store instance without crossing accounts or servers") {
		val disk = storage()
		var account: String? = "server1/user1"
		val choices = PreplayTrackSelection(disk) { account }
		choices.save(series, sub, PreplayTrackChoice(mode = "off"))
		PreplayTrackSelection(disk) { account }.read(series, sub)?.mode shouldBe "off"
		for (other in listOf("server1/user2", "server2/user1", null)) {
			account = other
			choices.read(series, sub) shouldBe null
		}
		account = "server1/user1"
		choices.read(series, sub)?.mode shouldBe "off"
	}
	test("audio and subtitles and unrelated series remain isolated") {
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(series, sub, PreplayTrackChoice(mode = "off"))
		choices.read(series, audio) shouldBe null
		choices.effective(item(source(), UUID.randomUUID()), sub) shouldBe null
	}
	test("corrupted saved metadata falls back safely instead of preventing playback") {
		val disk = storage()
		val choices = PreplayTrackSelection(disk) { "server/user" }
		choices.save(series, sub, PreplayTrackChoice(mode = "off"))
		disk.values.keys.toList().forEach { disk.values[it] = "invalid-json" }
		choices.read(series, sub) shouldBe null
	}
	test("delivery lookup uses actual stream index rather than list position") {
		val s = source("source", stream(0, audio), stream(9).copy(deliveryMethod = SubtitleDeliveryMethod.EMBED))
		every { s.defaultSubtitleStreamIndex } returns 9
		StreamInfo().apply { mediaSource = s }.subtitleDeliveryMethod shouldBe SubtitleDeliveryMethod.EMBED
	}
	test("a named subtitle resolves from item-level streams when a single-source queue DTO omits them") {
		val full = source("same", stream(2, audio), stream(5))
		val movie = item(full, null)
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(movie.id, sub, PreplayTrackChoice.track(movie, full, full.mediaStreams!![1]))
		val sparse = movie.copy(mediaSources = listOf(source("same", stream(2, audio))), mediaStreams = full.mediaStreams)
		val options = VideoOptions()
		choices.apply(sparse, options)
		options.subtitleStreamIndex shouldBe 5
		options.preplayNeedsMetadata shouldBe false
	}
	test("missing subtitle metadata requests full metadata even when audio streams were supplied") {
		val full = source("same", stream(2, audio), stream(5))
		val episode = item(full)
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(episode.id, sub, PreplayTrackChoice.track(episode, full, full.mediaStreams!![1]))
		val options = VideoOptions()
		choices.apply(episode.copy(mediaSources = listOf(source("same", stream(2, audio))), mediaStreams = null), options)
		options.subtitleStreamIndex shouldBe null
		options.preplayNeedsMetadata shouldBe true
		choices.apply(episode, options)
		options.subtitleStreamIndex shouldBe 5
		options.preplayNeedsMetadata shouldBe false
	}
	test("item-level streams cannot supply indices to an ambiguous media version") {
		val full = source("same", stream(5))
		val movie = item(full, null)
		val choices = PreplayTrackSelection(storage()) { "server/user" }
		choices.save(movie.id, sub, PreplayTrackChoice.track(movie, full, full.mediaStreams!!.single()))
		val sparse = movie.copy(mediaSources = listOf(source("same"), source("other")), mediaStreams = full.mediaStreams)
		val options = VideoOptions()
		choices.apply(sparse, options)
		options.subtitleStreamIndex shouldBe null
		options.preplayNeedsMetadata shouldBe true
	}
	test("missing or disabled subtitle indices drop safely during negotiation") {
		val s = source("source", stream(0, audio))
		for (index in listOf(-1, 8)) {
			every { s.defaultSubtitleStreamIndex } returns index
			StreamInfo().apply { mediaSource = s }.subtitleDeliveryMethod shouldBe SubtitleDeliveryMethod.DROP
		}
	}
})
