package com.healthdecoder.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redactor's real risk is not under-redacting — it is OVER-redacting: a black box across a
 * medication row or a lab value is a wrong dose or a lost result, which is far worse than a lab's
 * name reaching the model. The "must survive" cases below are therefore the important half of
 * this file, and should be treated as regressions if they ever start failing.
 *
 * [PiiRedactor.shouldRedact]'s second argument is the line's vertical position as 0..1 down the
 * page; 0.05 stands in for the letterhead band, 0.5 for the body, 0.95 for the footer/signature.
 */
class PiiRedactorTest {

    private fun body(line: String) = PiiRedactor.shouldRedact(line, 0.5f)
    private fun letterhead(line: String) = PiiRedactor.shouldRedact(line, 0.05f)
    private fun footer(line: String) = PiiRedactor.shouldRedact(line, 0.95f)

    // ── Must be covered ───────────────────────────────────────────────────────

    @Test fun `covers the patient name wherever it appears`() {
        assertTrue(body("Patient Name : Jayant Bhagwat"))
        assertTrue(letterhead("Name: Mrs. Sunita Deshmukh"))
        assertTrue(body("Pt. Name - RAMESH KUMAR"))
    }

    @Test fun `covers account and record identifiers`() {
        assertTrue(body("UHID: 20260819-4471"))
        assertTrue(body("Lab No : 5512348"))
        assertTrue(body("Accession No. AC-99120"))
        assertTrue(body("Patient ID: P/2026/8841"))
    }

    @Test fun `covers contact details`() {
        assertTrue(body("Ph: 9876543210"))
        assertTrue(body("reports@metropolisindia.com"))
        assertTrue(body("+91 98765 43210"))
    }

    @Test fun `covers the referring or signing clinician`() {
        assertTrue(body("Ref. By: Dr. A. K. Sharma"))
        assertTrue(body("Consultant Pathologist"))
        assertTrue(footer("Dr. Meera Iyer, MD"))
        assertTrue(letterhead("Dr. Rajesh Nair"))
    }

    @Test fun `covers the lab letterhead and footer`() {
        assertTrue(letterhead("SRL Diagnostics Pvt. Ltd."))
        assertTrue(letterhead("Apollo Hospitals, Navi Mumbai"))
        assertTrue(footer("Sunrise Pathology Laboratory"))
    }

    // ── Must survive: covering any of these corrupts the extraction ───────────

    @Test fun `keeps report headings that merely contain a provider word`() {
        assertFalse(letterhead("PATHOLOGY REPORT"))
        assertFalse(letterhead("LABORATORY TEST REPORT"))
        assertFalse(letterhead("DIAGNOSTIC IMAGING REPORT"))
    }

    @Test fun `keeps lab result rows`() {
        assertFalse(body("Haemoglobin        13.5    g/dL     13.0 - 17.0"))
        assertFalse(body("Total Cholesterol  212     mg/dL    < 200      High"))
        assertFalse(body("TSH (Ultra Sensitive)   4.12   uIU/mL"))
    }

    @Test fun `keeps medication rows including pharma brands that start with Dr`() {
        assertFalse(body("Tab. Dolo 650mg  1-0-1  x 5 days"))
        // "Dr. Reddy's" is a manufacturer printed in the body of a prescription, not a clinician.
        assertFalse(body("Dr. Reddy's Omez 20mg  0-0-1"))
        assertFalse(body("Cap. Amifru 40mg   1-0-0   till follow-up"))
    }

    @Test fun `keeps dates and their labels`() {
        assertFalse(body("Reported on : 19/08/2026"))
        assertFalse(body("Sample Collected : 18-08-2026 09:14"))
        assertFalse(body("Date of Examination: 12 March 2026"))
    }

    @Test fun `keeps clinical narrative`() {
        assertFalse(body("Mild fatty liver changes noted in segment IV."))
        assertFalse(body("Advise: repeat lipid profile after 3 months."))
        assertFalse(body("No focal lesion identified."))
    }

    @Test fun `ignores blank lines`() {
        assertFalse(body(""))
        assertFalse(body("   "))
    }
}
