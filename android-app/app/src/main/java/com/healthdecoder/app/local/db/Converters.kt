package com.healthdecoder.app.local.db

import androidx.room.TypeConverter
import com.healthdecoder.app.model.ComparisonResult
import com.healthdecoder.app.model.HealthInsights
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.model.SourceFile
import com.healthdecoder.app.model.TestResults
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Room type converters for the nested report structures. They are stored as JSON text
 * columns (same shape as the old reports.json entries), so only the flat, queryable
 * fields (patient, date, category…) become real SQL columns.
 *
 * Every read passes an explicit [Type]. The previous version used a single
 * `inline fun <reified T> fromJson(json)` built on `object : TypeToken<T>() {}.type`, which
 * looks correct and is silently broken: Kotlin's reification does not reach the anonymous
 * class's generic supertype, so Gson received the type VARIABLE T rather than the real type.
 * Falling back to its default Object adapter, it then returned a LinkedTreeMap (or an
 * ArrayList of them) for every column — which is not a TestResults, and not a List<Medication>.
 *
 * Nothing failed at that point. The wrong object was handed back through an unchecked cast and
 * only blew up later, at the first real use, as a bare ClassCastException nowhere near the
 * converter — and the `catch (Exception)` below never saw it, because the throw happens outside
 * this class. Scanning was the visible casualty: extraction would succeed, then saving it read
 * the existing reports back, and the scan died with "check your internet connection".
 *
 * Writes were always correct (gson.toJson uses the runtime class), so stored rows are valid
 * JSON and no migration is needed — this only restores the ability to read them back.
 */
class Converters {

    private val gson = Gson()

    private companion object {
        val STRING_LIST: Type = TypeToken.getParameterized(List::class.java, String::class.java).type
        val MEDICATION_LIST: Type = TypeToken.getParameterized(List::class.java, Medication::class.java).type
        val SOURCE_FILE_LIST: Type = TypeToken.getParameterized(List::class.java, SourceFile::class.java).type
    }

    private fun <T> fromJson(json: String?, type: Type): T? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson<T>(json, type)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── String lists ──────────────────────────────────────────────────────────
    @TypeConverter
    fun stringListToJson(value: List<String>?): String = gson.toJson(value ?: emptyList<String>())

    @TypeConverter
    fun jsonToStringList(json: String?): List<String> = fromJson<List<String>>(json, STRING_LIST) ?: emptyList()

    // ── Medications ───────────────────────────────────────────────────────────
    @TypeConverter
    fun medicationListToJson(value: List<Medication>?): String = gson.toJson(value ?: emptyList<Medication>())

    @TypeConverter
    fun jsonToMedicationList(json: String?): List<Medication> =
        fromJson<List<Medication>>(json, MEDICATION_LIST) ?: emptyList()

    // ── Source files ──────────────────────────────────────────────────────────
    @TypeConverter
    fun sourceFileListToJson(value: List<SourceFile>?): String = gson.toJson(value ?: emptyList<SourceFile>())

    @TypeConverter
    fun jsonToSourceFileList(json: String?): List<SourceFile> =
        fromJson<List<SourceFile>>(json, SOURCE_FILE_LIST) ?: emptyList()

    // ── Nested result objects (nullable) ──────────────────────────────────────
    @TypeConverter
    fun testResultsToJson(value: TestResults?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToTestResults(json: String?): TestResults? = fromJson(json, TestResults::class.java)

    @TypeConverter
    fun comparisonResultToJson(value: ComparisonResult?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToComparisonResult(json: String?): ComparisonResult? = fromJson(json, ComparisonResult::class.java)

    @TypeConverter
    fun healthInsightsToJson(value: HealthInsights?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToHealthInsights(json: String?): HealthInsights? = fromJson(json, HealthInsights::class.java)
}
