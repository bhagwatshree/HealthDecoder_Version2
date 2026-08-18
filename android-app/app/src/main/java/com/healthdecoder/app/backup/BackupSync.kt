package com.healthdecoder.app.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Uploads a local backup file to a cloud destination. The real Google Drive
 * implementation is added in Phase 3 (needs OAuth); until then a no-op is used so the
 * offline-first flow compiles and runs — local backups still happen, cloud upload is simply
 * skipped until an uploader is configured.
 */
interface CloudUploader {
    /** True once the user has connected the cloud account (e.g. signed into Drive). */
    fun isConfigured(context: Context): Boolean
    /** Uploads the file; returns true on success. */
    fun upload(context: Context, file: File): Boolean
}

object NoOpUploader : CloudUploader {
    override fun isConfigured(context: Context) = false
    override fun upload(context: Context, file: File) = false
}

/**
 * Offline-first sync coordinator: local backups are made regardless of connectivity, and
 * any not-yet-uploaded backups are pushed to the cloud whenever the network becomes
 * available. Tracks which backups have already been uploaded in a small synced.json.
 */
object BackupSync {

    // SafCloudUploader writes backup zips into the user's chosen cloud-synced
    // folder via SAF — no OAuth, no API keys. Falls back to NoOpUploader if
    // no folder has been selected yet (isConfigured returns false in that case).
    @Volatile
    var uploader: CloudUploader = SafCloudUploader

    private val gson = Gson()
    private val syncedType = object : TypeToken<MutableSet<String>>() {}.type

    private fun syncedFile(context: Context) = File(BackupManager.backupsDir(context), "synced.json")

    private fun loadSynced(context: Context): MutableSet<String> {
        val f = syncedFile(context)
        if (!f.exists()) return mutableSetOf()
        return try {
            gson.fromJson<MutableSet<String>>(f.readText(), syncedType) ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun saveSynced(context: Context, set: Set<String>) {
        runCatching { syncedFile(context).writeText(gson.toJson(set)) }
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun pendingCount(context: Context): Int {
        val synced = loadSynced(context)
        return BackupManager.listBackups(context).count { it.name !in synced }
    }

    /**
     * Uploads any local backups that haven't been synced yet, if online and an uploader is
     * configured. Safe to call often (e.g. on app start or when connectivity returns).
     * Returns the number of backups uploaded in this pass.
     */
    @Synchronized
    fun syncPending(context: Context): Int {
        if (!uploader.isConfigured(context) || !isOnline(context)) return 0
        val synced = loadSynced(context)
        var uploaded = 0
        for (backup in BackupManager.listBackups(context).reversed()) { // oldest first
            if (backup.name in synced) continue
            if (uploader.upload(context, backup)) {
                synced.add(backup.name)
                uploaded++
            } else {
                break // stop on first failure; retry later
            }
        }
        if (uploaded > 0) saveSynced(context, synced)
        return uploaded
    }

    /** Marks a backup as already uploaded (used by the uploader after a successful push). */
    fun markSynced(context: Context, file: File) {
        val synced = loadSynced(context)
        synced.add(file.name)
        saveSynced(context, synced)
    }

    // ── Auto-sync on connectivity changes ─────────────────────────────────────
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Registers a listener that runs syncPending whenever a network becomes available.
     * Call from the app's foreground lifecycle. For robust background/deferred sync we will
     * add WorkManager in Phase 3.
     */
    fun registerAutoSync(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { syncPending(context.applicationContext) }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
        callback = cb
    }

    fun unregisterAutoSync(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
