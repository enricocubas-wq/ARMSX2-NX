package app.faxina

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import coil.request.CachePolicy

class FaxinaApp : Application(), ImageLoaderFactory {
    // Miniaturas de fotos e vídeos nas listas de revisão: pouca memória, sem cache em disco.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .build()
}
