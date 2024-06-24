package org.jellyfin.mobile.app

import org.jellyfin.mobile.downloads.DownloadsViewModel
import android.content.Context
import coil.ImageLoader
import com.google.android.exoplayer2.database.DatabaseProvider
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.ext.cronet.CronetDataSource
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ts.TsExtractor
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.Cache
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.audio.car.LibraryBrowser
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.qualityoptions.QualityOptionsProvider
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.PermissionRequestHelper
import org.jellyfin.mobile.utils.isLowRamDevice
import org.jellyfin.mobile.webapp.RemoteVolumeProvider
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.fragment.dsl.fragment
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.Executors

const val PLAYER_EVENT_CHANNEL = "PlayerEventChannel"
private const val HTTP_CACHE_SIZE: Long = 16 * 1024 * 1024
private const val TS_SEARCH_PACKETS = 1800

val applicationModule = module {
    single { AppPreferences(androidApplication()) }
    single { OkHttpClient() }
    single { ImageLoader(androidApplication()) }
    single { PermissionRequestHelper() }
    single { RemoteVolumeProvider(get()) }
    single(named(PLAYER_EVENT_CHANNEL)) { Channel<PlayerEvent>() }

    // Controllers
    single { ApiClientController(get(), get(), get(), get(), get()) }

    // Event handlers and channels
    single { ActivityEventHandler(get()) }
    single { WebappFunctionChannel() }

    // Bridge interfaces
    single { NativePlayer(get(), get(), get(named(PLAYER_EVENT_CHANNEL))) }

    // ViewModels
    viewModel { MainViewModel(get(), get()) }
    viewModel { DownloadsViewModel() }

    // Fragments
    fragment { WebViewFragment() }
    fragment { PlayerFragment() }

    // Connection helper
    single { ConnectionHelper(get(), get()) }

    // Media player helpers
    single { MediaSourceResolver(get()) }
    single { DeviceProfileBuilder(get()) }
    single { QualityOptionsProvider() }

    // ExoPlayer factories
    single <DatabaseProvider>{
        val dbProvider = StandaloneDatabaseProvider(get<Context>())
        dbProvider
    }
    single <Cache> {
        val downloadPath = File(get<Context>().filesDir, Constants.DOWNLOAD_PATH)
        if (!downloadPath.exists()) {
            downloadPath.mkdirs()
        }
        val cache = SimpleCache(downloadPath, NoOpCacheEvictor(), get())
        cache
    }

    single<DefaultDataSource.Factory> {
        val context: Context = get()

        val provider = CronetProvider.getAllProviders(context).firstOrNull { provider: CronetProvider ->
            (provider.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED) && provider.isEnabled
        }

        val baseDataSourceFactory = if (provider != null) {
            val cronetEngine = provider.createBuilder()
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, HTTP_CACHE_SIZE)
                .build()
            CronetDataSource.Factory(cronetEngine, Executors.newCachedThreadPool()).apply {
                setUserAgent(Util.getUserAgent(context, Constants.APP_INFO_NAME))
            }
        } else {
            DefaultHttpDataSource.Factory().apply {
                setUserAgent(Util.getUserAgent(context, Constants.APP_INFO_NAME))
            }
        }

        DefaultDataSource.Factory(context, baseDataSourceFactory)

    }

    single<CacheDataSource.Factory> {
        // Create a read-only cache data source factory using the download cache.
        CacheDataSource.Factory()
            .setCache(get())
            .setUpstreamDataSourceFactory(get<DefaultDataSource.Factory>())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { spec ->
                val uri = spec.uri.toString()
                val idRegex = Regex("""/([a-f0-9]{32}|[a-f0-9-]{36})/""")
                val idResult = idRegex.find(uri)
                val itemId = idResult?.groups?.get(1)?.value.toString()
                var item = itemId.toUUID().toString()

                val subtitleRegex = Regex("""(?:Subtitles/(\d+)/\d+/Stream.subrip)|(?:/(\d+).subrip)""")
                val subtitleResult = subtitleRegex.find(uri)
                if (subtitleResult != null) {
                    item += ":${subtitleResult.groups?.get(1)?.value ?: subtitleResult.groups?.get(2)?.value}"
                }

                item
            }
    }

    single<MediaSource.Factory> {
        val context: Context = get()
        val extractorsFactory = DefaultExtractorsFactory().apply {
            // https://github.com/google/ExoPlayer/issues/8571
            setTsExtractorTimestampSearchBytes(
                when {
                    !context.isLowRamDevice -> TS_SEARCH_PACKETS * TsExtractor.TS_PACKET_SIZE // 3x default
                    else -> TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                },
            )
        }
        DefaultMediaSourceFactory(get<CacheDataSource.Factory>(), extractorsFactory)
    }
    single { ProgressiveMediaSource.Factory(get<CacheDataSource.Factory>()) }
    single { HlsMediaSource.Factory(get<CacheDataSource.Factory>()) }
    single { SingleSampleMediaSource.Factory(get<CacheDataSource.Factory>()) }

    // Media components
    single { LibraryBrowser(get(), get()) }
}
