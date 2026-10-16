package com.speedvpn.app

/**
 * Process-wide gate for overlapping Service instances. Each accepted STOP/teardown owns
 * a token; a stale Service can release only its own token, never another generation's
 * pending stop. Starts arriving while any token remains are queued until all teardown
 * owners have finished.
 */
internal class VpnLifecycleCommandState {
    private val lock = Any()
    private val pendingStops = linkedSetOf<Long>()
    private var nextToken = 0L
    private var startAfterStop = false

    fun requestStart() {
        synchronized(lock) {
            if (pendingStops.isNotEmpty()) startAfterStop = true
        }
    }

    fun requestStop(
        existingToken: Long? = null,
        restartAfterStop: Boolean = false,
    ): Long = synchronized(lock) {
        val token = existingToken?.takeIf { it in pendingStops } ?: ++nextToken
        pendingStops.add(token)
        // The latest explicit stop supersedes a queued ordinary start. Settings restart
        // sets this back to true so the queued connection survives its own teardown.
        startAfterStop = restartAfterStop
        token
    }

    fun beginTeardown(existingToken: Long? = null): Long = synchronized(lock) {
        val token = existingToken?.takeIf { it in pendingStops } ?: ++nextToken
        pendingStops.add(token)
        token
    }

    fun shouldDeferConnect(): Boolean = synchronized(lock) { pendingStops.isNotEmpty() }

    fun finishStop(token: Long?): Boolean = synchronized(lock) {
        if (token == null || !pendingStops.remove(token)) return@synchronized false
        if (pendingStops.isNotEmpty()) {
            false
        } else {
            val resume = startAfterStop
            startAfterStop = false
            resume
        }
    }
}
