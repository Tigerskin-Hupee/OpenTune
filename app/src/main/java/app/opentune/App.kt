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
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import app.opentune.innertube.NewPipeDownloader
import app.opentune.utils.CoilBitmapLoader
import app.opentune.utils.LocalArtworkPathKeyer
import dagger.hilt.android.HiltAndroidApp
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    private val TAG = App::class.simpleName.toString()

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
        }

        // Initialise NewPipeExtractor — pure-JVM YouTube extraction. Cheap
        // (no native binary, no disk extraction); safe to call on main thread.
        try {
            NewPipe.init(NewPipeDownloader(), Localization("en", "US"), ContentCountry("US"))
            Log.i(TAG, "NewPipeExtractor initialised")
        } catch (t: Throwable) {
            Log.e(TAG, "NewPipeExtractor init failed [${t.javaClass.name}]: ${t.message}", t)
        }

        instance = this
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() }))
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
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
