package com.scriptgod.fireos.avod.drm

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Custom Widevine license callback for Amazon's DRM exchange.
 *
 * Uses POST /playback/drm-vod/GetWidevineLicense with a JSON body matching
 * Amazon's WidevineLicenseParamsCreator shape:
 *   { "licenseChallenge":"<base64>", "deviceCapabilityFamily":"AndroidPlayer",
 *     "capabilityDiscriminators":{ "version":1, "discriminators":{...} } }
 *
 * playbackEnvelope is omitted — it is optional (WidevineLicenseParamsCreator has a
 * constructor that omits it, and the field has no checkNotNull guard).
 *
 * Response: { "widevineLicense": { "license": "<base64>" } }
 */
@UnstableApi
class AmazonLicenseService(
    private val authService: AmazonAuthService,
    private val licenseUrl: String,
    private val context: Context,
    private val diagFile: java.io.File? = null
) : MediaDrmCallback {

    companion object {
        private const val TAG = "AmazonLicenseService"
    }

    private fun diagLog(msg: String) {
        Log.w(TAG, msg)
        try { diagFile?.appendText("${System.currentTimeMillis()} $msg\n") } catch (_: Exception) {}
    }

    // Authenticated client for Amazon license requests
    private val client: OkHttpClient = authService.buildAuthenticatedClient()
    // Plain client for Google Widevine provisioning — must NOT carry Amazon auth headers
    private val provisionClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private fun getAppVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1"
    } catch (_: Exception) { "1" }

    /**
     * Builds the capabilityDiscriminators JSON object from device info.
     * Mirrors Amazon's ParamsCreatorUtils.getCapabilityDiscriminators():
     *   hardware: Build.MANUFACTURER / Build.MODEL / Build.BOARD
     *   software.application: package name + versionName
     *   software.operatingSystem: "Android" + Build.VERSION.RELEASE
     *   software.firmware: Build.FINGERPRINT
     *   software.player: "ExoPlayer" + versionName
     *   software.renderer: "DASH" / "WIDEVINE"
     *   software.client: id=null (no clientId in our flow)
     */
    private fun buildCapabilityDiscriminators(): JsonObject {
        val versionName = getAppVersionName()

        val hardware = JsonObject().apply {
            addProperty("manufacturer", Build.MANUFACTURER)
            addProperty("modelName", Build.MODEL)
            addProperty("chipset", Build.BOARD)
        }
        val software = JsonObject().apply {
            add("application", JsonObject().apply {
                addProperty("name", context.packageName)
                addProperty("version", versionName)
            })
            add("operatingSystem", JsonObject().apply {
                addProperty("name", "Android")
                addProperty("version", Build.VERSION.RELEASE)
            })
            add("firmware", JsonObject().apply {
                addProperty("version", Build.FINGERPRINT)
            })
            add("player", JsonObject().apply {
                addProperty("name", "ExoPlayer")
                addProperty("version", versionName)
            })
            add("renderer", JsonObject().apply {
                addProperty("name", "DASH")
                addProperty("drmScheme", "WIDEVINE")
            })
            add("client", JsonObject().apply {
                addProperty("id", null as String?)
            })
        }
        return JsonObject().apply {
            addProperty("version", 1)
            add("discriminators", JsonObject().apply {
                add("hardware", hardware)
                add("software", software)
            })
        }
    }

    /**
     * Handles Widevine key request using Amazon's native DRM endpoint.
     *
     * Request:  POST JSON { licenseChallenge, deviceCapabilityFamily, capabilityDiscriminators }
     * Response: JSON { "widevineLicense": { "license": "<base64(license_bytes)>" } }
     */
    private var keyRequestCount = 0

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        keyRequestCount++
        val reqNum = keyRequestCount
        val challengeBytes = request.data
        diagLog("DRM key request #$reqNum: challengeSize=${challengeBytes.size} url=$licenseUrl")

        // Standard base64 (with padding, no newlines) — matches Amazon's licenseChallenge field
        val licenseChallenge = Base64.encodeToString(challengeBytes, Base64.NO_WRAP)

        val bodyJson = JsonObject().apply {
            addProperty("licenseChallenge", licenseChallenge)
            addProperty("deviceCapabilityFamily", "AndroidPlayer")
            add("capabilityDiscriminators", buildCapabilityDiscriminators())
        }

        val requestBody = gson.toJson(bodyJson).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(licenseUrl)
            .post(requestBody)
            .build()

        val startMs = System.currentTimeMillis()
        val response = client.newCall(httpRequest).execute()
        val elapsedMs = System.currentTimeMillis() - startMs
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty license response (HTTP ${response.code})")

        diagLog("DRM key response #$reqNum: HTTP ${response.code} elapsed=${elapsedMs}ms bodySize=${responseBody.length}")

        if (!response.isSuccessful) {
            diagLog("DRM key request #$reqNum FAILED: HTTP ${response.code} — $responseBody")
            throw RuntimeException("License request failed: HTTP ${response.code} — $responseBody")
        }

        // Parse JSON response: { "widevineLicense": { "license": "<base64>" } }
        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val licenseBase64 = json
                .getAsJsonObject("widevineLicense")
                ?.getAsJsonPrimitive("license")
                ?.asString
                ?: throw RuntimeException("No widevineLicense.license field in response")

            val licenseBytes = Base64.decode(licenseBase64, Base64.DEFAULT)
            diagLog("DRM key #$reqNum OK: licenseSize=${licenseBytes.size} bytes")
            licenseBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse license response #$reqNum", e)
            diagLog("DRM key #$reqNum PARSE ERROR: ${e.message} body=${responseBody.take(200)}")
            throw RuntimeException("License parse error: ${e.message}", e)
        }
    }

    /**
     * Handles provisioning requests — mirrors ExoPlayer's HttpMediaDrmCallback exactly.
     * Standard Widevine provisioning: GET to defaultUrl + &signedRequest=<utf8(data)>.
     * Must use plain client — no Amazon auth headers go to Google's provisioning server.
     */
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val signedRequest = String(request.data)
        Log.d(TAG, "Provisioning request to: ${request.defaultUrl}")
        // Google's Certificate Provisioning API requires POST with JSON body
        val jsonBody = "{\"signedRequest\":\"$signedRequest\"}"
        val httpRequest = Request.Builder()
            .url(request.defaultUrl)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val response = provisionClient.newCall(httpRequest).execute()
        val body = response.body?.bytes()
            ?: throw RuntimeException("Empty provision response (HTTP ${response.code})")
        if (!response.isSuccessful) {
            Log.e(TAG, "Provision failed: HTTP ${response.code}")
            throw RuntimeException("Provision failed: HTTP ${response.code}")
        }
        return body
    }
}
