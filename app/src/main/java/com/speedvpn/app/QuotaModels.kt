package com.speedvpn.app

internal enum class ResetBehavior {
    AUTO_RESET,
    BLOCK_UNTIL_RESET,
}

internal enum class QuotaTrafficDirection {
    DOWNLOAD,
    UPLOAD,
}

/**
 * Distinguishes stream-oriented TCP chunks (which allow prefix admission) from atomic UDP
 * datagrams (which require full-packet admission and allow rollback only when a send failure
 * is explicitly observed by the live process before settlement; if the process terminates
 * between admission and settlement, both TCP and UDP reservations have an uncertain I/O
 * outcome and are conservatively retained to prevent undercounting and quota bypass).
 */
internal enum class QuotaTransport {
    TCP,
    UDP,
}

/**
 * Tracks a single policy row's tentative reservation created by [UsageRepository.reserveQuotaBytes]
 * so post-I/O settlement or post-crash recovery can distinguish in-flight reservations from
 * committed forwarded usage without crossing period boundaries.
 */
internal data class QuotaPolicyReservation(
    val packageName: String,
    val quotaType: QuotaType,
    val periodStartMillis: Long,
    val direction: QuotaTrafficDirection,
    val reservedBytes: Int,
    val transport: QuotaTransport = QuotaTransport.TCP,
    val reservationId: Long = 0L,
)

/**
 * Explicit post-I/O settlement policy for an admitted relay reservation:
 * - [TCP_WRITE_SUCCEEDED]: commits the written prefix to durable quota and increments live forwarded counters.
 * - [TCP_WRITE_FAILED_CONSERVATIVE]: OutputStream.write() does not report partial bytes flushed to the kernel
 *   before throwing; conservatively retains the durable quota reservation to prevent undercounting/bypass while
 *   recording 0 live-forwarded bytes.
 * - [UDP_DELIVERED]: atomic datagram was sent in full; commits durable quota, clears the pending UDP reservation
 *   journal entry, and increments live forwarded counters.
 * - [UDP_DROPPED_OR_SEND_FAILED]: atomic datagram was dropped before send (allowedBytes < payloadBytes) or the
 *   DatagramChannel/DatagramSocket send failed (0 bytes transmitted on the wire); releases the uncommitted durable
 *   reservation, clears the pending UDP journal entry, and records 0 live-forwarded bytes.
 */
internal enum class QuotaSettlementOutcome {
    TCP_WRITE_SUCCEEDED,
    TCP_WRITE_FAILED_CONSERVATIVE,
    UDP_DELIVERED,
    UDP_DROPPED_OR_SEND_FAILED,
}

/** Bytes admitted by the persistent quota gate for one relay read/datagram. */
internal data class QuotaTrafficDecision(
    val allowedBytes: Int,
    val exhaustedPackages: Set<String> = emptySet(),
    val reservations: List<QuotaPolicyReservation> = emptyList(),
)

internal data class AppQuotaPolicy(
    val packageName: String,
    val quotaType: QuotaType,
    val limitBytes: Long,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val usedBytes: Long,
    val resetBehavior: ResetBehavior,
    val downloadBytes: Long = 0L,
    val uploadBytes: Long = 0L,
    /** True once historical period usage has been seeded into the durable byte counters. */
    val baselineInitialized: Boolean = false,
)
