package com.speedvpn.app

import java.net.DatagramSocket
import java.net.Socket

/**
 * A socket is usable for external traffic only after VpnService.protect() returns true.
 * This helper closes the socket synchronously on false/exception and reports both
 * protection and close failures. Callers must abort before bind/connect/send on false.
 */
private fun protectSocketOrClose(
    description: String,
    protect: () -> Boolean,
    close: () -> Unit,
    onFailure: (String, Throwable?) -> Unit,
): Boolean {
    var protectionError: Exception? = null
    val protected = try {
        protect()
    } catch (e: Exception) {
        protectionError = e
        false
    }
    if (protected) return true

    val reason = if (protectionError == null) {
        "VpnService.protect() returned false"
    } else {
        "VpnService.protect() threw ${protectionError.javaClass.simpleName}"
    }
    onFailure("SOCKET_PROTECTION_FAILED socket=$description reason=$reason; closing before bind/connect/send", protectionError)
    try {
        close()
    } catch (closeError: Exception) {
        onFailure("SOCKET_CLOSE_FAILED socket=$description after protection rejection", closeError)
    }
    return false
}

internal fun protectTcpSocketOrClose(
    socket: Socket,
    protect: (Socket) -> Boolean,
    description: String,
    onFailure: (String, Throwable?) -> Unit = { message, error -> logE(message, error) },
): Boolean = protectSocketOrClose(
    description = "TCP:$description",
    protect = { protect(socket) },
    close = { socket.close() },
    onFailure = onFailure,
)

internal fun protectUdpSocketOrClose(
    socket: DatagramSocket,
    protect: (DatagramSocket) -> Boolean,
    description: String,
    onFailure: (String, Throwable?) -> Unit = { message, error -> logE(message, error) },
): Boolean = protectSocketOrClose(
    description = "UDP:$description",
    protect = { protect(socket) },
    close = { socket.close() },
    onFailure = onFailure,
)
