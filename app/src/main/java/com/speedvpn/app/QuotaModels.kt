package com.speedvpn.app

internal enum class ResetBehavior {
    AUTO_RESET,
    BLOCK_UNTIL_RESET,
}

internal enum class QuotaTrafficDirection {
    DOWNLOAD,
    UPLOAD,
}

/** Bytes admitted by the persistent quota gate for one relay read/datagram. */
internal data class QuotaTrafficDecision(
    val allowedBytes: Int,
    val exhaustedPackages: Set<String> = emptySet(),
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
)
