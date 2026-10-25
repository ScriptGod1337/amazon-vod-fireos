package com.scriptgod.fireos.avod.auth

import android.util.Log
import com.scriptgod.fireos.avod.model.TokenData
import com.scriptgod.fireos.avod.model.TokenRefreshResponse
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.UUID

/**
 * Manages Amazon token-based authentication.
 * Loads credentials from .device-token, handles Bearer header injection,
 * and performs token refresh on 401 responses.
 *
 * Mirrors Kodi plugin logic in login.py:563-604 and decisions.md Decision 6.
 */
class AmazonAuthService(private val tokenFile: File) {

    companion object {
        private const val TAG = "AmazonAuthService"

        // Device fingerprint — SHIELD Android TV (common.py:55-58, login.py:551-560)
        const val DEVICE_TYPE_ID = "A43PXU4ZN2AL1"
        const val APP_NAME = "com.amazon.avod.thirdpartyclient"
        const val APP_VERSION = "296016847"
        const val DEVICE_MODEL = "mdarcy/nvidia/SHIELD Android TV"
        const val OS_VERSION = "NVIDIA/mdarcy/mdarcy:11/RQ1A.210105.003/7094531_2971.7725:user/release-keys"
        const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV RQ1A.210105.003)"
    }

    private val gson = Gson()
    @Volatile private var tokenData: TokenData = loadToken()
    @Volatile private var siDomain: String = "amazon.com"
    @Volatile var onMediaRequestObserved: ((String) -> Unit)? = null

    fun setSiDomain(domain: String) { siDomain = domain }

    // Base HTTP client (no interceptor) used only for refresh calls
    private val refreshClient = OkHttpClient.Builder().build()

    private fun loadToken(): TokenData {
        return gson.fromJson(tokenFile.readText(), TokenData::class.java)
            ?: throw IllegalStateException("Failed to parse .device-token")
    }

    fun getAccessToken(): String = synchronized(this) {
        if (isExpired()) {
            Log.d(TAG, "Token expired, refreshing")
            refresh()
        }
        tokenData.accessToken
    }

    fun getDeviceId(): String = tokenData.deviceId

    private fun isExpired(): Boolean {
        val expiresAt = tokenData.expiresAt
        return expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - 60
    }

    /**
     * Refreshes the access token using the stored refresh_token.
     * Mirrors login.py:578-604 (refreshToken).
     * IMPORTANT: Never log token values.
     */
    private fun refresh() {
        val current = tokenData
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val domain = "api.$siDomain"

        val body = FormBody.Builder()
            .add("domain", "DeviceLegacy")
            .add("device_type", DEVICE_TYPE_ID)
            .add("device_serial", current.deviceId)
            .add("app_name", APP_NAME)
            .add("app_version", APP_VERSION)
            .add("device_model", DEVICE_MODEL)
            .add("os_version", OS_VERSION)
            .add("source_token_type", "refresh_token")
            .add("requested_token_type", "access_token")
            .add("source_token", current.refreshToken)
            .build()

        // Refresh call uses a stripped-down header set (login.py:591-593):
        // no x-gasc-enabled, no X-Requested-With; add identity domain + requestid
        val refreshUrl = "https://$domain/auth/token"
        val request = Request.Builder()
            .url(refreshUrl)
            .post(body)
            .header("Accept-Charset", "utf-8")
            .header("User-Agent", USER_AGENT)
            .header("x-amzn-identity-auth-domain", domain)
            .header("Accept-Language", "en-US")
            .header("x-amzn-requestid", reqId)
            .build()

        val response = refreshClient.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty refresh response (HTTP ${response.code})")

        if (!response.isSuccessful) {
            throw RuntimeException("Token refresh failed: HTTP ${response.code}")
        }

        val refreshed = gson.fromJson(responseBody, TokenRefreshResponse::class.java)
        val nowSecs = System.currentTimeMillis() / 1000
        tokenData = current.copy(
            accessToken = refreshed.accessToken,
            expiresAt = nowSecs + refreshed.expiresIn
        )
        // Persist updated token (preserve refresh_token and device_id)
        tokenFile.writeText(gson.toJson(tokenData))
        Log.d(TAG, "Token refreshed successfully")
    }

    /**
     * OkHttp interceptor: attaches Authorization header and retries on 401.
     */
    inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val authedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer ${getAccessToken()}")
                .build()

            val response = chain.proceed(authedRequest)

            if (response.code == 401 || response.code == 403) {
                response.close()
                Log.d(TAG, "Got ${response.code}, refreshing token and retrying")
                synchronized(this@AmazonAuthService) { refresh() }
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer ${getAccessToken()}")
                    .build()
                return chain.proceed(retryRequest)
            }
            return response
        }
    }

    fun buildAuthenticatedClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .addInterceptor(AndroidHeadersInterceptor())
            .addInterceptor(NetworkLogInterceptor())
            .build()
    }

    /** Logs network errors at ERROR level so they're visible in logcat on release builds. */
    inner class NetworkLogInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath.lowercase()
            val shouldLog = path.endsWith(".mpd") || path.endsWith(".mp4") || path.contains("license")
            if (shouldLog) {
                Log.i(TAG, "HTTP ${request.method} ${request.url}")
                if (path.endsWith(".mp4")) {
                    val segmentKind = when {
                        path.contains("_video_") -> "video"
                        path.contains("_audio_") -> "audio"
                        else -> "segment"
                    }
                    Log.i(TAG, "Media $segmentKind segment observed: ${request.url}")
                    onMediaRequestObserved?.invoke(request.url.toString())
                }
            }
            return try {
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code} ${request.method} ${request.url}")
                    if (shouldLog) {
                        val body = runCatching { response.peekBody(512).string() }.getOrDefault("")
                        Log.e(
                            TAG,
                            "HTTP ${response.code} detail: range=${request.header("Range")} " +
                                "proto=${response.protocol} server=${response.header("Server")} " +
                                "via=${response.header("Via")} body=${body.replace('\n', ' ')}"
                        )
                    }
                }
                response
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message} for ${request.url}")
                throw e
            }
        }
    }

    /**
     * Injects Android device headers on every request (common.py:57-58).
     */
    inner class AndroidHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("Accept-Charset", "utf-8")
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", APP_NAME)
                .build()
            return chain.proceed(request)
        }
    }
}
