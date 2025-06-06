package org.jellyfin.mobile.webapp

import android.net.http.SslError
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.initLocale
import org.jellyfin.mobile.utils.inject
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.Reader
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class JellyfinWebViewClient(
    private val coroutineScope: CoroutineScope,
    private val server: ServerEntity,
    private val assetsPathHandler: AssetsPathHandler,
    private val apiClientController: ApiClientController,
    private val clientPrivateKeyEntry: KeyStore.PrivateKeyEntry? = null,
) : WebViewClientCompat() {

    abstract fun onConnectedToWebapp()

    abstract fun onErrorReceived()

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest) {
        if (clientPrivateKeyEntry == null) {
            request.cancel()
            return
        }
        request.proceed(
            clientPrivateKeyEntry.privateKey,
            clientPrivateKeyEntry.certificateChain.map { it as X509Certificate }.toTypedArray(),
        )
    }

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path?.lowercase(Locale.ROOT) ?: return null
        return when {
            path.matches(Constants.MAIN_BUNDLE_PATH_REGEX) && "deferred" !in url.query.orEmpty() -> {
                onConnectedToWebapp()
                assetsPathHandler.inject("native/injectionScript.js")
            }
            // Load injected scripts from application assets
            path.contains("/native/") -> assetsPathHandler.inject("native/${url.lastPathSegment}")
            // Load the chrome.cast.js library instead
            path.endsWith(Constants.CAST_SDK_PATH) -> assetsPathHandler.inject("native/chrome.cast.js")
            path.endsWith(Constants.SESSION_CAPABILITIES_PATH) -> {
                coroutineScope.launch {
                    val credentials = suspendCoroutine { continuation ->
                        webView.evaluateJavascript(
                            "JSON.parse(window.localStorage.getItem('jellyfin_credentials'))",
                        ) { result ->
                            try {
                                continuation.resume(JSONObject(result))
                            } catch (e: JSONException) {
                                val message = "Failed to extract credentials"
                                Timber.e(e, message)
                                continuation.resumeWithException(Exception(message, e))
                            }
                        }
                    }
                    val storedServer = credentials.getJSONArray("Servers").getJSONObject(0)
                    val user = storedServer.getString("UserId")
                    val token = storedServer.getString("AccessToken")
                    apiClientController.setupUser(server.id, user, token)
                    webView.initLocale(user)
                }
                null
            }
            else -> null
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        val errorMessage = errorResponse.data?.run { bufferedReader().use(Reader::readText) }
        Timber.e("Received WebView HTTP %d error: %s", errorResponse.statusCode, errorMessage)

        if (request.isForMainFrame) onErrorReceived()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        val description = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) -> error.description
            else -> null
        }
        val errorCode = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE) -> error.errorCode
            else -> ERROR_UNKNOWN
        }
        Timber.e("Received WebView error %d at %s: %s", errorCode, request.url.toString(), description)

        // Abort on some specific error codes or when the request url matches the server url
        if (request.isForMainFrame) onErrorReceived()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Timber.e("Received SSL error: %s", error.toString())
        handler.cancel()
    }
}
