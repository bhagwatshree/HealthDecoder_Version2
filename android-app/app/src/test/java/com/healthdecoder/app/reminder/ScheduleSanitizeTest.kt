package com.healthdecoder.app.reminder

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gson populates fields by reflection and ignores Kotlin nullability, so JSON that omits a key
 * leaves a non-null Kotlin field holding null. Nothing fails at parse time; it detonates at the
 * first read. That took out the Reminders and Doctor Appointments screens on open — an NPE in
 * dedupeCanonical() calling patientName.trim(), with nothing in the stack trace to suggest JSON.
 *
 * These tests parse real JSON rather than constructing objects directly, because only a parse
 * can produce the impossible state (a null in a non-null field) that the fix exists to absorb.
 */
class ScheduleSanitizeTest {

    private val gson = Gson()

    private fun medicines(json: String): List<MedicineSchedule> =
        gson.fromJson(json, object : TypeToken<List<MedicineSchedule>>() {}.type)

    private fun appointments(json: String): List<AppointmentSchedule> =
        gson.fromJson(json, object : TypeToken<List<AppointmentSchedule>>() {}.type)

    @Test
    fun `Gson really does put null into a non-null Kotlin field`() {
        // Guards the premise of every other test here: if a Gson upgrade ever starts rejecting
        // this instead, the sanitizers become dead weight and this test says so loudly.
        val parsed = medicines("""[{"medicineName":"Pan D"}]""")
        @Suppress("SENSELESS_COMPARISON")
        assertNull("expected Gson to leave the missing field null", parsed[0].patientName as String?)
    }

    @Test
    fun `missing medicine fields become blank instead of crashing`() {
        val clean = medicines("""[{"medicineName":"Pan D"}]""").mapNotNull { it.sanitized() }

        assertEquals(1, clean.size)
        assertEquals("Pan D", clean[0].medicineName)
        assertEquals("", clean[0].patientName)
        assertEquals("", clean[0].dosage)
        assertEquals("", clean[0].frequency)
        assertTrue(clean[0].slots.isEmpty())
    }

    @Test
    fun `medicine with no usable name is dropped rather than kept as a landmine`() {
        val clean = medicines("""[{"patientName":"Asha"},{"medicineName":"  "},{"medicineName":"Pan D"}]""")
            .mapNotNull { it.sanitized() }

        assertEquals(listOf("Pan D"), clean.map { it.medicineName })
    }

    @Test
    fun `medicine display name is trimmed`() {
        val clean = medicines("""[{"medicineName":"  Pan D  ","patientName":"Asha"}]""")
            .mapNotNull { it.sanitized() }

        assertEquals("Pan D", clean[0].medicineName)
    }

    @Test
    fun `a null entry in the array does not crash the whole load`() {
        val clean = medicines("""[null,{"medicineName":"Pan D"}]""").mapNotNull { it.sanitized() }

        assertEquals(1, clean.size)
    }

    @Test
    fun `missing appointment fields become blank instead of crashing`() {
        val clean = appointments("""[{"date":"2026-06-24"}]""").mapNotNull { it.sanitized() }

        assertEquals(1, clean.size)
        assertEquals("", clean[0].doctorName)
        assertEquals("", clean[0].time)
        assertEquals("", clean[0].place)
        assertEquals("", clean[0].patientName)
        assertEquals("None", clean[0].recurrence)
        assertTrue("a missing id must be regenerated", clean[0].id.isNotBlank())
    }

    @Test
    fun `appointment with no date is dropped`() {
        // Without a date it cannot be sorted, displayed or scheduled — keeping it just moves the
        // crash into the alarm code.
        val clean = appointments("""[{"doctorName":"Dr Rao"},{"date":"2026-06-24"}]""")
            .mapNotNull { it.sanitized() }

        assertEquals(listOf("2026-06-24"), clean.map { it.date })
    }

    @Test
    fun `well-formed records pass through untouched`() {
        val json = """[{"medicineName":"Pan D","patientName":"Asha","dosage":"40mg",
            "frequency":"Once daily","slots":{"Morning":{"enabled":true,"hour":8,"minute":0}}}]"""
        val clean = medicines(json).mapNotNull { it.sanitized() }

        assertEquals("Asha", clean[0].patientName)
        assertEquals("40mg", clean[0].dosage)
        assertNotNull(clean[0].slots["Morning"])
        assertTrue(clean[0].slots.getValue("Morning").enabled)
    }
}
