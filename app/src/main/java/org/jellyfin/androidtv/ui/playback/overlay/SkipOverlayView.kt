package org.jellyfin.androidtv.ui.playback.overlay

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.SegmentCountdown
import org.jellyfin.androidtv.ui.playback.segment.SegmentCountdownCue
import org.jellyfin.androidtv.ui.playback.segment.findSegmentCountdown
import org.jellyfin.sdk.model.api.MediaSegmentType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SkipOverlayComposable(
	visible: Boolean,
	countdown: SegmentCountdown? = null,
	playbackRunning: Boolean = false,
) {
	Box(
		contentAlignment = Alignment.BottomEnd,
		modifier = Modifier
			.padding(48.dp, 48.dp)
	) {
		if (countdown != null) SegmentCountdownCard(countdown, playbackRunning)
		AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
			Row(
				modifier = Modifier
					.clip(RoundedCornerShape(6.dp))
					.background(colorResource(R.color.popup_menu_background).copy(alpha = 0.6f))
					.padding(10.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
					contentDescription = null,
				)

				Text(
					text = stringResource(R.string.segment_action_skip),
					color = colorResource(R.color.button_default_normal_text),
					fontSize = 18.sp,
				)
			}
		}
	}
}

@Composable
private fun SegmentCountdownCard(countdown: SegmentCountdown, playbackRunning: Boolean) {
	val ring = remember(countdown.cue) { Animatable(countdown.remainingFraction) }
	LaunchedEffect(ring, countdown.remainingFraction, playbackRunning) {
		if (playbackRunning) {
			// Match the native 500ms progress-report cadence without advancing beyond a reported position.
			ring.animateTo(countdown.remainingFraction, tween(durationMillis = 500, easing = LinearEasing))
		} else {
			ring.snapTo(countdown.remainingFraction)
		}
	}
	val segmentName = stringResource(when (countdown.cue.type) {
		MediaSegmentType.INTRO -> R.string.segment_countdown_intro
		MediaSegmentType.OUTRO -> R.string.segment_countdown_outro
		else -> R.string.segment_countdown_recap
	})
	val label = stringResource(
		if (countdown.cue.action == MediaSegmentAction.SKIP) R.string.segment_countdown_auto else R.string.segment_countdown_ask,
		segmentName,
	)
	val description = stringResource(R.string.segment_countdown_accessibility, label, countdown.secondsRemaining)
	val color = colorResource(R.color.button_default_normal_text)
	Row(
		modifier = Modifier
			.clip(RoundedCornerShape(6.dp))
			.background(colorResource(R.color.popup_menu_background).copy(alpha = 0.6f))
			.padding(10.dp)
			.semantics(mergeDescendants = true) { contentDescription = description },
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
			Canvas(Modifier.size(40.dp)) {
				val width = 3.dp.toPx()
				drawCircle(color.copy(alpha = 0.25f), radius = (size.minDimension - width) / 2, style = Stroke(width))
				drawArc(
					color = color,
					startAngle = -90f,
					sweepAngle = 360f * ring.value,
					useCenter = false,
					topLeft = Offset(width / 2, width / 2),
					size = Size(size.width - width, size.height - width),
					style = Stroke(width, cap = StrokeCap.Round),
				)
			}
			Text(text = countdown.secondsRemaining.toString(), color = color, fontSize = 18.sp)
		}
		Text(text = label, color = color, fontSize = 18.sp)
	}
}

class SkipOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyle: Int = 0
) : AbstractComposeView(context, attrs, defStyle) {
	private val _currentPosition = MutableStateFlow(Duration.ZERO)
	private val _targetPosition = MutableStateFlow<Duration?>(null)
	private val _skipUiEnabled = MutableStateFlow(true)
	private val _segmentCountdowns = MutableStateFlow<List<SegmentCountdownCue>>(emptyList())
	private val _playbackRunning = MutableStateFlow(false)

	var playbackRunning: Boolean
		get() = _playbackRunning.value
		set(value) {
			_playbackRunning.value = value
		}

	var segmentCountdowns: List<SegmentCountdownCue>
		get() = _segmentCountdowns.value
		set(value) {
			_segmentCountdowns.value = value.toList()
		}

	var currentPosition: Duration
		get() = _currentPosition.value
		set(value) {
			_currentPosition.value = value
		}

	var currentPositionMs: Long
		get() = _currentPosition.value.inWholeMilliseconds
		set(value) {
			_currentPosition.value = value.milliseconds
		}

	var targetPosition: Duration?
		get() = _targetPosition.value
		set(value) {
			_targetPosition.value = value
		}

	var targetPositionMs: Long?
		get() = _targetPosition.value?.inWholeMilliseconds
		set(value) {
			_targetPosition.value = value?.milliseconds
		}

	var skipUiEnabled: Boolean
		get() = _skipUiEnabled.value
		set(value) {
			_skipUiEnabled.value = value
		}

	val visible: Boolean
		get() {
			val enabled = _skipUiEnabled.value
			val targetPosition = _targetPosition.value
			val currentPosition = _currentPosition.value

			return enabled && targetPosition != null && currentPosition <= (targetPosition - MediaSegmentRepository.SkipMinDuration)
		}

	@Composable
	override fun Content() {
		val skipUiEnabled by _skipUiEnabled.collectAsState()
		val currentPosition by _currentPosition.collectAsState()
		val targetPosition by _targetPosition.collectAsState()
		val segmentCountdowns by _segmentCountdowns.collectAsState()
		val playbackRunning by _playbackRunning.collectAsState()

		val visible by remember(skipUiEnabled, currentPosition, targetPosition) {
			derivedStateOf { visible }
		}

		// Auto hide
		LaunchedEffect(skipUiEnabled, targetPosition) {
			delay(MediaSegmentRepository.AskToSkipAutoHideDuration)
			_targetPosition.value = null
		}

		val countdown = if (skipUiEnabled && !visible) findSegmentCountdown(segmentCountdowns, currentPosition.inWholeMilliseconds) else null
		SkipOverlayComposable(visible, countdown, playbackRunning)
	}
}
