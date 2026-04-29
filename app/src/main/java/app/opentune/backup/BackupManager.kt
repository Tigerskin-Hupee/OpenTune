package app.opentune.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.opentune.BuildConfig
import app.opentune.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val dbFile get() = context.getDatabasePath(MusicDatabase.DB_NAME)
    private val settingsFile get() = File(context.filesDir, "datastore/settings.preferences_pb")

    fun exportBackup(outputUri: Uri) {
        // Truncate WAL so the main DB file is self-contained before copying
        database.openHelper.writableDatabase
            .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
            .close()

        val out = context.contentResolver.openOutputStream(outputUri)
            ?: throw IOException("Cannot open output stream for $outputUri")

        out.use {
            ZipOutputStream(it.buffered()).use { zip ->
                if (dbFile.exists()) addToZip(zip, dbFile, "song.db")
                if (settingsFile.exists()) addToZip(zip, settingsFile, "settings.preferences_pb")
            }
        }
    }

    fun importBackup(inputUri: Uri) {
        val tmpDb = File(context.cacheDir, "import_song.db")
        val tmpSettings = File(context.cacheDir, "import_settings.preferences_pb")
        tmpDb.delete()
        tmpSettings.delete()

        var hasSongDb = false

        val input = context.contentResolver.openInputStream(inputUri)
            ?: throw IOException("Cannot open input stream for $inputUri")

        input.use {
            ZipInputStream(it.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "song.db" -> { tmpDb.outputStream().use(zip::copyTo); hasSongDb = true }
                        "settings.preferences_pb" -> tmpSettings.outputStream().use(zip::copyTo)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        if (!hasSongDb) throw IOException("Invalid backup: song.db not found")

        // Close Room so WAL is flushed and file handles are released
        database.close()

        dbFile.parentFile?.mkdirs()
        if (!tmpDb.renameTo(dbFile)) {
            tmpDb.copyTo(dbFile, overwrite = true)
            tmpDb.delete()
        }
        // Remove stale WAL files so Room opens cleanly
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()

        if (tmpSettings.exists()) {
            settingsFile.parentFile?.mkdirs()
            if (!tmpSettings.renameTo(settingsFile)) {
                tmpSettings.copyTo(settingsFile, overwrite = true)
                tmpSettings.delete()
            }
        }

        // Restart the app
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)!!
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
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
