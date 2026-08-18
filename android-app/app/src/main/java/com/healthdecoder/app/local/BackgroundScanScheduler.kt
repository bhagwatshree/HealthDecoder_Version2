package com.healthdecoder.app.local

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.util.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ScanJobStatus {
    UPLOADING, OCR, SAVING, COMPLETED, ERROR
}

data class ScanJob(
    val id: String = UUID.randomUUID().toString(),
    val patientName: String,
    val scanType: String,
    val category: String,
    var status: ScanJobStatus,
    var progress: Float, // 0.0 to 1.0
    var error: String? = null,
    var resultReportId: String? = null,
    // When the scan was rejected as a duplicate, the id of the already-saved report so the UI can
    // offer "View existing" — letting the user jump to that document (e.g. to delete it).
    var duplicateReportId: String? = null
)

object BackgroundScanScheduler {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    val activeJobs = mutableStateListOf<ScanJob>()

    private val _onJobCompleted = MutableSharedFlow<String>() // emits reportId
    val onJobCompleted: SharedFlow<String> = _onJobCompleted.asSharedFlow()

    fun startScan(
        context: Context,
        pageUris: List<Uri>,
        sourceUris: List<Triple<Uri, String, String>>,
        referenceText: String,
        scanType: String,
        category: String,
        patientName: String
    ): String {
        val scanJob = ScanJob(
            patientName = patientName.ifBlank { "Auto-detect" },
            scanType = scanType,
            category = category,
            status = ScanJobStatus.UPLOADING,
            progress = 0.1f
        )
        activeJobs.add(scanJob)

        val appContext = context.applicationContext

        scope.launch(Dispatchers.Default) {
            try {
                // Step 1: Compressing and reading file bytes
                updateJobProgress(scanJob.id, ScanJobStatus.UPLOADING, 0.2f)
                
                val pageData = pageUris.mapNotNull { uri ->
                    ImageUtil.compressForScan(appContext, uri)?.let { it to "image/jpeg" }
                }
                
                val sourceData = sourceUris.mapNotNull { (uri, name, mime) ->
                    val b = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (b != null) Triple(b, name, mime) else null
                }
                
                if (pageData.isEmpty() && referenceText.isBlank()) {
                    updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = "Failed to read the selected file(s).")
                    return@launch
                }

                // Step 2: OCR and AI analysis
                updateJobProgress(scanJob.id, ScanJobStatus.OCR, 0.5f)
                
                val savedReports = LocalRepository.saveScan(
                    appContext,
                    pageData,
                    sourceData,
                    referenceText,
                    scanType,
                    category,
                    patientName
                )
                
                // Step 3: Saving to secure SQLite db
                updateJobProgress(scanJob.id, ScanJobStatus.SAVING, 0.8f)
                
                val reportId = savedReports.firstOrNull()?.id
                updateJobProgress(scanJob.id, ScanJobStatus.COMPLETED, 1.0f, resultReportId = reportId)
                
                if (reportId != null) {
                    _onJobCompleted.emit(reportId)
                }
            } catch (dup: DuplicateReportException) {
                val who = dup.existing.patientName ?: "this patient"
                val date = dup.existing.reportDate ?: ""
                val err = "This report is already saved for $who${if (date.isNotBlank()) " (dated $date)" else ""}"
                updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = err, duplicateReportId = dup.existing.id)
            } catch (oom: OutOfMemoryError) {
                updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = "Too many pages. Please scan fewer documents.")
            } catch (ai: com.healthdecoder.app.ai.BackendAiClient.BackendAiException) {
                // Surface the backend's actual reason (daily quota reached, server down, etc.)
                // instead of a generic message — this used to be swallowed into a silent
                // localFallback() save with a wrong date/category (see OcrEngine.scanChunk).
                updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = ai.message ?: "Analysis failed. Please try again.")
            } catch (e: Exception) {
                e.printStackTrace()
                updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = "Scan failed. Please check internet connection.")
            }
        }
        
        return scanJob.id
    }

    /**
     * Upload-only counterpart of [startScan]: stores the file(s) as a report WITHOUT any AI
     * analysis (no API calls), for archiving old data. The report is flagged unanalyzed and can
     * be analyzed later from its detail screen. Emits the new report id on [onJobCompleted].
     */
    fun startUpload(
        context: Context,
        pageUris: List<Uri>,
        sourceUris: List<Triple<Uri, String, String>>,
        category: String,
        patientName: String
    ): String {
        val scanJob = ScanJob(
            patientName = patientName.ifBlank { "Unknown Patient" },
            scanType = "upload",
            category = category,
            status = ScanJobStatus.UPLOADING,
            progress = 0.2f
        )
        activeJobs.add(scanJob)
        val appContext = context.applicationContext

        scope.launch(Dispatchers.Default) {
            try {
                val pageData = pageUris.mapNotNull { uri ->
                    ImageUtil.compressForScan(appContext, uri)?.let { it to "image/jpeg" }
                }
                val sourceData = sourceUris.mapNotNull { (uri, name, mime) ->
                    val b = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (b != null) Triple(b, name, mime) else null
                }
                if (pageData.isEmpty() && sourceData.isEmpty()) {
                    updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = "Failed to read the selected file(s).")
                    return@launch
                }
                updateJobProgress(scanJob.id, ScanJobStatus.SAVING, 0.8f)
                val report = LocalRepository.saveUploadOnly(appContext, pageData, sourceData, category, patientName)
                updateJobProgress(scanJob.id, ScanJobStatus.COMPLETED, 1.0f, resultReportId = report.id)
                _onJobCompleted.emit(report.id)
            } catch (dup: DuplicateReportException) {
                val who = dup.existing.patientName ?: "this patient"
                updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = "This file is already saved for $who.", duplicateReportId = dup.existing.id)
            } catch (e: Exception) {
                e.printStackTrace()
                updateJobProgress(scanJob.id, ScanJobStatus.ERROR, 1.0f, error = "Upload failed. Please try again.")
            }
        }
        return scanJob.id
    }

    fun removeJob(jobId: String) {
        scope.launch(Dispatchers.Main) {
            activeJobs.removeAll { it.id == jobId }
        }
    }

    private fun updateJobProgress(
        jobId: String,
        status: ScanJobStatus,
        progress: Float,
        error: String? = null,
        resultReportId: String? = null,
        duplicateReportId: String? = null
    ) {
        scope.launch(Dispatchers.Main) {
            val index = activeJobs.indexOfFirst { it.id == jobId }
            if (index != -1) {
                val job = activeJobs[index]
                activeJobs[index] = job.copy(
                    status = status,
                    progress = progress,
                    error = error,
                    resultReportId = resultReportId,
                    duplicateReportId = duplicateReportId
                )
            }
        }
    }
}
