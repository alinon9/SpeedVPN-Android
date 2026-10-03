package com.speedvpn.app

import java.util.Calendar

internal enum class QuotaType {
    DAILY,
    WEEKLY,
    MONTHLY,
}

internal object QuotaPeriod {
    data class Bounds(val startMillis: Long, val endMillis: Long)

    fun current(type: QuotaType, nowMillis: Long = System.currentTimeMillis()): Bounds {
        val start = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (type) {
                QuotaType.DAILY -> Unit
                QuotaType.WEEKLY -> {
                    val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) + 5) % 7
                    add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
                }
                QuotaType.MONTHLY -> set(Calendar.DAY_OF_MONTH, 1)
            }
        }
        val end = (start.clone() as Calendar).apply {
            when (type) {
                QuotaType.DAILY -> add(Calendar.DAY_OF_MONTH, 1)
                QuotaType.WEEKLY -> add(Calendar.DAY_OF_MONTH, 7)
                QuotaType.MONTHLY -> add(Calendar.MONTH, 1)
            }
            add(Calendar.MILLISECOND, -1)
        }
        return Bounds(start.timeInMillis, end.timeInMillis)
    }
}
