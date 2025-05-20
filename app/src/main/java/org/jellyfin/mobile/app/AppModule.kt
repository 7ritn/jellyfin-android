package org.jellyfin.mobile.app

import android.content.Context
import androidx.core.net.toUri
import coil.ImageLoader
import com.google.android.exoplayer2.database.DatabaseProvider
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ts.TsExtractor
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.ResolvingDataSource
import com.google.android.exoplayer2.upstream.cache.Cache
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.downloads.DownloadsViewModel
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
import org.jellyfin.mobile.utils.extractId
import org.jellyfin.mobile.utils.getClientCertificate
import org.jellyfin.mobile.utils.isLowRamDevice
import org.jellyfin.mobile.webapp.RemoteVolumeProvider
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.fragment.dsl.fragment
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

const val PLAYER_EVENT_CHANNEL = "PlayerEventChannel"
private const val TS_SEARCH_PACKETS = 1800

val applicationModule = module {
    single { AppPreferences(androidApplication()) }
    single<OkHttpClient> {
        val okHttpBuilder = OkHttpClient.Builder()

        get<AppPreferences>().currentServerId?.let { id ->
            getClientCertificate(id.toString())?.let { mtls ->
                val keyStore = KeyStore.getInstance("PKCS12")
                keyStore.load(null, null)
                keyStore.setKeyEntry(
                    "client",
                    mtls.privateKey,
                    null,
                    mtls.certificateChain,
                )

                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, null)

                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(kmf.keyManagers, null, null)
                }

                val trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm(),
                ).apply {
                    init(null as KeyStore?) // use default trust store
                }

                val trustManager = trustManagerFactory.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .firstOrNull()

                trustManager?.let {
                    okHttpBuilder.sslSocketFactory(sslContext.socketFactory, it)
                }
            }
        }

        okHttpBuilder.build()
    }
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
    single<DatabaseProvider> {
        val dbProvider = StandaloneDatabaseProvider(get<Context>())
        dbProvider
    }
    single<Cache> {
        val downloadPath = File(get<Context>().filesDir, Constants.DOWNLOAD_PATH)
        if (!downloadPath.exists()) {
            downloadPath.mkdirs()
        }
        val cache = SimpleCache(downloadPath, NoOpCacheEvictor(), get())
        cache
    }

    single<DataSource.Factory> {
        val context: Context = get()
        val apiClient: ApiClient = get()

        val baseDataSourceFactory = OkHttpDataSource.Factory(get<OkHttpClient>())

        val dataSourceFactory = DefaultDataSource.Factory(context, baseDataSourceFactory)

        // Add authorization header. This is needed as we don't pass the
        // access token in the URL for Android Auto.
        ResolvingDataSource.Factory(dataSourceFactory) { dataSpec: DataSpec ->
            // Only send authorization header if URI matches the jellyfin server
            val baseUrlAuthority = apiClient.baseUrl?.toUri()?.authority

            if (dataSpec.uri.authority == baseUrlAuthority) {
                val authorizationHeaderString = AuthorizationHeaderBuilder.buildHeader(
                    clientName = apiClient.clientInfo.name,
                    clientVersion = apiClient.clientInfo.version,
                    deviceId = apiClient.deviceInfo.id,
                    deviceName = apiClient.deviceInfo.name,
                    accessToken = apiClient.accessToken,
                )

                dataSpec.withRequestHeaders(hashMapOf("Authorization" to authorizationHeaderString))
            } else {
                dataSpec
            }
        }
    }

    single<CacheDataSource.Factory> {
        // Create a read-only cache data source factory using the download cache.
        CacheDataSource.Factory()
            .setCache(get())
            .setUpstreamDataSourceFactory(get<DataSource.Factory>())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec ->
                spec.uri.extractId()
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
