package com.speedvpn.app

import java.util.Calendar

internal enum class QuotaType {
    DAILY,
    WEEKLY,
    MONTHLY,
}

internal enum class QuotaResetBehavior {
    AUTO_RESET,
    BLOCK_UNTIL_RESET,
}

internal data class AppQuotaPolicy(
    val packageName: String,
    val uid: Int,
    val quotaType: QuotaType,
    val limitBytes: Long,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val usedBytes: Long,
    val resetBehavior: QuotaResetBehavior,
)

internal object QuotaPeriod {
    fun current(type: QuotaType, nowMillis: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (type) {
                QuotaType.DAILY -> Unit
                QuotaType.WEEKLY -> {
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                }
                QuotaType.MONTHLY -> {
                    set(Calendar.DAY_OF_MONTH, 1)
                }
            }
        }
        val end = Calendar.getInstance().apply {
            timeInMillis = start.timeInMillis
            when (type) {
                QuotaType.DAILY -> add(Calendar.DAY_OF_YEAR, 1)
                QuotaType.WEEKLY -> add(Calendar.DAY_OF_YEAR, 7)
                QuotaType.MONTHLY -> add(Calendar.MONTH, 1)
            }
            add(Calendar.MILLISECOND, -1)
        }
        return start.timeInMillis to end.timeInMillis
    }
}
