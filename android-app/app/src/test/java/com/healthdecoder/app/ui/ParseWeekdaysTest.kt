package com.healthdecoder.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseWeekdays] decides which days a medicine reminder actually fires on: its result is stored
 * as MedicineSchedule.daysOfWeek and checked by isDueToday. Getting it wrong either nags a patient
 * to take a drug on a day the doctor excluded, or hides a dose they needed — so the off-day cases
 * below are safety behaviour, not formatting preferences.
 *
 * Codes are Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon, 3=Tue, 4=Wed, 5=Thu, 6=Fri, 7=Sat.
 * An empty result means "every day".
 */
class ParseWeekdaysTest {

    private val everyday = emptyList<Int>()

    @Test fun `explicit weekly schedule is taken as-is`() {
        assertEquals(listOf(4, 7), parseWeekdays(listOf("Wednesday", "Saturday"), "1-0-0"))
    }

    @Test fun `Everyday means no day restriction`() {
        assertEquals(everyday, parseWeekdays(listOf("Everyday"), "1-0-1"))
        assertEquals(everyday, parseWeekdays(listOf("Daily"), "1-0-1"))
    }

    /**
     * "Everyday" is the schema's default and the model emits it even for a medicine whose
     * frequency text then restricts the days. It must not suppress that text: treating it as a
     * final answer is what kept the Acitrom reminder firing on Thursdays after the first fix.
     */
    @Test fun `Everyday does not suppress off-days named in the frequency text`() {
        val days = parseWeekdays(listOf("Everyday"), "5 days a week, THURSDAY & SUNDAY OFF")
        assertEquals(listOf(2, 3, 4, 6, 7), days)
    }

    @Test fun `plain daily prescription is unrestricted`() {
        assertEquals(everyday, parseWeekdays(emptyList(), "1-0-1"))
        assertEquals(everyday, parseWeekdays(emptyList(), "twice a day after food"))
    }

    /**
     * The reported defect: an anticoagulant written as "5 days a week, Thursday & Sunday off"
     * reminded on Thursday. The old parse searched the schedule and the frequency text together
     * for day names, so the two days named as EXCLUDED were added as active.
     */
    @Test fun `off days named in free text are excluded, not included`() {
        val days = parseWeekdays(emptyList(), "TILL 20 OCT 2026, 5 DAYS A WEEK, THURSDAY & SUNDAY OFF")
        assertEquals(listOf(2, 3, 4, 6, 7), days)   // Mon, Tue, Wed, Fri, Sat
    }

    @Test fun `structured schedule wins over off-day wording in the frequency text`() {
        val days = parseWeekdays(
            listOf("Monday", "Tuesday", "Wednesday", "Friday", "Saturday"),
            "5 days a week, Thursday & Sunday off"
        )
        assertEquals(listOf(2, 3, 4, 6, 7), days)
    }

    @Test fun `other exclusion wordings behave the same`() {
        assertEquals(listOf(2, 3, 4, 5, 7), parseWeekdays(emptyList(), "daily except Friday and Sunday"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), parseWeekdays(emptyList(), "every day, skip Saturday"))
    }

    /** "mon" inside "month" used to register as Monday. */
    @Test fun `words merely containing a day abbreviation are not days`() {
        assertEquals(everyday, parseWeekdays(emptyList(), "once a month"))
        assertEquals(everyday, parseWeekdays(emptyList(), "monitor saturation levels"))
    }

    @Test fun `abbreviations and plurals are recognised`() {
        assertEquals(listOf(2, 5), parseWeekdays(listOf("Mon", "Thu"), ""))
        assertEquals(listOf(3, 6), parseWeekdays(emptyList(), "Tuesdays and Fridays"))
    }

    /** Degenerate input must not collapse to the empty "every day" answer. */
    @Test fun `excluding every day falls back to the named days`() {
        val days = parseWeekdays(emptyList(), "Mon Tue Wed Thu Fri Sat Sun off")
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), days)
    }
}
