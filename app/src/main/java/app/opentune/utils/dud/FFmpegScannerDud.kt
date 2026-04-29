package app.opentune.utils.scanners

import app.opentune.models.SongTempData
import java.io.File

class FFmpegScanner() : MetadataScanner {
    override suspend fun getAllMetadataFromFile(file: File): SongTempData {
        throw NotImplementedError()
    }

    companion object {
        const val VERSION_STRING = "N/A"
    }
}
