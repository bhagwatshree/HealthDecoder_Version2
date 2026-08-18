package com.healthdecoder.app.local.db

import androidx.room.TypeConverter
import com.healthdecoder.app.model.ComparisonResult
import com.healthdecoder.app.model.HealthInsights
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.model.SourceFile
import com.healthdecoder.app.model.TestResults
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room type converters for the nested report structures. They are stored as JSON text
 * columns (same shape as the old reports.json entries), so only the flat, queryable
 * fields (patient, date, category…) become real SQL columns.
 */
class Converters {

    private val gson = Gson()

    private inline fun <reified T> fromJson(json: String?): T? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            null
        }
    }

    // ── String lists ──────────────────────────────────────────────────────────
    @TypeConverter
    fun stringListToJson(value: List<String>?): String = gson.toJson(value ?: emptyList<String>())

    @TypeConverter
    fun jsonToStringList(json: String?): List<String> = fromJson(json) ?: emptyList()

    // ── Medications ───────────────────────────────────────────────────────────
    @TypeConverter
    fun medicationListToJson(value: List<Medication>?): String = gson.toJson(value ?: emptyList<Medication>())

    @TypeConverter
    fun jsonToMedicationList(json: String?): List<Medication> = fromJson(json) ?: emptyList()

    // ── Source files ──────────────────────────────────────────────────────────
    @TypeConverter
    fun sourceFileListToJson(value: List<SourceFile>?): String = gson.toJson(value ?: emptyList<SourceFile>())

    @TypeConverter
    fun jsonToSourceFileList(json: String?): List<SourceFile> = fromJson(json) ?: emptyList()

    // ── Nested result objects (nullable) ──────────────────────────────────────
    @TypeConverter
    fun testResultsToJson(value: TestResults?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToTestResults(json: String?): TestResults? = fromJson(json)

    @TypeConverter
    fun comparisonResultToJson(value: ComparisonResult?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToComparisonResult(json: String?): ComparisonResult? = fromJson(json)

    @TypeConverter
    fun healthInsightsToJson(value: HealthInsights?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToHealthInsights(json: String?): HealthInsights? = fromJson(json)
}
