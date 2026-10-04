package com.speedvpn.app

internal enum class ResetBehavior {
    AUTO_RESET,
    BLOCK_UNTIL_RESET,
}

internal data class AppQuotaPolicy(
    val packageName: String,
    val quotaType: QuotaType,
    val limitBytes: Long,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val usedBytes: Long,
    val resetBehavior: ResetBehavior,
)
