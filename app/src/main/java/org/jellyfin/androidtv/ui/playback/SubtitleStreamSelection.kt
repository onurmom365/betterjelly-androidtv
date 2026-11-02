package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/** Upstream #5862: embedded group order includes ASS tracks even when the server would burn them. */
internal fun embeddedSubtitleGroupIndex(streams: List<MediaStream>, selected: MediaStream): Int? = streams
	.filter { it.type == MediaStreamType.SUBTITLE }
	.filter {
		if (selected.deliveryMethod == SubtitleDeliveryMethod.EMBED) !it.isExternal
		else it.deliveryMethod == SubtitleDeliveryMethod.EMBED || it.deliveryMethod == SubtitleDeliveryMethod.HLS
	}
	.indexOf(selected).takeIf { it >= 0 }
