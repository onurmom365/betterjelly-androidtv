package org.jellyfin.androidtv.ui.seerr

// Keep exception assertions compatible with the pinned JVM 8 test compilation target.
internal inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
	try { block() }
	catch (error: Throwable) {
		if (error is T) return error
		throw AssertionError("Expected ${T::class.simpleName}, got ${error::class.simpleName}", error)
	}
	throw AssertionError("Expected ${T::class.simpleName}")
}
