package com.healthdecoder.app.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.Text

/**
 * Paints over identity-bearing regions of a scanned page before the image is uploaded for
 * analysis, so the AI provider receives the clinical content without the patient's name, the
 * doctor, the lab's letterhead, or account/contact identifiers.
 *
 * BEST-EFFORT, NOT A GUARANTEE — read this before describing the behaviour to a user. Redaction
 * keys off the on-device OCR's line boxes, so anything the OCR misses (or renders as an
 * unlabelled bare name, a logo drawn as artwork, a handwritten name) is not covered and is
 * uploaded. Treat this as materially reducing what leaves the device, not as de-identification
 * in the regulatory sense. The un-redacted originals always stay on the device.
 *
 * The rules deliberately favour UNDER-redacting over destroying clinical data: a page here is
 * about to be read for medication dosages and lab values, and a black box across the wrong line
 * is a wrong dosage, which is far worse than a lab's name reaching the model. Every rule below
 * therefore matches an explicit identity signal rather than guessing from position alone.
 */
object PiiRedactor {

    /**
     * "Name:", "Patient Name -" … — the person's own name. This one REQUIRES the trailing
     * colon/dash: the bare word "Name" is also a lab-table column header ("Test Name  Value  Unit"),
     * and covering that header would take the whole results table's first column with it.
     */
    private val NAME_LABEL = Regex(
        """\b(patient\s*name|pt\.?\s*name|patient|name)\b\s*[:\-]""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Account/record identifiers. Unlike a name these need no punctuation guard — "UHID",
     * "Accession", "Barcode" and friends do not occur in clinical prose, so the label alone is
     * signal enough, and requiring a colon missed real formats like "Accession No. AC-99120".
     */
    private val ID_LABEL = Regex(
        """\b(uhid|mrn|mr\.?\s*no|reg(istration)?\.?\s*no|patient\s*id|pid|op\.?\s*no|ip\.?\s*no|""" +
            """visit\s*id|lab\s*(no|id)|accession|sample\s*id|barcode|srf\s*id|abha|aadhaar|""" +
            """insurance|policy\s*no)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Email addresses and Indian mobile numbers, including the spaced "98765 43210" form. */
    private val CONTACT = Regex(
        """[\w.+-]+@[\w-]+\.[\w.]+|(\+?91[\s-]?)?\b[6-9]\d{4}[\s-]?\d{5}\b"""
    )

    /** The lab/hospital's own name, which lives in the letterhead or the footer. */
    private val PROVIDER_KEYWORD = Regex(
        """\b(laborator(y|ies)|labs|diagnostics?|patholog(y|ists?)|hospitals?|clinics?|""" +
            """polyclinics?|healthcare|health\s*care|medical\s*cent(re|er)|""" +
            """diagnostic\s*cent(re|er)|scan\s*cent(re|er)|nursing\s*homes?)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Headings that legitimately contain a provider-ish word ("PATHOLOGY REPORT", "LAB REPORT").
     * These carry the document's TYPE, which the extraction depends on to classify the report —
     * covering them would cost more than the letterhead is worth.
     */
    private val REPORT_HEADING = Regex(
        """\b(report|result|profile|panel|test|summary|prescription|investigation|""" +
            """h(a)?emogram|findings|impression)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Credential/referral wording that marks a line as being about a clinician. */
    private val DOCTOR_CONTEXT = Regex(
        """\b(ref(erred)?\.?\s*(by|dr)|consultant|physician|pathologist|radiologist|surgeon|""" +
            """m\.?b\.?b\.?s|m\.?d\b|d\.?n\.?b|d\.?g\.?o|m\.?s\b|dm\b|reg\.?\s*no)\b""",
        RegexOption.IGNORE_CASE
    )

    /** A line that simply opens with "Dr Somebody". */
    private val DOCTOR_PREFIX = Regex("""^\s*dr\.?\s+\p{L}""", RegexOption.IGNORE_CASE)

    /**
     * Decides whether one recognised line should be covered. [topFraction] is the line's vertical
     * position as 0..1 down the page, used only to separate the letterhead/signature bands from
     * the body — a bare "Dr. …" in the body of a prescription is very often a MEDICINE brand
     * ("Dr. Reddy's"), so it is left alone there and only covered in the bands where a clinician's
     * name actually appears.
     */
    internal fun shouldRedact(rawLine: String, topFraction: Float): Boolean {
        val line = rawLine.trim()
        if (line.isBlank()) return false

        if (NAME_LABEL.containsMatchIn(line)) return true
        if (ID_LABEL.containsMatchIn(line)) return true
        if (CONTACT.containsMatchIn(line)) return true
        if (DOCTOR_CONTEXT.containsMatchIn(line)) return true

        val inLetterheadOrFooter = topFraction < 0.20f || topFraction > 0.80f
        if (PROVIDER_KEYWORD.containsMatchIn(line) && !REPORT_HEADING.containsMatchIn(line)) return true
        if (DOCTOR_PREFIX.containsMatchIn(line) && inLetterheadOrFooter) return true

        return false
    }

    /**
     * Returns a copy of [source] with identity regions painted out, or null when the page has
     * nothing to redact — the caller then uploads the ORIGINAL bytes rather than a re-encoded
     * copy, avoiding a pointless round of JPEG generation loss on a page the model has to read
     * small printed digits from.
     */
    fun redactedCopy(source: Bitmap, recognised: Text): Bitmap? {
        val pageHeight = source.height.takeIf { it > 0 } ?: return null
        val boxes = mutableListOf<Rect>()
        var covered = 0

        for (block in recognised.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (!shouldRedact(line.text, box.top.toFloat() / pageHeight)) continue
                // Pad slightly: ML Kit's box hugs the glyphs, leaving ascenders/descenders and
                // anti-aliased edges legible at the boundary.
                boxes.add(Rect(box.left - 4, box.top - 4, box.right + 4, box.bottom + 4))
                covered++
            }
        }
        if (boxes.isEmpty()) return null

        Log.i("ScanDiag", "REDACTED $covered line(s) of identity data before upload")
        val out = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return null
        val canvas = Canvas(out)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        boxes.forEach { canvas.drawRect(it, paint) }
        return out
    }
}
