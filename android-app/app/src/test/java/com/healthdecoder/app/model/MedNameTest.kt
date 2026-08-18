package com.healthdecoder.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Real-world case: the same drug is scanned once with a plain name ("Concor 5 mg", a discharge
 * summary) and once with the generic/salt name bracketed after it ("CONCOR TAB 5MG (Bisoprolol
 * 5mg)", the follow-up prescription) — canonicalKey must treat both as the same drug so the
 * newer scan updates the existing medicine instead of forking a duplicate.
 */
class MedNameTest {

    @Test
    fun `bracketed generic-salt annotation does not fork identity`() {
        assertEquals(MedName.canonicalKey("Concor 5 mg"), MedName.canonicalKey("CONCOR TAB 5MG (Bisoprolol 5mg)"))
        assertEquals(MedName.canonicalKey("Amifru 40mg"), MedName.canonicalKey("Amifru (Amiloride 5mg + Frusemide 40mg)"))
        assertEquals(MedName.canonicalKey("Clopitorva 20mg"), MedName.canonicalKey("Clopitorva (Atorvastatin 20mg + Clopidogrel 75mg)"))
        assertEquals(MedName.canonicalKey("Carbimazol 5mg"), MedName.canonicalKey("Carbimazol (5mg)"))
        assertEquals(MedName.canonicalKey("Tab. Acitrom"), MedName.canonicalKey("Acitrom (Acenocoumarol 1mg)"))
    }

    @Test
    fun `different products never merge`() {
        assertNotEquals(MedName.canonicalKey("Ecosprin"), MedName.canonicalKey("Ecosprin Gold"))
        assertNotEquals(MedName.canonicalKey("Glycomet"), MedName.canonicalKey("Glycomet SR"))
        assertNotEquals(MedName.canonicalKey("Pan"), MedName.canonicalKey("Pan D"))
    }

    @Test
    fun `dosing-timing abbreviations are noise, not identity`() {
        assertEquals(MedName.canonicalKey("Concor"), MedName.canonicalKey("Concor OD"))
        assertEquals(MedName.canonicalKey("Ecosprin Gold"), MedName.canonicalKey("Ecosprin Gold HS"))
    }

    @Test
    fun `strengthKey keeps two powers on one prescription distinct`() {
        assertNotEquals(MedName.strengthKey("Clopitorva 20"), MedName.strengthKey("Clopitorva 40"))
    }

    @Test
    fun `strengthKey still collapses a bracket-suffix duplicate at the same power`() {
        assertEquals(MedName.strengthKey("Concor 5mg"), MedName.strengthKey("Concor (Bisoprolol) 5mg"))
        assertEquals(MedName.strengthKey("Concor 5mg"), MedName.strengthKey("Concor (Bisoprolol 5.0 MG)"))
    }

    @Test
    fun `strengthKey falls back to dosage when name carries no strength`() {
        assertEquals(MedName.strengthKey("Carbimazol", "5mg"), MedName.strengthKey("Carbimazol 5mg", ""))
    }

    @Test
    fun `cleanDisplay is unaffected by the identity-key changes`() {
        assertEquals("Pan D", MedName.cleanDisplay("Tab. Pan D"))
        assertEquals("Concor (Bisoprolol 5mg)", MedName.cleanDisplay("Concor (Bisoprolol 5mg)"))
    }
}
