package com.healthdecoder.app.model

/**
 * Medicine-name normalization so the SAME drug written differently across scans is treated as one.
 * The AI (and different prompt versions) emit a drug as "Tab. Concor", "Concor 5mg", "Syp. Alex SF"
 * or "Alex SF Syrup"; without this they became separate reminders/medications. [canonicalKey]
 * collapses them to one key; [cleanDisplay] gives a tidy label (form words dropped).
 */
object MedName {
    // Dosage-form words (without dots) that describe HOW a medicine is taken, not which drug it is.
    // Stripped wherever they appear (prefix or suffix) so "Syp. Cremaffin" == "Cremaffin Syrup".
    private val FORM_TOKENS = setOf(
        "tab", "tablet", "tablets", "cap", "caps", "capsule", "capsules", "syp", "syrup", "syrp",
        "susp", "suspension", "inj", "injection", "oint", "ointment", "sol", "soln", "solution",
        "cream", "gel", "drops", "drop", "lotion", "powder", "sachet", "spray", "soap", "tube"
    )

    // Dosing-timing abbreviations that sometimes get typed alongside the drug name on a
    // prescription line ("Concor OD"). Identity-irrelevant, so stripped from the match key only —
    // never from cleanDisplay, since these aren't part of how a user recognizes the drug's name.
    private val TIMING_TOKENS = setOf("od", "bd", "tds", "tid", "qid", "hs", "sos", "stat", "prn")

    // Strength/product suffixes are DELIBERATELY never added to any stop-word set below: "SR",
    // "XL", "CR", "DSR", "HD", "Plus", "Forte", "Ext", "Gold" etc. name a DIFFERENT product from
    // the plain drug (e.g. "Glycomet" vs "Glycomet SR" are not interchangeable) and must keep
    // forking canonicalKey. Listed here only as documentation of that guarantee, not used at runtime.
    private val PRESERVED_SUFFIXES = setOf("sr", "xl", "cr", "dsr", "hd", "plus", "forte", "ext", "gold")

    private val STRENGTH_REGEX = Regex("""\b(\d+(?:\.\d+)?)\s*(mg|mcg|ml|g|gm|iu|k|units?|%)?\b""")

    private fun isFormWord(token: String) = token.trimEnd('.').lowercase() in FORM_TOKENS

    /** Drops leading/trailing form words: "Tab. Pan D" -> "Pan D", "Alex SF Syrup" -> "Alex SF". */
    fun cleanDisplay(raw: String): String {
        val tokens = raw.trim().split(Regex("""\s+""")).toMutableList()
        while (tokens.isNotEmpty() && isFormWord(tokens.first())) tokens.removeAt(0)
        while (tokens.isNotEmpty() && isFormWord(tokens.last())) tokens.removeAt(tokens.size - 1)
        return tokens.joinToString(" ").ifBlank { raw.trim() }
    }

    /**
     * Canonical DRUG-IDENTITY match key: lowercase, parenthetical generic/salt annotations
     * removed, form words and dosing-timing words removed, strength/number tokens removed,
     * punctuation flattened. "Tab. Concor", "Concor 5mg", "Concor (Bisoprolol 5mg)", "Syp. Alex
     * SF", "Alex SF Syrup" all collapse to "concor" / "alex sf"; "Tayo 60 K" -> "tayo".
     *
     * Deliberately power-agnostic (strength is stripped, not compared) — the SAME drug at a
     * different dose on a later prescription is meant to update the existing medicine in place,
     * not fork into a second one. Use [strengthKey] where two different powers must stay distinct
     * (e.g. collapsing duplicate lines within a single prescription).
     */
    fun canonicalKey(raw: String): String {
        var s = raw.lowercase()
        // Drop parenthetical content entirely: "(Bisoprolol 5mg)" describes composition, not
        // identity — left in, it forks "Concor" and "Concor (Bisoprolol 5mg)" into two keys even
        // though they're the same drug on two different prescriptions.
        s = s.replace(Regex("""\([^)]*\)"""), " ")
        // Drop strength tokens: a number (optional decimal) with an optional unit.
        s = s.replace(Regex("""\b\d+(\.\d+)?\s*(mg|mcg|ml|g|gm|iu|k|units?|%)?\b"""), " ")
        val tokens = s.split(Regex("""[^a-z0-9]+"""))
            .filter { it.isNotBlank() && it !in FORM_TOKENS && it !in TIMING_TOKENS }
        return tokens.joinToString(" ").ifBlank { raw.trim().lowercase() }
    }

    /**
     * Stricter NAME+POWER match key: [canonicalKey] plus a normalized strength token, so two
     * different powers of the same drug (e.g. "Clopitorva 20" vs "Clopitorva 40") stay distinct
     * even though they'd share a [canonicalKey]. Used to collapse duplicate lines within a SINGLE
     * prescription/scan — never to decide whether two prescriptions describe the same medicine.
     * Falls back to [dosage] when [raw] itself carries no strength digit.
     */
    fun strengthKey(raw: String, dosage: String = ""): String {
        val strength = normalizedStrength(raw) ?: normalizedStrength(dosage) ?: ""
        return "${canonicalKey(raw)}|$strength"
    }

    private fun normalizedStrength(text: String): String? {
        if (text.isBlank()) return null
        val match = STRENGTH_REGEX.find(text.lowercase()) ?: return null
        val num = match.groupValues[1].toDoubleOrNull() ?: return null
        val numStr = if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
        return numStr + match.groupValues[2]
    }
}
