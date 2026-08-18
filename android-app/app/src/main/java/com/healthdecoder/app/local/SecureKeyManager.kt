package com.healthdecoder.app.local

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

/**
 * Manages secure cryptographically strong keys/passphrases for SQLCipher database encryption.
 * Passphrases are generated on first launch and stored locally in EncryptedSharedPreferences,
 * which uses keys secured by the Android Keystore system (hardware-backed when available).
 */
object SecureKeyManager {
    private const val PREFS_FILE = "secure_key_prefs"
    private const val KEY_DB_PASSWORD = "db_passphrase_key"

    // Set the moment either Keystore-backed path below throws and falls back to plain
    // SharedPreferences (e.g. a corrupted/modified Keystore on some custom ROMs) — surfaced
    // by SettingsScreen.kt as a warning instead of silently downgrading with no indication.
    @Volatile
    private var usedInsecureFallback = false

    fun isStorageHardwareBacked(): Boolean = !usedInsecureFallback

    /**
     * Retrieves the persisted database passphrase, generating a new one if it doesn't exist.
     * Returns the 32-byte key as a ByteArray.
     *
     * Routes through [getSecurePrefs] — the SAME Keystore-try/fallback helper [setDatabasePassphrase]
     * uses — rather than its own separate copy of that logic. It used to duplicate the Keystore
     * attempt with its own inline fallback to a differently-named file ("legacy_key_prefs" here vs
     * "legacy_secure_prefs" in getSecurePrefs). On a device where the Keystore-backed path is
     * flaky (a known issue on some Samsung builds), a write from one function and a read from the
     * other could silently land in two different files: a restored passphrase would never be seen
     * by whatever next re-opens the database, or a backup created while Keystore was down would
     * bundle a passphrase that doesn't even match the one actually encrypting the live database —
     * making that backup permanently unrestorable (INCOMPATIBLE_KEY) regardless of password.
     */
    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = getSecurePrefs(context)
        var passStr = prefs.getString(KEY_DB_PASSWORD, null)
            // One-time migration for anyone who already hit the two-file split bug: the value
            // that's actually keying their live (already-encrypted) database may be sitting in
            // the old inline fallback this function used before, not in getSecurePrefs' file.
            // Must check this BEFORE generating a random key below, or their existing database
            // becomes permanently unreadable the moment this fix lands.
            ?: migrateLegacyPassphrase(context, prefs)
        if (passStr.isNullOrBlank()) {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            passStr = Base64.encodeToString(key, Base64.NO_WRAP)
            prefs.edit().putString(KEY_DB_PASSWORD, passStr).apply()
        }
        return Base64.decode(passStr, Base64.NO_WRAP)
    }

    private fun migrateLegacyPassphrase(context: Context, target: android.content.SharedPreferences): String? {
        val legacy = context.getSharedPreferences("legacy_key_prefs", Context.MODE_PRIVATE)
        val found = legacy.getString(KEY_DB_PASSWORD, null) ?: return null
        target.edit().putString(KEY_DB_PASSWORD, found).apply()
        legacy.edit().remove(KEY_DB_PASSWORD).apply()
        return found
    }

    /**
     * Overwrites the persisted database passphrase — used only when restoring a backup created
     * on a different install (see BackupManager.restoreBackup). A fresh install always generates
     * its own random passphrase on first use, which can't decrypt a medical_records.db encrypted
     * under a different device's passphrase; this replaces it with the one the backup was made
     * with, so the restored database is actually readable instead of silently recreated empty.
     */
    fun setDatabasePassphrase(context: Context, passphrase: ByteArray) {
        // commit(), not apply(): the caller (BackupManager.restoreBackup) force-kills the process
        // via Runtime.exit() moments later to reload from the restored files. apply()'s write is
        // async — if the process dies before it lands, the whole point of this call is lost and
        // the restored database becomes unreadable exactly like the bug this exists to fix.
        getSecurePrefs(context).edit()
            .putString(KEY_DB_PASSWORD, Base64.encodeToString(passphrase, Base64.NO_WRAP))
            .commit()
    }

    private fun getSecurePrefs(context: Context): android.content.SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            e.printStackTrace()
            usedInsecureFallback = true
            context.getSharedPreferences("legacy_secure_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getEmailToken(context: Context): String? =
        getSecurePrefs(context).getString("email_oauth_token", null)

    fun setEmailToken(context: Context, token: String?) {
        getSecurePrefs(context).edit().putString("email_oauth_token", token).apply()
    }

    fun getImapPassword(context: Context): String? =
        getSecurePrefs(context).getString("email_imap_password", null)

    fun setImapPassword(context: Context, pass: String?) {
        getSecurePrefs(context).edit().putString("email_imap_password", pass).apply()
    }

    /**
     * Password applied to backups LocalRepository.afterWrite() creates automatically after
     * every write (and syncs to whatever cloud folder the user picked in Settings) — separate
     * from the one-shot password on the manual "Export Backup" button. Null/unset (the default)
     * means those automatic backups stay unprotected, exactly like before this existed; a user
     * who opts in here gets the same protection on the backups actually leaving the device via
     * cloud sync, not just the ones they explicitly export by hand.
     */
    fun getBackupPassword(context: Context): String? =
        getSecurePrefs(context).getString("auto_backup_password", null)

    fun setBackupPassword(context: Context, password: String?) {
        getSecurePrefs(context).edit().putString("auto_backup_password", password).apply()
    }
}
