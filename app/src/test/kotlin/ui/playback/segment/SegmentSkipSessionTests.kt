package org.jellyfin.androidtv.ui.playback.segment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID

class SegmentSkipSessionTests : FunSpec({
	val firstItem = UUID(0, 1)
	val secondItem = UUID(0, 2)
	val intro = SegmentCountdownCue(60_000, 90_000, MediaSegmentType.INTRO, MediaSegmentAction.SKIP)
	val outro = SegmentCountdownCue(600_000, 630_000, MediaSegmentType.OUTRO, MediaSegmentAction.SKIP)

	test("without cancellation intro and credits retain their configured actions") {
		val session = SegmentSkipSession()
		val token = session.begin(firstItem)
		session.setCues(token, listOf(intro, outro))
		session.shouldAct(token, intro) shouldBe true
		session.shouldAct(token, outro) shouldBe true
	}

	test("Back removes the current countdown and blocks its scheduled skip while credits remain enabled") {
		val session = SegmentSkipSession()
		val token = session.begin(firstItem)
		session.setCues(token, listOf(intro, outro))
		val shown = findSegmentCountdown(session.countdowns, 58_000)!!.cue
		session.dismiss(shown) shouldBe true
		findSegmentCountdown(session.countdowns, 58_000) shouldBe null
		session.shouldAct(token, intro) shouldBe false
		session.shouldAct(token, outro) shouldBe true
	}

	test("a queued action rechecks cancellation before making a seek or advancing an episode") {
		val session = SegmentSkipSession()
		val token = session.begin(firstItem)
		session.setCues(token, listOf(outro))
		var seeks = 0
		val queuedAction = { if (session.shouldAct(token, outro)) seeks++ }
		session.dismiss(outro)
		queuedAction()
		seeks shouldBe 0
	}

	test("cancellation persists across seeking back and a stream rebuild for the same item") {
		val session = SegmentSkipSession()
		val old = session.begin(firstItem)
		session.setCues(old, listOf(intro))
		session.dismiss(intro)
		val rebuilt = session.begin(firstItem)
		session.setCues(rebuilt, listOf(intro))
		findSegmentCountdown(session.countdowns, 58_000) shouldBe null
		session.shouldAct(rebuilt, intro) shouldBe false
	}

	test("the next episode gets normal auto skip even with identical segment timestamps") {
		val session = SegmentSkipSession()
		val first = session.begin(firstItem)
		session.setCues(first, listOf(intro))
		session.dismiss(intro)
		val next = session.begin(secondItem)
		session.setCues(next, listOf(intro))
		session.shouldAct(first, intro) shouldBe false
		session.shouldAct(next, intro) shouldBe true
		findSegmentCountdown(session.countdowns, 58_000)?.cue shouldBe intro
	}

	test("old fetches and callbacks cannot act after a newer segment registration") {
		val session = SegmentSkipSession()
		val old = session.begin(firstItem)
		session.setCues(old, listOf(intro))
		val current = session.begin(firstItem)
		session.setCues(current, listOf(outro))
		session.setCues(old, listOf(intro))
		session.countdowns shouldBe listOf(outro)
		session.shouldAct(old, intro) shouldBe false
		session.shouldAct(current, intro) shouldBe false
	}

	test("Ask mode can be dismissed before its prompt and a preference refresh cannot restore that segment") {
		val ask = intro.copy(action = MediaSegmentAction.ASK_TO_SKIP)
		val session = SegmentSkipSession()
		val token = session.begin(firstItem)
		session.setCues(token, listOf(ask))
		session.shouldAct(token, ask) shouldBe true
		session.dismiss(ask)
		session.shouldAct(token, ask) shouldBe false
		val refreshed = session.begin(firstItem)
		session.setCues(refreshed, listOf(intro))
		session.shouldAct(refreshed, intro) shouldBe false
	}

	test("nothing mode and unknown cues cannot cause actions or cancellation") {
		val session = SegmentSkipSession()
		val token = session.begin(firstItem)
		val disabled = intro.copy(action = MediaSegmentAction.NOTHING)
		session.setCues(token, listOf(disabled))
		session.shouldAct(token, disabled) shouldBe false
		session.dismiss(disabled) shouldBe false
		session.dismiss(outro) shouldBe false
		session.shouldAct(token, outro) shouldBe false
	}

	test("ending playback and creating a new controller session restores original actions") {
		val oldSession = SegmentSkipSession()
		val old = oldSession.begin(firstItem)
		oldSession.setCues(old, listOf(intro))
		oldSession.dismiss(intro)
		val newSession = SegmentSkipSession()
		val token = newSession.begin(firstItem)
		newSession.setCues(token, listOf(intro))
		newSession.shouldAct(token, intro) shouldBe true
	}
})
