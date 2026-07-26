package net.koalastuff.koalacast.core.data.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injected rather than called statically so a repository's timestamps can be
 * asserted in a test instead of guessed at.
 */
interface Clock {
    fun nowMs(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
