package app.opentune.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.opentune.BuildConfig
import app.opentune.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    // Our Room DB lives at getDatabasePath("music.db"); ZIP entries use the OuterTune name "song.db".
    private val dbFile    get() = context.getDatabasePath(MusicDatabase.DB_NAME)
    private val dbWalFile get() = File("${dbFile.path}-wal")
    private val dbShmFile get() = File("${dbFile.path}-shm")
    private val settingsFile get() = File(context.filesDir, "datastore/settings.preferences_pb")

    suspend fun exportBackup(outputUri: Uri) = withContext(Dispatchers.IO) {
        // Flush WAL into the main DB file so the snapshot is self-contained.
        database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")

        val out = context.contentResolver.openOutputStream(outputUri)
            ?: throw IOException("Cannot open output stream for $outputUri")

        out.use {
            ZipOutputStream(it.buffered()).use { zip ->
                if (dbFile.exists()) addToZip(zip, dbFile, "song.db")
                if (dbWalFile.exists() && dbWalFile.length() > 0) addToZip(zip, dbWalFile, "song.db-wal")
                if (dbShmFile.exists() && dbShmFile.length() > 0) addToZip(zip, dbShmFile, "song.db-shm")
                if (settingsFile.exists()) addToZip(zip, settingsFile, "settings.preferences_pb")
            }
        }
    }

    suspend fun importBackup(inputUri: Uri) = withContext(Dispatchers.IO) {
        val tmpDb       = File(context.cacheDir, "import_song.db")
        val tmpWal      = File(context.cacheDir, "import_song.db-wal")
        val tmpShm      = File(context.cacheDir, "import_song.db-shm")
        val tmpSettings = File(context.cacheDir, "import_settings.preferences_pb")
        listOf(tmpDb, tmpWal, tmpShm, tmpSettings).forEach { it.delete() }

        var hasSongDb = false

        val input = context.contentResolver.openInputStream(inputUri)
            ?: throw IOException("Cannot open input stream for $inputUri")

        input.use {
            ZipInputStream(it.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "song.db"     -> { tmpDb.outputStream().use(zip::copyTo); hasSongDb = true }
                        "song.db-wal" -> tmpWal.outputStream().use(zip::copyTo)
                        "song.db-shm" -> tmpShm.outputStream().use(zip::copyTo)
                        "settings.preferences_pb" -> tmpSettings.outputStream().use(zip::copyTo)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        if (!hasSongDb) throw IOException("Invalid backup: song.db not found")

        // Close Room so WAL is flushed and all file handles are released before we replace files.
        database.close()

        dbFile.parentFile?.mkdirs()

        // Copy rather than rename — source (cacheDir) and destination (databasesDir) may be on
        // different mount points, causing renameTo() to fail silently.
        tmpDb.copyTo(dbFile, overwrite = true)
        tmpDb.delete()

        // Remove stale WAL/SHM, then restore any bundled ones.
        dbWalFile.delete()
        dbShmFile.delete()
        if (tmpWal.exists()) { tmpWal.copyTo(dbWalFile, overwrite = true); tmpWal.delete() }
        if (tmpShm.exists()) { tmpShm.copyTo(dbShmFile, overwrite = true); tmpShm.delete() }

        // Patch the SQLite user_version to match our current schema (version 1).
        // Without this, Room's fallbackToDestructiveMigration() would drop all tables when it
        // detects a version mismatch (e.g. an OuterTune backup exported at a higher version).
        try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            ).use { sqlite -> sqlite.execSQL("PRAGMA user_version = ${MusicDatabase.SCHEMA_VERSION}") }
        } catch (_: Exception) { /* best-effort; proceed even if patching fails */ }

        if (tmpSettings.exists()) {
            settingsFile.parentFile?.mkdirs()
            tmpSettings.copyTo(settingsFile, overwrite = true)
            tmpSettings.delete()
        }

        // Switch to main thread to start the new Activity, then terminate the current process.
        withContext(Dispatchers.Main) {
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)!!
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            context.startActivity(intent)
        }
        System.exit(0)
    }

    private fun addToZip(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        fun buildFileName(): String {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "OpenTune_${BuildConfig.VERSION_NAME}_$ts.backup"
        }
    }
}
