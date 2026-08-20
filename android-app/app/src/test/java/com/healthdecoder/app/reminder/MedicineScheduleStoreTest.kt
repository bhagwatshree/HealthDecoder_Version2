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

    // isDueToday requires BOTH the day-of-week script and the interval cadence.
    //
    // This reverses what this file asserted before, deliberately. The old rule was "interval
    // replaces the day check", which is right for a genuine 15-day cycle (not weekday-aligned, so
    // daysOfWeek is null anyway and nothing is lost) but discarded explicitly prescribed days
    // whenever both happened to be set. A real Acitrom script — "5 days a week, Thursday & Sunday
    // off" — came back with correct days AND intervalDays=1, and since "every 1 days" is true
    // every day, the days were never consulted and it reminded on Thursday.
    //
    // When the two disagree, the named days win by being required: for an anticoagulant, a
    // reminder that withholds a dose the script didn't call for is a safer error than one that
    // adds a dose the doctor excluded.
    @Test
    fun `isDueToday requires both the named days and the interval`() {
        val s = schedule(daysOfWeek = listOf(2, 3), startDate = "2026-07-30", intervalDays = 15)
        // 2026-08-14 IS an interval day, but Thursday (5) is not one of the named days.
        assertFalse(s.isDueToday(dayOfWeek = 5, todayIso = "2026-08-14"))
        // A named day that isn't an interval day is likewise not due.
        assertFalse(s.isDueToday(dayOfWeek = 2, todayIso = "2026-08-13"))
    }

    @Test
    fun `interval alone still governs when no days are named`() {
        val s = schedule(daysOfWeek = null, startDate = "2026-07-30", intervalDays = 15)
        assertTrue(s.isDueToday(dayOfWeek = 5, todayIso = "2026-08-14"))
        assertFalse(s.isDueToday(dayOfWeek = 5, todayIso = "2026-08-13"))
    }

    /**
     * The reported defect. "Every 1 days" is what having no interval already means, so it must not
     * count as a constraint — otherwise it silently overrides the days the doctor did name.
     */
    @Test
    fun `an interval of one day does not override the named days`() {
        val s = schedule(daysOfWeek = listOf(2, 3, 4, 6, 7), startDate = "2026-08-13", intervalDays = 1)
        assertFalse(s.isDueToday(dayOfWeek = 5, todayIso = "2026-08-20")) // Thursday: excluded
        assertFalse(s.isDueToday(dayOfWeek = 1, todayIso = "2026-08-23")) // Sunday: excluded
        assertTrue(s.isDueToday(dayOfWeek = 2, todayIso = "2026-08-17"))  // Monday: due
    }

    @Test
    fun `isDueToday falls back to day-of-week when no interval is set`() {
        val s = schedule(daysOfWeek = listOf(4, 7)) // Wed & Sat only
        assertTrue(s.isDueToday(dayOfWeek = 4, todayIso = "2026-08-14"))
        assertFalse(s.isDueToday(dayOfWeek = 2, todayIso = "2026-08-14"))
    }
}
