package com.healthdecoder.app.backup

import android.content.Context
import com.healthdecoder.app.local.LocalStore
import com.healthdecoder.app.local.SecureKeyManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * On-device backup engine. Snapshots the entire records/ folder (medical_records.db,
 * images/, and sources/) into a single timestamped .zip stored locally on the
 * device. These local snapshots are the source of truth for backup; cloud upload
 * (Google Drive) is a separate, deferred step that runs when the network is available
 * (see BackupSync). Uses only java.util.zip — no external dependencies.
 *
 * Older backups containing the legacy reports.json files restore fine: LocalStore
 * imports them into the database the next time it opens.
 */
object BackupManager {

    private const val MAX_LOCAL_BACKUPS = 15
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // Not real record files — never placed under recordsDir, added directly as their own zip
    // entries (see createLocalBackup/restoreBackup) so they never leak into recordsDir on disk,
    // Transfer Records exports, or anything else that reads that folder. Exactly one of these two
    // is present in any given backup: the raw key (default) or the password-encrypted one
    // (optional — see createLocalBackup's password parameter).
    private const val DB_KEY_ENTRY_NAME = ".backup_dbkey"
    private const val DB_KEY_ENC_ENTRY_NAME = ".backup_dbkey_enc"

    private const val PBKDF2_ITERATIONS = 210_000 // OWASP's 2023 minimum for PBKDF2-HMAC-SHA256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Encrypts the raw DB passphrase with a key derived from [password], so possessing the
     *  backup zip alone is no longer enough to decrypt the records inside it — see
     *  createLocalBackup's password parameter. Format: salt(16) + iv(12) + AES-GCM ciphertext. */
    private fun encryptPassphraseWithPassword(passphrase: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return salt + iv + cipher.doFinal(passphrase)
    }

    /** Reverses [encryptPassphraseWithPassword]. Null on a wrong password (GCM's auth tag check
     *  fails) or a malformed blob — never throws. */
    private fun decryptPassphraseWithPassword(blob: ByteArray, password: String): ByteArray? = runCatching {
        val salt = blob.copyOfRange(0, SALT_LEN)
        val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ciphertext = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        cipher.doFinal(ciphertext)
    }.getOrNull()

    /** Cheap check — random-access lookup by name via the zip's central directory (see
     *  restoreBackup's matching comment), not a sequential scan through every entry — so the UI
     *  can decide whether to prompt for a password before actually attempting [restoreBackup]
     *  without paying for a full pass over a potentially large, photo-heavy backup first. */
    fun requiresPassword(backupZip: File): Boolean {
        if (!backupZip.exists()) return false
        return runCatching {
            java.util.zip.ZipFile(backupZip).use { zf -> zf.getEntry(DB_KEY_ENC_ENTRY_NAME) != null }
        }.getOrDefault(false)
    }

    fun backupsDir(context: Context): File =
        File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }

    /** All local backup zips, newest first. */
    fun listBackups(context: Context): List<File> =
        backupsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Creates a new local backup snapshot of all records. Returns the created zip file,
     * or null if there is nothing to back up. Old snapshots beyond MAX_LOCAL_BACKUPS are pruned.
     *
     * [password], if given, encrypts the bundled DB key with it (see encryptPassphraseWithPassword)
     * instead of storing it raw — so possessing the zip file alone isn't enough to decrypt the
     * records inside; the password is also needed at restore time. Optional: leaving it null keeps
     * the simpler "the zip is self-contained" behavior, matching how these backups worked before
     * this option existed.
     */
    @Synchronized
    fun createLocalBackup(context: Context, password: String? = null): File? {
        val recordsDir = LocalStore.recordsDir(context)
        if (!recordsDir.exists() || recordsDir.listFiles().isNullOrEmpty()) return null

        val outFile = File(backupsDir(context), "backup_${stamp.format(Date())}.zip")
        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            zipDirectory(recordsDir, recordsDir, zip)
            // Bundle the DB encryption passphrase so this backup is self-contained: restoring
            // it on a different install (a new phone, or this one reinstalled) can actually
            // decrypt medical_records.db, instead of the fresh install's own random passphrase
            // failing to open it and LocalStore silently recreating an empty database — see
            // restoreBackup(). Without a password, the backup file itself becomes what protects
            // this data at rest; with one, the password is a required second factor beyond just
            // having the file.
            val passphrase = SecureKeyManager.getDatabasePassphrase(context)
            if (password.isNullOrBlank()) {
                zip.putNextEntry(ZipEntry(DB_KEY_ENTRY_NAME))
                zip.write(passphrase)
                zip.closeEntry()
            } else {
                zip.putNextEntry(ZipEntry(DB_KEY_ENC_ENTRY_NAME))
                zip.write(encryptPassphraseWithPassword(passphrase, password))
                zip.closeEntry()
            }
        }
        pruneOldBackups(context)
        return outFile
    }

    /** Why a restore did or didn't happen — lets the UI say something accurate instead of a
     *  blanket "Restore failed", and specifically lets it say "your existing data is untouched"
     *  when that's actually true. */
    enum class RestoreOutcome { SUCCESS, NOT_A_BACKUP, INCOMPATIBLE_KEY, READ_ERROR, PASSWORD_REQUIRED, WRONG_PASSWORD }

    /**
     * Restores a local backup, replacing the current records folder contents — but only after
     * confirming the restored database can actually be decrypted. Everything is extracted into a
     * temporary staging area first; the live recordsDir is never touched until that verification
     * passes. A backup that turns out to be unreadable (wrong/missing encryption key — see
     * createLocalBackup) is reported as [RestoreOutcome.INCOMPATIBLE_KEY] with the device's
     * current data left completely intact, instead of wiping it for a restore that was never
     * going to work.
     *
     * [password] is required only for a backup created with one (check [requiresPassword] first
     * to prompt for it up front rather than discovering the need after already picking a file).
     */
    @Synchronized
    fun restoreBackup(context: Context, backupZip: File, password: String? = null): RestoreOutcome {
        if (!backupZip.exists()) return RestoreOutcome.READ_ERROR

        // Validate this is actually a whole-device Backup snapshot before extracting anything.
        // "export.json" at the zip root is the *Transfer Records* portable-export format (a
        // different feature, easy to pick from the wrong button since Restore accepts any file
        // type) — extracting it here would produce files Room never reads (medical_records.db
        // never gets written). Same pass also pulls out the bundled DB passphrase, raw or
        // password-encrypted (see createLocalBackup) — absent on backups made before that fix.
        //
        // java.util.zip.ZipFile (random access via the zip's central directory), NOT
        // ZipInputStream (sequential) — this only reads the tiny key entries directly by name;
        // ZipInputStream would have to decompress its way through every image/source entry just
        // to reach them, which on a real multi-photo backup was most of why this whole check —
        // done BEFORE the real extraction pass even starts — took as long as it did.
        var dbPassphrase: ByteArray? = null
        var encryptedKeyBlob: ByteArray? = null
        val looksLikeBackup = try {
            java.util.zip.ZipFile(backupZip).use { zf ->
                val hasDb = zf.getEntry("medical_records.db") != null
                zf.getEntry(DB_KEY_ENTRY_NAME)?.let { dbPassphrase = zf.getInputStream(it).use { s -> s.readBytes() } }
                zf.getEntry(DB_KEY_ENC_ENTRY_NAME)?.let { encryptedKeyBlob = zf.getInputStream(it).use { s -> s.readBytes() } }
                hasDb
            }
        } catch (e: Exception) {
            return RestoreOutcome.READ_ERROR
        }
        if (!looksLikeBackup) return RestoreOutcome.NOT_A_BACKUP

        encryptedKeyBlob?.let { blob ->
            if (password.isNullOrBlank()) return RestoreOutcome.PASSWORD_REQUIRED
            dbPassphrase = decryptPassphraseWithPassword(blob, password) ?: return RestoreOutcome.WRONG_PASSWORD
        }

        val stagingDir = File(context.cacheDir, "restore_staging_${System.currentTimeMillis()}")
        try {
            stagingDir.mkdirs()
            ZipInputStream(FileInputStream(backupZip).buffered()).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    if (entry.name == DB_KEY_ENTRY_NAME || entry.name == DB_KEY_ENC_ENTRY_NAME) {
                        entry = zin.nextEntry
                        continue // not a record file — read above, not extracted
                    }
                    val outFile = File(stagingDir, entry.name)
                    // Guard against zip path traversal.
                    if (!outFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                        entry = zin.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zin.copyTo(out) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }

            // Prefer the bundled passphrase; fall back to this device's own — covers a
            // same-device restore of a backup made before the bundling fix existed, where this
            // device's already-active passphrase is the correct one even though none was bundled.
            val candidatePassphrase = dbPassphrase ?: SecureKeyManager.getDatabasePassphrase(context)
            val stagedDb = File(stagingDir, "medical_records.db")
            val opens = stagedDb.exists() && runCatching {
                net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
                // Room's SupportFactory(ByteArray) keys the database with the raw bytes, not a
                // PBKDF2-derived passphrase — SQLCipher's "x'<hex>'" convention is how the raw
                // net.sqlcipher.database.SQLiteDatabase API asks for that same raw-key mode.
                // Using a plain string here would derive a completely different (wrong) key even
                // from the objectively correct bytes, and falsely report a good backup as bad.
                val rawKeyHex = "x'" + candidatePassphrase.joinToString("") { "%02x".format(it) } + "'"
                val db = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                    stagedDb.absolutePath,
                    rawKeyHex,
                    null,
                    net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
                )
                try {
                    db.rawQuery("select count(*) from sqlite_master", null).use { it.moveToFirst() }
                } finally {
                    db.close()
                }
                true
            }.getOrDefault(false)

            if (!opens) return RestoreOutcome.INCOMPATIBLE_KEY

            // Verified openable — only now is it safe to replace the live data.
            LocalStore.closeDatabase() // release the SQLite file before replacing it
            val recordsDir = LocalStore.recordsDir(context)
            recordsDir.listFiles()?.forEach { it.deleteRecursively() }
            // rename(), not copyRecursively(): staging and recordsDir are both already on the
            // same internal storage volume (cacheDir/filesDir), so this is an instant directory-
            // entry update instead of writing every byte a second time — the extraction pass
            // above already wrote them once. Falls back to an actual copy only if some device
            // genuinely can't rename across these two dirs (not expected, but rename() failing
            // silently returns false rather than throwing, so this is a real path to guard).
            stagingDir.listFiles()?.forEach { src ->
                val dest = File(recordsDir, src.name)
                if (!src.renameTo(dest)) {
                    src.copyRecursively(dest, overwrite = true)
                }
            }

            // Must happen before anything reopens the database — restartApp() in SettingsScreen
            // guarantees that ordering.
            dbPassphrase?.let { SecureKeyManager.setDatabasePassphrase(context, it) }
            return RestoreOutcome.SUCCESS
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Human-readable label for a backup file (from its timestamp). */
    fun labelFor(file: File): String {
        return try {
            val name = file.nameWithoutExtension.removePrefix("backup_")
            val parsed = stamp.parse(name)
            SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(parsed!!)
        } catch (e: Exception) {
            file.name
        }
    }

    fun backupSizeKb(file: File): Long = (file.length() / 1024).coerceAtLeast(1)

    // ── internals ────────────────────────────────────────────────────────────
    private fun zipDirectory(root: File, current: File, zip: ZipOutputStream) {
        val children = current.listFiles() ?: return
        for (child in children) {
            val relPath = child.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')
            if (child.isDirectory) {
                zipDirectory(root, child, zip)
            } else {
                zip.putNextEntry(ZipEntry(relPath.replace('\\', '/')))
                FileInputStream(child).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun pruneOldBackups(context: Context) {
        val backups = listBackups(context)
        if (backups.size > MAX_LOCAL_BACKUPS) {
            backups.drop(MAX_LOCAL_BACKUPS).forEach { runCatching { it.delete() } }
        }
    }
}
