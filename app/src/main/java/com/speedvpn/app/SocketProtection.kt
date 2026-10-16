package com.speedvpn.app

/** Protect an egress socket before binding it to a physical network. */
internal fun <T> protectSocketBeforeNetworkBinding(
    socket: T,
    protect: (T) -> Boolean,
    bindToNetwork: (T) -> Unit,
): Boolean {
    if (!protect(socket)) return false
    bindToNetwork(socket)
    return true
}
