package com.healthdecoder.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitConverterTest {

    @Test
    fun `notation variants collapse to the same canonical unit`() {
        val mgdl = UnitConverter.canonicalizeUnitString("mg/dL")
        assertEquals(mgdl, UnitConverter.canonicalizeUnitString("mg %"))
        assertEquals(mgdl, UnitConverter.canonicalizeUnitString("mg%"))
        assertEquals(mgdl, UnitConverter.canonicalizeUnitString("mg/100mL"))

        val gdl = UnitConverter.canonicalizeUnitString("g/dL")
        assertEquals(gdl, UnitConverter.canonicalizeUnitString("gm %"))
        assertEquals(gdl, UnitConverter.canonicalizeUnitString("gm/dl"))

        // micro sign, Greek mu, and the "u"/"m" spellings of TSH units all coincide
        assertEquals(UnitConverter.canonicalizeUnitString("uIU/mL"), UnitConverter.canonicalizeUnitString("µIU/mL"))
        assertEquals(UnitConverter.canonicalizeUnitString("uIU/mL"), UnitConverter.canonicalizeUnitString("mIU/L"))
    }

    @Test
    fun `same unit (ignoring notation) needs no conversion`() {
        // mg% and mg/dL are identical, so glucose should not be "converted" between them
        assertEquals(115f, UnitConverter.convert("Blood Sugar", 115f, "mg %", "mg/dL")!!, 0.001f)
    }

    @Test
    fun `glucose converts between mmol per L and mg per dL`() {
        // 1 mmol/L = 18.016 mg/dL
        assertEquals(108.1f, UnitConverter.convert("Blood Sugar", 6.0f, "mmol/L", "mg/dL")!!, 0.1f)
        assertEquals(6.0f, UnitConverter.convert("Blood Sugar", 108.1f, "mg/dL", "mmol/L")!!, 0.01f)
    }

    @Test
    fun `T3 converts nmol per L to ng per mL toward the standard`() {
        // Deenanath Mangeshkar 0.68 nmol/L standardized to Dhande's ng/mL (1 nmol/L = 0.651 ng/mL)
        assertEquals(0.443f, UnitConverter.convert("T3", 0.68f, "nmol/L", "ng/mL")!!, 0.01f)
    }

    @Test
    fun `converting to a unit and back round-trips`() {
        val original = 200f
        val toMmol = UnitConverter.convert("Total Cholesterol", original, "mg/dL", "mmol/L")!!
        val back = UnitConverter.convert("Total Cholesterol", toMmol, "mmol/L", "mg/dL")!!
        assertEquals(original, back, 0.01f)
    }

    @Test
    fun `unknown unit pair returns null rather than a guess`() {
        assertNull(UnitConverter.convert("T3", 1.0f, "made-up-unit", "ng/mL"))
        assertNull(UnitConverter.convert("Unknown Test", 1.0f, "mmol/L", "mg/dL"))
    }

    @Test
    fun `category matching is case-insensitive`() {
        assertTrue(UnitConverter.convert("blood sugar", 6.0f, "mmol/L", "mg/dL") != null)
    }
}
