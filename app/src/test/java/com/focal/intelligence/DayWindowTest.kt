package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayWindowTest {

    @Test
    fun `at 3 AM returns today's window starting at 2 AM`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
        }
        val (start, end) = DayWindow.getWindow(cal.timeInMillis)

        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))
        assertEquals(24 * 60 * 60 * 1000L, end - start)
    }

    @Test
    fun `at 1 AM returns yesterday's window`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 30)
        }
        val (start, _) = DayWindow.getWindow(cal.timeInMillis)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2, startCal.get(Calendar.HOUR_OF_DAY))

        val nowCal = Calendar.getInstance().apply { timeInMillis = cal.timeInMillis }
        assertTrue(startCal.get(Calendar.DAY_OF_YEAR) < nowCal.get(Calendar.DAY_OF_YEAR) ||
            startCal.get(Calendar.YEAR) < nowCal.get(Calendar.YEAR))
    }

    @Test
    fun `at 11 PM returns today's window`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
        }
        val (start, _) = DayWindow.getWindow(cal.timeInMillis)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(cal.get(Calendar.DAY_OF_YEAR), startCal.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun `window is exactly 24 hours`() {
        val (start, end) = DayWindow.getWindow()
        assertEquals(24 * 60 * 60 * 1000L, end - start)
    }
}
