package com.shinobi.app

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe

class ShinobiApplication : Application(), ImageLoaderFactory {
    /** Process-wide background work that no screen owns. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one image loader (built once), so memory pressure can trim its caches. */
    @Volatile
    private var loader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
        DeviceRam.init(this)
        Net.init(this)
        NewPipe.init(NewPipeDownloader.instance)
        // The iOS client only adds HLS livestream formats; Shinobi plays VOD, so skip that request.
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(false)
        ThemeSettings.init(this)
        AppSettings.init(this)
        // English by default, Arabic one tap away in Settings → Language.
        Loc.init(this)
        QualityPreference.init(this)
        PlaybackSpeed.init(this)
        AudioOnly.init(this)
        ResumeStore.init(this)
        CaptionPreference.init(this)
        LocalHistory.init(this)
        Account.init(this)
        SponsorBlock.init(this)
        // No SIM and no mobile network to place the phone: ask YouTube where this connection is.
        if (AppSettings.detectionIsGuess) scope.launch {
            Region.fromYouTube()?.let { code -> withContext(Dispatchers.Main) { AppSettings.updateDetectedCountry(code) } }
        }
        // Stream URLs are bound to the network they were fetched on: forget them on a switch.
        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                private var last: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (last != null && last != network) StreamCache.invalidateAll()
                    last = network
                }
            },
        )
    }

    // Thumbnails are in every list and the player; a short crossfade replaces pop-in.
    // Sized for 2–3 GB phones: a small memory cache and 16-bit bitmaps (thumbnails are
    // photos — half the bytes of ARGB_8888, invisible at card sizes), so lists recycle
    // without the heap churning.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(if (DeviceRam.lowRam) 0.12 else 0.18)
                .build()
        }
        .bitmapConfig(Bitmap.Config.RGB_565)
        // The app's own connection pool: thumbnail hosts stay warm between screens instead
        // of a second client opening its own connections.
        .okHttpClient { Net.client }
        // Thumbnail bytes never change for a given URL; keep them on disk whatever the
        // headers say, so a relaunch or a revisited list draws from the cache at once.
        .respectCacheHeaders(false)
        .build()
        .also { loader = it }

    // Under pressure the caches go first — the big open-source clients do the same.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            runCatching { loader?.memoryCache?.trimMemory(level) }
        }
    }
}
