package com.focal.intelligence

import java.util.Calendar

object DayWindow {

    private const val RESET_HOUR = 2
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun getWindow(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (cal.get(Calendar.HOUR_OF_DAY) < RESET_HOUR) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, RESET_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return start to (start + DAY_MS)
    }
}
