package com.healthdecoder.app.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicineScheduleStoreTest {

    private fun schedule(
        daysOfWeek: List<Int>? = null,
        startDate: String? = null,
        endDate: String? = null,
        intervalDays: Int? = null
    ) = MedicineSchedule(
        medicineName = "Tayo 60K",
        patientName = "Jayant",
        dosage = "1 tablet",
        frequency = "Once in 15 days",
        slots = emptyMap(),
        daysOfWeek = daysOfWeek,
        startDate = startDate,
        endDate = endDate,
        intervalDays = intervalDays
    )

    // Real case: "Once in 15 days" anchored to the scan date, day 0.
    @Test
    fun `interval fires on the anchor day and every N days after`() {
        val s = schedule(startDate = "2026-07-30", intervalDays = 15)
        assertTrue(s.isDueByInterval("2026-07-30"))  // day 0
        assertTrue(s.isDueByInterval("2026-08-14"))  // day 15
        assertTrue(s.isDueByInterval("2026-08-29"))  // day 30
    }

    @Test
    fun `interval does not fire on off days`() {
        val s = schedule(startDate = "2026-07-30", intervalDays = 15)
        assertFalse(s.isDueByInterval("2026-07-31"))  // day 1
        assertFalse(s.isDueByInterval("2026-08-13"))  // day 14
        assertFalse(s.isDueByInterval("2026-08-15"))  // day 16
    }

    @Test
    fun `interval before the anchor date is never due`() {
        val s = schedule(startDate = "2026-07-30", intervalDays = 15)
        assertFalse(s.isDueByInterval("2026-07-29"))
    }

    @Test
    fun `no interval or no anchor falls back to always due (never silently stops firing)`() {
        assertTrue(schedule(intervalDays = null).isDueByInterval("2026-08-01"))
        assertTrue(schedule(startDate = null, intervalDays = 15).isDueByInterval("2026-08-01"))
    }

    // isDueToday: intervalDays, when set, REPLACES the day-of-week check entirely — a 15-day
    // cycle isn't tied to any particular weekday, so daysOfWeek is irrelevant once interval is set.
    @Test
    fun `isDueToday uses interval instead of day-of-week when interval is set`() {
        val s = schedule(daysOfWeek = listOf(2, 3), startDate = "2026-07-30", intervalDays = 15)
        // Calendar.DAY_OF_WEEK 5 = Thursday, not in daysOfWeek — but today IS an interval day.
        assertTrue(s.isDueToday(dayOfWeek = 5, todayIso = "2026-08-14"))
        // An interval day check that fails should also fail isDueToday, day-of-week notwithstanding.
        assertFalse(s.isDueToday(dayOfWeek = 2, todayIso = "2026-08-13"))
    }

    @Test
    fun `isDueToday falls back to day-of-week when no interval is set`() {
        val s = schedule(daysOfWeek = listOf(4, 7)) // Wed & Sat only
        assertTrue(s.isDueToday(dayOfWeek = 4, todayIso = "2026-08-14"))
        assertFalse(s.isDueToday(dayOfWeek = 2, todayIso = "2026-08-14"))
    }
}
