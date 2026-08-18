package com.healthdecoder.app.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A coroutine scope that outlives any single screen — for launching a background operation from
 * a composable without tying its lifetime to that composable's own. A plain rememberCoroutineScope()
 * gets CANCELLED the instant its composable leaves composition (e.g. the user taps a different
 * bottom-nav tab), which silently kills whatever it was doing with no error at all — confusing
 * for anything that takes real time (bulk AI re-analysis, multi-file transfers, restore). Mirrors
 * the same pattern BackgroundScanScheduler already uses for scan jobs, generalized for reuse
 * rather than reimplemented per feature (see RestoreScheduler for one such reimplementation this
 * predates — new call sites should prefer this instead of writing another one-off object).
 */
object BackgroundTasks {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Launches [block] in a scope independent of any composable — it keeps running even if the
     *  screen that started it is navigated away from and disposed. */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
