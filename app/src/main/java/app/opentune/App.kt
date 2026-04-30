/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package app.opentune

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import app.opentune.playback.YtDlpManager
import app.opentune.utils.CoilBitmapLoader
import app.opentune.utils.LocalArtworkPathKeyer
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    private val TAG = App::class.simpleName.toString()

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var ytDlpManager: YtDlpManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
        }

        // Initialise yt-dlp (embedded Python in nativeLibraryDir — works under
        // SELinux on Android 10+). Heavy: extracts .so on first launch.
        GlobalScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(this@App)
                ytDlpManager.onLibraryReady()
            } catch (t: Throwable) {
                ytDlpManager.onLibraryError(t)
            }
        }

        // Schedule the periodic update worker — runs the library's
        // updateYoutubeDL() so yt-dlp stays current without an APK release.
        ytDlpManager.scheduleUpdates()

        instance = this
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {

        return ImageLoader.Builder(this)
            .components {
                add(CoilBitmapLoader.Factory(this@App))
                add(LocalArtworkPathKeyer())
            }
            .crossfade(true)
            .allowHardware(false)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.3)
                    .build()
            }
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
