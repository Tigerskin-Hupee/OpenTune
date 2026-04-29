package app.opentune.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import app.opentune.BuildConfig
import app.opentune.db.MusicDatabase
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Song
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

private const val TAG = "BackupManager"

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val repository: MusicRepository,
) {
    private val dbFile    get() = context.getDatabasePath(MusicDatabase.DB_NAME)
    private val dbWalFile get() = File("${dbFile.path}-wal")
    private val dbShmFile get() = File("${dbFile.path}-shm")
    private val settingsFile get() = File(context.filesDir, "datastore/settings.preferences_pb")

    // ── Export ────────────────────────────────────────────────────────────────

    suspend fun exportBackup(outputUri: Uri) = withContext(Dispatchers.IO) {
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

    // ── Import (OuterTune-compatible) ─────────────────────────────────────────

    /**
     * Imports an OuterTune (or OpenTune) backup ZIP.
     *
     * Instead of replacing the database file (which would fail due to schema version
     * mismatch — OuterTune is at version 23, we are at version 1), we read the
     * backup's SQLite file directly, map the columns, and upsert each row through
     * Room. This is schema-agnostic and survives any version delta.
     *
     * Column mapping:
     *   OuterTune `likedDate` (epoch ms Long) → our `likedAt`
     *   OuterTune `liked`     (SQLite integer) → our `liked` (Boolean)
     *   OuterTune `inLibrary` (epoch ms Long) → our `inLibrary`
     *
     * Returns the number of songs imported.
     */
    suspend fun importBackup(inputUri: Uri): Int = withContext(Dispatchers.IO) {
        val tmpDb       = File(context.cacheDir, "import_song.db")
        val tmpSettings = File(context.cacheDir, "import_settings.pb")
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
                        "song.db" -> {
                            tmpDb.outputStream().use(zip::copyTo)
                            hasSongDb = true
                        }
                        "settings.preferences_pb" -> {
                            tmpSettings.outputStream().use(zip::copyTo)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        if (!hasSongDb) throw IOException("Invalid backup: song.db not found")

        var importedCount = 0
        try {
            SQLiteDatabase.openDatabase(
                tmpDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { sqlite ->
                sqlite.rawQuery("SELECT * FROM song", null)?.use { cursor ->
                    val idIdx        = cursor.getColumnIndex("id")
                    val titleIdx     = cursor.getColumnIndex("title")
                    val durationIdx  = cursor.getColumnIndex("duration")
                    val thumbIdx     = cursor.getColumnIndex("thumbnailUrl")
                    val albumIdIdx   = cursor.getColumnIndex("albumId")
                    val albumNameIdx = cursor.getColumnIndex("albumName")
                    val likedIdx     = cursor.getColumnIndex("liked")
                    // OuterTune stores liked timestamp as "likedDate"; we use "likedAt"
                    val likedDateIdx = cursor.getColumnIndex("likedDate")
                    val inLibraryIdx = cursor.getColumnIndex("inLibrary")

                    if (idIdx < 0 || titleIdx < 0) {
                        Log.e(TAG, "Backup song table missing id/title columns; skipping")
                        return@use
                    }

                    while (cursor.moveToNext()) {
                        val id    = cursor.getString(idIdx) ?: continue
                        val title = cursor.getString(titleIdx) ?: continue

                        val song = Song(
                            id           = id,
                            title        = title,
                            duration     = if (durationIdx >= 0) cursor.getInt(durationIdx) else -1,
                            thumbnailUrl = colStringOrNull(cursor, thumbIdx),
                            albumId      = colStringOrNull(cursor, albumIdIdx),
                            albumName    = colStringOrNull(cursor, albumNameIdx),
                            liked        = likedIdx >= 0 && !cursor.isNull(likedIdx) && cursor.getInt(likedIdx) != 0,
                            likedAt      = colLongOrNull(cursor, likedDateIdx),
                            inLibrary    = colLongOrNull(cursor, inLibraryIdx),
                        )
                        repository.upsertSong(song)
                        importedCount++
                    }
                }
            }
            Log.i(TAG, "Imported $importedCount songs from backup")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read backup database", e)
            throw IOException("Cannot read backup database: ${e.message}", e)
        } finally {
            tmpDb.delete()
        }

        // Restore settings and restart to apply them.
        if (tmpSettings.exists()) {
            settingsFile.parentFile?.mkdirs()
            tmpSettings.copyTo(settingsFile, overwrite = true)
            tmpSettings.delete()

            withContext(Dispatchers.Main) {
                val intent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)!!
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                context.startActivity(intent)
            }
            System.exit(0)
        }

        importedCount
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun colStringOrNull(cursor: android.database.Cursor, idx: Int): String? =
        if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null

    private fun colLongOrNull(cursor: android.database.Cursor, idx: Int): Long? =
        if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null

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
