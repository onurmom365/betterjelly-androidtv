package org.jellyfin.androidtv.ui.seerr

import kotlinx.coroutines.delay
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class SeerrConnectionIssue(val description: String, val retryable: Boolean) {
	TIMEOUT("The Seerr connection timed out.", true),
	UNREACHABLE("Could not reach the Seerr server.", true),
	CLOSED("The Seerr connection closed unexpectedly.", true),
	DNS("The Seerr address could not be resolved.", false),
	TLS("The secure Seerr connection could not be verified.", false),
	OTHER("The Seerr connection failed.", true);

	companion object {
		fun from(error: IOException): SeerrConnectionIssue = when (error) {
			is SSLException -> TLS
			is UnknownHostException -> DNS
			is InterruptedIOException -> TIMEOUT
			is ConnectException, is NoRouteToHostException -> UNREACHABLE
			is EOFException, is SocketException -> CLOSED
			else -> OTHER
		}
	}
}

interface SeerrRetryableReadFailure { val retryableRead: Boolean }

/** One extra cancellable attempt for transient reads only. Callers must exclude every write. */
internal suspend fun <T> recoverSeerrRead(block: suspend () -> T): T {
	return try { block() }
	catch (error: Exception) {
		if ((error as? SeerrRetryableReadFailure)?.retryableRead != true) throw error
		delay(250)
		block()
	}
}
