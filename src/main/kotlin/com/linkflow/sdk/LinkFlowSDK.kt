package com.linkflow.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.*

/**
 * LinkFlow SDK for Android
 * Handles deferred deep linking and attribution for Android apps
 */
class LinkFlowSDK private constructor(
    private val context: Context,
    private val apiBaseUrl: String,
    private val enableLogging: Boolean
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var attributionCallback: AttributionCallback? = null
    private var installId: String? = null

    companion object {
        @Volatile
        private var instance: LinkFlowSDK? = null

        fun initialize(
            context: Context,
            apiBaseUrl: String = "https://thelinkflow.app",
            enableLogging: Boolean = false
        ): LinkFlowSDK {
            return instance ?: synchronized(this) {
                instance ?: LinkFlowSDK(
                    context.applicationContext,
                    apiBaseUrl,
                    enableLogging
                ).also { instance = it }
            }
        }

        fun getInstance(): LinkFlowSDK {
            return instance ?: throw IllegalStateException("LinkFlowSDK not initialized. Call initialize() first.")
        }
    }

    /**
     * Set callback for attribution results
     */
    fun setAttributionCallback(callback: AttributionCallback) {
        this.attributionCallback = callback
    }

    /**
     * Handle app launch and resolve attribution
     * Call this in your Application.onCreate() or main Activity
     */
    fun handleAppLaunch(intent: Intent?) {
        scope.launch {
            try {
                log("Handling app launch")

                // Check if this is first install
                val prefs = context.getSharedPreferences("linkflow_prefs", Context.MODE_PRIVATE)
                val isFirstLaunch = prefs.getBoolean("is_first_launch", true)

                if (isFirstLaunch) {
                    log("First launch detected, resolving attribution")
                    resolveAttribution(intent)
                    prefs.edit().putBoolean("is_first_launch", false).apply()
                } else {
                    // Handle deep link for existing users
                    handleDeepLink(intent)
                }
            } catch (e: Exception) {
                logError("Error handling app launch", e)
            }
        }
    }

    /**
     * Resolve attribution for first app launch
     */
    private suspend fun resolveAttribution(intent: Intent?) {
        try {
            // Get install referrer
            val installReferrer = getInstallReferrer()

            // Get advertising ID
            val advertisingId = getAdvertisingId()

            // Extract click token from intent if available
            val clickToken = intent?.data?.getQueryParameter("click_token")

            // Build device fingerprint
            val fingerprint = buildDeviceFingerprint()

            // Create attribution request
            val requestData = JSONObject().apply {
                put("platform", "android")
                put("installReferrer", installReferrer)
                put("advertisingId", advertisingId)
                put("clickToken", clickToken)
                put("deviceFingerprint", fingerprint)
                put("appVersion", getAppVersion())
                put("osVersion", Build.VERSION.RELEASE)
                put("sdkVersion", "1.0.0")
            }

            log("Sending attribution request: $requestData")

            // Send request to server
            val response = sendHttpRequest(
                "$apiBaseUrl/api/attribution/resolve",
                "POST",
                requestData.toString()
            )

            log("Attribution response: $response")

            val result = JSONObject(response)
            val attributed = result.getBoolean("attributed")

            if (attributed) {
                // Save install ID for event tracking
                installId = result.optString("installId")
                context.getSharedPreferences("linkflow_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("install_id", installId)
                    .apply()

                // Extract deep link data
                val deepLinkValue = result.optString("deepLinkValue")
                val deepLinkParams = result.optJSONObject("deepLinkParams")
                val campaignData = result.optJSONObject("campaignData")

                // Notify callback
                withContext(Dispatchers.Main) {
                    attributionCallback?.onAttributionResolved(
                        AttributionResult(
                            attributed = true,
                            deepLinkValue = deepLinkValue,
                            deepLinkParams = deepLinkParams?.toMap() ?: emptyMap(),
                            campaignData = campaignData?.toMap() ?: emptyMap()
                        )
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    attributionCallback?.onAttributionResolved(
                        AttributionResult(attributed = false)
                    )
                }
            }
        } catch (e: Exception) {
            logError("Error resolving attribution", e)
            withContext(Dispatchers.Main) {
                attributionCallback?.onAttributionError(e)
            }
        }
    }

    /**
     * Get Play Install Referrer
     */
    private suspend fun getInstallReferrer(): String? = suspendCancellableCoroutine { continuation ->
        try {
            val referrerClient = InstallReferrerClient.newBuilder(context).build()

            referrerClient.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            try {
                                val response = referrerClient.installReferrer
                                val referrer = response.installReferrer
                                log("Install referrer: $referrer")
                                continuation.resume(referrer) {}
                            } catch (e: Exception) {
                                logError("Error getting install referrer", e)
                                continuation.resume(null) {}
                            } finally {
                                referrerClient.endConnection()
                            }
                        }
                        else -> {
                            log("Install referrer response code: $responseCode")
                            continuation.resume(null) {}
                            referrerClient.endConnection()
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    log("Install referrer service disconnected")
                    continuation.resume(null) {}
                }
            })
        } catch (e: Exception) {
            logError("Error connecting to install referrer", e)
            continuation.resume(null) {}
        }
    }

    /**
     * Get Google Advertising ID
     */
    private suspend fun getAdvertisingId(): String? = withContext(Dispatchers.IO) {
        try {
            val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
            if (!adInfo.isLimitAdTrackingEnabled) {
                adInfo.id
            } else {
                log("Ad tracking is limited")
                null
            }
        } catch (e: Exception) {
            logError("Error getting advertising ID", e)
            null
        }
    }

    /**
     * Build device fingerprint for probabilistic attribution
     */
    private fun buildDeviceFingerprint(): JSONObject {
        return JSONObject().apply {
            put("ipAddress", getIpAddress())
            put("userAgent", System.getProperty("http.agent") ?: "Android")
            put("timezone", TimeZone.getDefault().id)
            put("language", Locale.getDefault().language)
            put("screenWidth", context.resources.displayMetrics.widthPixels)
            put("screenHeight", context.resources.displayMetrics.heightPixels)
        }
    }

    /**
     * Handle deep link for existing users
     */
    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            log("Handling deep link: $uri")
            // Extract parameters and notify callback
            scope.launch {
                withContext(Dispatchers.Main) {
                    attributionCallback?.onDeepLinkReceived(uri)
                }
            }
        }
    }

    /**
     * Track in-app event
     */
    fun trackEvent(eventName: String, params: Map<String, Any>? = null, revenue: Double? = null) {
        scope.launch {
            try {
                val savedInstallId = installId ?: context.getSharedPreferences("linkflow_prefs", Context.MODE_PRIVATE)
                    .getString("install_id", null)

                if (savedInstallId == null) {
                    logError("Cannot track event: install ID not found", null)
                    return@launch
                }

                val requestData = JSONObject().apply {
                    put("installId", savedInstallId)
                    put("eventName", eventName)
                    if (params != null) {
                        put("eventParams", JSONObject(params))
                    }
                    if (revenue != null) {
                        put("revenue", revenue)
                    }
                }

                sendHttpRequest(
                    "$apiBaseUrl/api/attribution/event",
                    "POST",
                    requestData.toString()
                )

                log("Event tracked: $eventName")
            } catch (e: Exception) {
                logError("Error tracking event", e)
            }
        }
    }

    // Helper functions
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getIpAddress(): String {
        // In production, you'd get this from the server
        return "0.0.0.0"
    }

    private suspend fun sendHttpRequest(url: String, method: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw Exception("HTTP error code: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun log(message: String) {
        if (enableLogging) {
            println("[LinkFlow] $message")
        }
    }

    private fun logError(message: String, error: Throwable?) {
        if (enableLogging) {
            println("[LinkFlow ERROR] $message")
            error?.printStackTrace()
        }
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            map[key] = get(key)
        }
        return map
    }

    // ========================================================================
    // REWARDS SYSTEM - Phase 3 Implementation
    // ========================================================================

    /**
     * Validate a reward after attribution resolution
     * Call this when you want to check if a reward is available for the user
     */
    fun validateReward(rewardId: String, callback: (RewardValidation?) -> Unit) {
        scope.launch {
            try {
                val savedInstallId = installId ?: context.getSharedPreferences("linkflow_prefs", Context.MODE_PRIVATE)
                    .getString("install_id", null)

                if (savedInstallId == null) {
                    logError("Cannot validate reward: install ID not found", null)
                    withContext(Dispatchers.Main) {
                        callback(null)
                    }
                    return@launch
                }

                val deviceFingerprint = buildDeviceFingerprint()
                val requestData = JSONObject().apply {
                    put("installId", savedInstallId)
                    put("rewardId", rewardId)
                    put("deviceFingerprint", deviceFingerprint)
                }

                val response = sendHttpRequest(
                    "$apiBaseUrl/api/rewards/validate",
                    "POST",
                    requestData.toString()
                )

                val jsonResponse = JSONObject(response)
                val validation = if (jsonResponse.getBoolean("valid")) {
                    val rewardObj = jsonResponse.getJSONObject("reward")
                    RewardValidation(
                        valid = true,
                        redemptionToken = jsonResponse.getString("redemptionToken"),
                        reward = Reward(
                            id = rewardObj.getString("id"),
                            type = rewardObj.getString("type"),
                            value = rewardObj.getJSONObject("value").toMap(),
                            code = rewardObj.optString("code", null),
                            title = rewardObj.getString("title"),
                            description = rewardObj.getString("description"),
                            expiresAt = rewardObj.optString("expiresAt", null)
                        ),
                        errors = null
                    )
                } else {
                    RewardValidation(
                        valid = false,
                        redemptionToken = null,
                        reward = null,
                        errors = jsonResponse.getJSONArray("errors").let { arr ->
                            List(arr.length()) { arr.getString(it) }
                        }
                    )
                }

                // Save redemption token for later use
                if (validation.valid && validation.redemptionToken != null) {
                    context.getSharedPreferences("linkflow_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("redemption_token_$rewardId", validation.redemptionToken)
                        .apply()
                }

                withContext(Dispatchers.Main) {
                    callback(validation)
                }
                log("Reward validated: ${validation.valid}")
            } catch (e: Exception) {
                logError("Error validating reward", e)
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    /**
     * Redeem a reward after user completes qualifying action
     * @param redemptionToken Token received from validateReward()
     * @param purchaseAmount Optional purchase amount if reward requires minimum spend
     * @param metadata Optional additional data
     */
    fun redeemReward(
        redemptionToken: String,
        purchaseAmount: Double? = null,
        metadata: Map<String, Any>? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        scope.launch {
            try {
                val deviceFingerprint = buildDeviceFingerprint()
                val requestData = JSONObject().apply {
                    put("redemptionToken", redemptionToken)
                    put("deviceFingerprint", deviceFingerprint)
                    if (purchaseAmount != null) {
                        put("purchaseAmount", purchaseAmount)
                    }
                    if (metadata != null) {
                        put("metadata", JSONObject(metadata))
                    }
                }

                val response = sendHttpRequest(
                    "$apiBaseUrl/api/rewards/redeem",
                    "POST",
                    requestData.toString()
                )

                val jsonResponse = JSONObject(response)
                val success = jsonResponse.getBoolean("success")
                val message = jsonResponse.optString("message", null)

                withContext(Dispatchers.Main) {
                    callback(success, message)
                }
                log("Reward redeemed: $success")
            } catch (e: Exception) {
                logError("Error redeeming reward", e)
                withContext(Dispatchers.Main) {
                    callback(false, e.message)
                }
            }
        }
    }

    /**
     * Get available rewards from saved attribution result
     * Returns rewards that were attached to the deep link the user clicked
     */
    fun getAvailableRewards(): List<Reward> {
        val prefs = context.getSharedPreferences("linkflow_prefs", Context.MODE_PRIVATE)
        val rewardsJson = prefs.getString("available_rewards", null) ?: return emptyList()

        return try {
            val jsonArray = org.json.JSONArray(rewardsJson)
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                Reward(
                    id = obj.getString("id"),
                    type = obj.getString("type"),
                    value = obj.getJSONObject("value").toMap(),
                    code = obj.optString("code", null),
                    title = obj.getString("title"),
                    description = obj.getString("description"),
                    expiresAt = obj.optString("expiresAt", null)
                )
            }
        } catch (e: Exception) {
            logError("Error parsing saved rewards", e)
            emptyList()
        }
    }

    // Data classes
    data class AttributionResult(
        val attributed: Boolean,
        val deepLinkValue: String? = null,
        val deepLinkParams: Map<String, Any> = emptyMap(),
        val campaignData: Map<String, Any> = emptyMap(),
        val rewards: List<Reward> = emptyList() // Added rewards support
    )

    data class Reward(
        val id: String,
        val type: String, // "discount", "credit", "unlock", "free_trial"
        val value: Map<String, Any>, // {"amount": 10, "currency": "USD"} or {"percentage": 20}
        val code: String?, // Optional promo code
        val title: String,
        val description: String,
        val expiresAt: String?
    )

    data class RewardValidation(
        val valid: Boolean,
        val redemptionToken: String?,
        val reward: Reward?,
        val errors: List<String>?
    )

    interface AttributionCallback {
        fun onAttributionResolved(result: AttributionResult)
        fun onAttributionError(error: Throwable)
        fun onDeepLinkReceived(uri: Uri)
    }
}
