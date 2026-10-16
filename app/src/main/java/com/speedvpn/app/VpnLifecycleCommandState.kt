package com.speedvpn.app

/** Process-wide gate for START commands that race native/TUN teardown. */
internal class VpnLifecycleCommandState {
    private val lock = Any()
    private var stopping = false
    private var startAfterStop = false

    fun requestStart() = synchronized(lock) {
        if (stopping) startAfterStop = true
    }

    fun requestStop(restartAfterStop: Boolean = false) = synchronized(lock) {
        stopping = true
        startAfterStop = restartAfterStop
    }

    fun beginTeardown() = synchronized(lock) { stopping = true }

    fun shouldDeferConnect(): Boolean = synchronized(lock) { stopping }

    fun finishStop(): Boolean = synchronized(lock) {
        stopping = false
        val resume = startAfterStop
        startAfterStop = false
        resume
    }
}
