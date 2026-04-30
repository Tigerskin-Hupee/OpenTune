/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
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
import app.opentune.utils.CoilBitmapLoader
import app.opentune.utils.LocalArtworkPathKeyer
import app.opentune.playback.YtDlpManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var ytDlpManager: YtDlpManager

    private val TAG = App::class.simpleName.toString()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
        }

        ytDlpManager.initialize()
        instance = this
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
