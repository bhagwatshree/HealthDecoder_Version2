package com.healthdecoder.app.local

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Runs the Settings screen's bulk maintenance operations (fixing quota-degraded reports,
 * recovering missing panels from pre-fix multi-page scans) via [BackgroundTasks], so navigating
 * away from Settings mid-run — entirely plausible for something that can take minutes across
 * several AI calls — doesn't cancel it. Progress/result state lives here (not in the screen's
 * own remember{}) for the same reason: remember{} state resets when its composable leaves and
 * re-enters composition, which would make a still-running background task look like it had
 * silently reset even if the coroutine itself kept going.
 */
object MaintenanceScheduler {
    var fixDegradedBusy by mutableStateOf(false)
        private set
    var fixDegradedProgress by mutableStateOf(0 to 0)
        private set
    var fixDegradedResult by mutableStateOf<String?>(null)
        private set

    var recoverBusy by mutableStateOf(false)
        private set
    var recoverProgress by mutableStateOf(0 to 0)
        private set
    var recoverResult by mutableStateOf<String?>(null)
        private set

    fun runFixDegraded(context: Context, onDone: suspend () -> Unit = {}) {
        if (fixDegradedBusy) return
        fixDegradedBusy = true
        fixDegradedResult = null
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            val result = LocalRepository.fixDegradedReports(appContext) { done, total ->
                fixDegradedProgress = done to total
            }
            fixDegradedResult = buildString {
                append("Fixed ${result.fixed} report(s).")
                if (result.remaining > 0) {
                    append(" ${result.remaining} still need re-analysis")
                    if (result.stoppedReason != null) append(" — ${result.stoppedReason}")
                    append(".")
                }
            }
            fixDegradedBusy = false
            onDone()
        }
    }

    fun runRecoverMissingPanels(context: Context, onDone: suspend () -> Unit = {}) {
        if (recoverBusy) return
        recoverBusy = true
        recoverResult = null
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            val result = LocalRepository.recoverMissingPanels(appContext) { done, total ->
                recoverProgress = done to total
            }
            recoverResult = buildString {
                append("Checked ${result.bundlesChecked} document(s), recovered ${result.panelsRecovered} missing panel(s).")
                if (result.stoppedReason != null) append(" Stopped early — ${result.stoppedReason}")
            }
            recoverBusy = false
            onDone()
        }
    }
}
