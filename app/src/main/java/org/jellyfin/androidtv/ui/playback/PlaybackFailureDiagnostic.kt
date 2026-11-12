package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

internal enum class FailureCause { PLAYER, PARSER, ARRAY_BOUNDS, BOUNDS, IO, ARGUMENT, STATE, OTHER }
internal enum class ParserComponent { HEVC_CONFIG, AVC_CONFIG, AV1_CONFIG, TRUEHD, AC3, EBML, MATROSKA, MP4, OTHER }
internal enum class ParserRejection { EBML_READ_VERSION, DOCUMENT_READ_VERSION, ENCODING_ORDER, ENCODING_SCOPE, COMPRESSION_ALGORITHM, ENCRYPTION_ALGORITHM, CIPHER_MODE, OTHER }
internal data class SafePlaybackFailure(
	val causes: List<FailureCause>, val parser: ParserComponent, val parserLine: Int? = null,
	val rejection: ParserRejection = ParserRejection.OTHER, val rejectedValue: Long? = null,
)

private val numericParserRejection = Regex("^(EBMLReadVersion|DocTypeReadVersion|ContentEncodingOrder|ContentEncodingScope|ContentCompAlgo|ContentEncAlgo|AESSettingsCipherMode) (-?\\d+) not supported(?: \\{contentIsMalformed=(?:true|false), dataType=-?\\d+\\})?$")

/** Only fixed categories leave this function. Exception messages and frame text never leave it. */
internal fun summarizePlaybackFailure(error: Throwable): SafePlaybackFailure {
	val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
	val causes = mutableListOf<FailureCause>()
	val components = mutableListOf<Pair<ParserComponent, Int>>()
	var rejection = ParserRejection.OTHER
	var rejectedValue: Long? = null
	var current: Throwable? = error
	while (current != null && causes.size < 8 && seen.add(current)) {
		if (current is ParserException) {
			// Exact known library templates only; variable text is never returned or logged.
			val known = numericParserRejection.matchEntire(current.message.orEmpty())
			if (known != null) {
				rejection = when (known.groupValues[1]) {
					"EBMLReadVersion" -> ParserRejection.EBML_READ_VERSION
					"DocTypeReadVersion" -> ParserRejection.DOCUMENT_READ_VERSION
					"ContentEncodingOrder" -> ParserRejection.ENCODING_ORDER
					"ContentEncodingScope" -> ParserRejection.ENCODING_SCOPE
					"ContentCompAlgo" -> ParserRejection.COMPRESSION_ALGORITHM
					"ContentEncAlgo" -> ParserRejection.ENCRYPTION_ALGORITHM
					"AESSettingsCipherMode" -> ParserRejection.CIPHER_MODE
					else -> ParserRejection.OTHER
				}
				rejectedValue = known.groupValues[2].toLongOrNull()
			}
		}
		causes += when (current) {
			is PlaybackException -> FailureCause.PLAYER
			is ParserException -> FailureCause.PARSER
			is ArrayIndexOutOfBoundsException -> FailureCause.ARRAY_BOUNDS
			is IndexOutOfBoundsException -> FailureCause.BOUNDS
			is IOException -> FailureCause.IO
			is IllegalArgumentException -> FailureCause.ARGUMENT
			is IllegalStateException -> FailureCause.STATE
			else -> FailureCause.OTHER
		}
		for (frame in current.stackTrace) {
			val component = when (frame.className) {
				"androidx.media3.extractor.HevcConfig" -> ParserComponent.HEVC_CONFIG
				"androidx.media3.extractor.AvcConfig" -> ParserComponent.AVC_CONFIG
				"androidx.media3.extractor.Av1Config" -> ParserComponent.AV1_CONFIG
				"androidx.media3.extractor.TrueHdSampleRechunker", "androidx.media3.extractor.TrueHdSampleRechunker\$1" -> ParserComponent.TRUEHD
				"androidx.media3.extractor.Ac3Util" -> ParserComponent.AC3
				"androidx.media3.extractor.mkv.DefaultEbmlReader", "androidx.media3.extractor.mkv.VarintReader" -> ParserComponent.EBML
				"androidx.media3.extractor.mkv.MatroskaExtractor", "io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor" -> ParserComponent.MATROSKA
				"androidx.media3.extractor.mp4.AtomParsers", "androidx.media3.extractor.mp4.Mp4Extractor", "androidx.media3.extractor.mp4.FragmentedMp4Extractor" -> ParserComponent.MP4
				else -> ParserComponent.OTHER
			}
			if (component != ParserComponent.OTHER) {
				components += component to frame.lineNumber
				break // Nearest recognized frame in each cause, rather than its calling dispatcher.
			}
		}
		current = current.cause
	}
	val parser = components.lastOrNull() // Prefer the most specific nested cause.
	return SafePlaybackFailure(causes, parser?.first ?: ParserComponent.OTHER, parser?.second?.takeIf { it >= 0 }, rejection, rejectedValue)
}
