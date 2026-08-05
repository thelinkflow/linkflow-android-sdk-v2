package com.linkflow.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import kotlin.coroutines.resume

/**
 * LinkFlow SDK for Android.
 *
 * Handles deferred deep linking and attribution.
 *
 * Minimal integration:
 * ```
 * LinkFlowSDK.initialize(this, LinkFlowConfig(appKey = "lfa_..."))
 *     .setAttributionCallback(callback)
 * LinkFlowSDK.getInstance().handleAppLaunch(intent)
 * ```
 */
class LinkFlowSDK private constructor(
    private val context: Context,
    private val config: LinkFlowConfig,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val eventQueue = EventQueue(context)
    private val http = LinkFlowHttp(config, ::log, ::logError)

    private var attributionCallback: AttributionCallback? = null

    @Volatile private var installId: String? = null
    @Volatile private var installToken: String? = null
    @Volatile private var lastResult: AttributionResult? = null

    /** Set when consent is required but not yet granted; replayed on grant. */
    @Volatile private var pendingLaunchIntent: Intent? = null
    @Volatile private var hasPendingLaunch = false
    @Volatile private var consent: LinkFlowConsent? = null

    companion object {
        private const val TAG = "LinkFlow"
        private const val PREFS = "linkflow_prefs"

        private const val KEY_ATTRIBUTION_COMPLETE = "attribution_complete"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_INSTALL_TOKEN = "install_token"
        private const val KEY_LAST_RESULT = "last_attribution_result"

        /** Reported to the server as `sdkVersion`. Keep in step with the published artifact version. */
        const val SDK_VERSION = "2.0.0"

        @Volatile
        private var instance: LinkFlowSDK? = null

        /**
         * Initializes the SDK.
         *
         * Repeat calls return the existing instance; the configuration from the
         * first call wins.
         */
        @JvmStatic
        fun initialize(context: Context, config: LinkFlowConfig): LinkFlowSDK {
            return instance ?: synchronized(this) {
                instance ?: LinkFlowSDK(context.applicationContext, config).also { instance = it }
            }
        }

        /**
         * Legacy entry point, retained so existing integrations keep compiling.
         *
         * Prefer the [LinkFlowConfig] overload — it is the only way to supply an
         * app key or consent policy.
         */
        @JvmStatic
        @JvmOverloads
        fun initialize(
            context: Context,
            apiBaseUrl: String = LinkFlowConfig.DEFAULT_API_BASE_URL,
            enableLogging: Boolean = false,
        ): LinkFlowSDK = initialize(
            context,
            LinkFlowConfig(apiBaseUrl = apiBaseUrl, enableLogging = enableLogging),
        )

        @JvmStatic
        fun getInstance(): LinkFlowSDK =
            instance ?: throw IllegalStateException("LinkFlowSDK not initialized. Call initialize() first.")

        /** Test/teardown hook. */
        @JvmStatic
        internal fun resetForTesting() {
            synchronized(this) {
                instance?.scope?.cancel()
                instance = null
            }
        }
    }

    init {
        installId = prefs.getString(KEY_INSTALL_ID, null)
        installToken = prefs.getString(KEY_INSTALL_TOKEN, null)

        if (!config.requireConsent) {
            consent = LinkFlowConsent(attribution = true, advertisingId = config.collectAdvertisingId)
        }
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    fun setAttributionCallback(callback: AttributionCallback): LinkFlowSDK {
        this.attributionCallback = callback
        return this
    }

    /**
     * Records the user's consent decision.
     *
     * Only required when the SDK was configured with `requireConsent = true`.
     * Granting consent replays any launch that was buffered while waiting, so no
     * attribution is lost to the consent prompt.
     */
    fun setConsent(consent: LinkFlowConsent) {
        this.consent = consent
        log("Consent set: attribution=${consent.attribution} advertisingId=${consent.advertisingId}")

        if (consent.attribution && hasPendingLaunch) {
            hasPendingLaunch = false
            val intent = pendingLaunchIntent
            pendingLaunchIntent = null
            handleAppLaunch(intent)
        } else if (consent.attribution) {
            scope.launch { flushEventQueue() }
        }
    }

    /**
     * Handles app launch: resolves attribution on first launch, otherwise
     * processes any deep link and flushes queued events.
     *
     * Safe to call on every launch.
     */
    fun handleAppLaunch(intent: Intent?) {
        val granted = consent
        if (granted == null || !granted.attribution) {
            log("Consent pending; buffering app launch until setConsent() is called")
            pendingLaunchIntent = intent
            hasPendingLaunch = true
            return
        }

        scope.launch {
            try {
                // Attribution is retried until it actually succeeds. The flag used to
                // be set before the network call, so a launch on a flaky connection
                // lost the attribution permanently.
                if (!prefs.getBoolean(KEY_ATTRIBUTION_COMPLETE, false)) {
                    log("Attribution not yet resolved; resolving")
                    resolveAttribution(intent)
                } else {
                    handleDeepLinkInternal(intent?.data)
                }

                flushEventQueue()
            } catch (e: Exception) {
                logError("Error handling app launch", e)
                notifyError(e)
            }
        }
    }

    /**
     * Feeds a deep link into the SDK.
     *
     * Call from `onNewIntent` for links received while the app is running. The
     * React Native bridge also routes through here.
     */
    fun handleDeepLink(uri: Uri?) {
        scope.launch { handleDeepLinkInternal(uri) }
    }

    /** Overload for callers holding an Intent. */
    fun handleDeepLink(intent: Intent?) = handleDeepLink(intent?.data)

    /**
     * Tracks an in-app event.
     *
     * Events are queued durably and retried, so a call made while offline is
     * delivered later rather than dropped. Delivery is idempotent.
     */
    @JvmOverloads
    fun trackEvent(eventName: String, params: Map<String, Any>? = null, revenue: Double? = null) {
        val granted = consent
        if (granted == null || !granted.attribution) {
            log("Consent pending; dropping event '$eventName'")
            return
        }

        val payload = JSONObject().apply {
            put("eventName", eventName)
            params?.let { put("eventParams", JSONObject(it)) }
            revenue?.let { put("revenue", it) }
        }

        eventQueue.enqueue(payload)
        scope.launch { flushEventQueue() }
    }

    /** The most recent attribution result, restored from disk if needed. */
    fun getAttributionResult(): AttributionResult? {
        lastResult?.let { return it }

        val raw = prefs.getString(KEY_LAST_RESULT, null) ?: return null
        return try {
            parseAttributionResult(JSONObject(raw)).also { lastResult = it }
        } catch (e: Exception) {
            logError("Could not restore cached attribution result", e)
            null
        }
    }

    /** Number of events waiting to be delivered. Useful in diagnostics. */
    fun pendingEventCount(): Int = eventQueue.size()

    // ------------------------------------------------------------------------
    // Attribution
    // ------------------------------------------------------------------------

    private suspend fun resolveAttribution(intent: Intent?) {
        try {
            val referrerDetails = getInstallReferrer()
            val advertisingId = if (consent?.advertisingId == true && config.collectAdvertisingId) {
                getAdvertisingId()
            } else {
                null
            }

            val clickToken = intent?.data?.getQueryParameter("click_token")

            val requestData = JSONObject().apply {
                put("platform", "android")
                put("bundleId", context.packageName)

                // The referrer is how the click token survives the Play Store: Play
                // forwards only a parameter named `referrer` into this API.
                referrerDetails?.let {
                    put("installReferrer", it.referrer)
                    put("referrerClickTimestampSeconds", it.clickTimestampSeconds)
                    put("installBeginTimestampSeconds", it.installBeginTimestampSeconds)
                }

                advertisingId?.let { put("advertisingId", it) }
                clickToken?.let { put("clickToken", it) }

                put("deviceFingerprint", buildDeviceFingerprint())
                put("appVersion", getAppVersion())
                put("osVersion", Build.VERSION.RELEASE)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("sdkVersion", SDK_VERSION)
            }

            log("Resolving attribution: ${redact(requestData)}")

            when (val outcome = http.send(
                "${config.apiBaseUrl}/api/attribution/resolve", "POST",
                requestData.toString(), config.appKey,
            )) {
                is HttpOutcome.Success -> handleResolveSuccess(JSONObject(outcome.body))

                is HttpOutcome.PermanentFailure -> {
                    // A 4xx will never succeed. Stop retrying on future launches so
                    // we do not hit the server forever, but surface the error.
                    logError("Attribution rejected (HTTP ${outcome.code}); not retrying", null)
                    prefs.edit().putBoolean(KEY_ATTRIBUTION_COMPLETE, true).apply()
                    notifyResolved(AttributionResult(attributed = false))
                }

                is HttpOutcome.TransientFailure -> {
                    // Leave the flag unset so the next launch tries again.
                    logError("Attribution could not be delivered; will retry on next launch", outcome.error)
                    notifyError(outcome.error ?: IllegalStateException("Attribution request failed"))
                }
            }
        } catch (e: Exception) {
            logError("Error resolving attribution", e)
            notifyError(e)
        }
    }

    private suspend fun handleResolveSuccess(response: JSONObject) {
        // Only now is the attribution genuinely complete.
        prefs.edit().putBoolean(KEY_ATTRIBUTION_COMPLETE, true).apply()

        val attributed = response.optBoolean("attributed", false)

        if (attributed) {
            installId = response.stringOrNull("installId")
            // Returned once, at creation. Authenticates subsequent event calls.
            response.stringOrNull("installToken")?.let {
                installToken = it
            }

            prefs.edit()
                .putString(KEY_INSTALL_ID, installId)
                .putString(KEY_INSTALL_TOKEN, installToken)
                .putString(KEY_LAST_RESULT, response.toString())
                .apply()
        }

        val result = parseAttributionResult(response)
        lastResult = result
        log("Attribution resolved: attributed=${result.attributed} method=${result.attributionMethod}")
        notifyResolved(result)
    }

    private fun parseAttributionResult(response: JSONObject): AttributionResult = AttributionResult(
        attributed = response.optBoolean("attributed", false),
        deepLinkValue = response.stringOrNull("deepLinkValue"),
        deepLinkParams = response.optJSONObject("deepLinkParams")?.toMap() ?: emptyMap(),
        campaignData = response.optJSONObject("campaignData")?.toMap() ?: emptyMap(),
        attributionMethod = response.stringOrNull("attributionMethod"),
        confidence = if (response.has("confidence")) response.optDouble("confidence") else null,
        isReinstall = response.optBoolean("isReinstall", false),
    )

    private suspend fun handleDeepLinkInternal(uri: Uri?) {
        if (uri == null) return
        log("Handling deep link: $uri")
        withContext(Dispatchers.Main) {
            attributionCallback?.onDeepLinkReceived(uri)
        }
    }

    // ------------------------------------------------------------------------
    // Event delivery
    // ------------------------------------------------------------------------

    private suspend fun flushEventQueue() {
        val id = installId ?: prefs.getString(KEY_INSTALL_ID, null)
        if (id == null) {
            if (eventQueue.size() > 0) {
                log("Holding ${eventQueue.size()} event(s): attribution has not produced an install id yet")
            }
            return
        }

        for (event in eventQueue.peekAll()) {
            val eventId = event.optString("eventId")

            val payload = JSONObject(event.toString()).apply {
                put("installId", id)
                installToken?.let { put("installToken", it) }
            }

            when (val outcome = http.send(
                "${config.apiBaseUrl}/api/attribution/event", "POST",
                payload.toString(), config.appKey,
            )) {
                is HttpOutcome.Success -> {
                    eventQueue.remove(eventId)
                    log("Event delivered: ${event.optString("eventName")}")
                }

                is HttpOutcome.PermanentFailure -> {
                    // Retrying a rejected event forever would block the queue.
                    eventQueue.remove(eventId)
                    logError("Event rejected (HTTP ${outcome.code}); discarding", null)
                }

                is HttpOutcome.TransientFailure -> {
                    // Stop the flush: later events are likely to fail the same way,
                    // and order is worth preserving.
                    log("Event delivery deferred; ${eventQueue.size()} event(s) still queued")
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Device signals
    // ------------------------------------------------------------------------

    internal data class ReferrerDetails(
        val referrer: String,
        val clickTimestampSeconds: Long,
        val installBeginTimestampSeconds: Long,
    )

    private suspend fun getInstallReferrer(): ReferrerDetails? = suspendCancellableCoroutine { continuation ->
        var resumed = false
        fun resumeOnce(value: ReferrerDetails?) {
            if (!resumed) {
                resumed = true
                continuation.resume(value)
            }
        }

        try {
            val client = InstallReferrerClient.newBuilder(context).build()

            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    try {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            val response = client.installReferrer
                            resumeOnce(
                                ReferrerDetails(
                                    referrer = response.installReferrer,
                                    clickTimestampSeconds = response.referrerClickTimestampSeconds,
                                    installBeginTimestampSeconds = response.installBeginTimestampSeconds,
                                )
                            )
                        } else {
                            log("Install referrer unavailable (response code $responseCode)")
                            resumeOnce(null)
                        }
                    } catch (e: Exception) {
                        logError("Error reading install referrer", e)
                        resumeOnce(null)
                    } finally {
                        runCatching { client.endConnection() }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    log("Install referrer service disconnected")
                    resumeOnce(null)
                }
            })
        } catch (e: Exception) {
            logError("Error connecting to install referrer service", e)
            resumeOnce(null)
        }
    }

    private suspend fun getAdvertisingId(): String? = withContext(Dispatchers.IO) {
        try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            if (info.isLimitAdTrackingEnabled) {
                log("Advertising ID unavailable: limit ad tracking is enabled")
                null
            } else {
                info.id
            }
        } catch (e: Exception) {
            // Play Services missing (common on non-GMS devices) is not an error.
            log("Advertising ID unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Signals used for probabilistic matching when no deterministic identifier is
     * available.
     *
     * The IP address is deliberately absent: it is taken from the connection
     * server-side. This used to send a hardcoded "0.0.0.0", which meant the
     * fingerprint could never match a real click.
     */
    private fun buildDeviceFingerprint(): JSONObject {
        val metrics = context.resources.displayMetrics
        return JSONObject().apply {
            put("timezone", TimeZone.getDefault().id)
            put("language", Locale.getDefault().language)
            put("screenWidth", metrics.widthPixels)
            put("screenHeight", metrics.heightPixels)
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("osVersion", Build.VERSION.RELEASE)
        }
    }

    private fun getAppVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    // ------------------------------------------------------------------------
    // Callbacks and logging
    // ------------------------------------------------------------------------

    private suspend fun notifyResolved(result: AttributionResult) = withContext(Dispatchers.Main) {
        runCatching { attributionCallback?.onAttributionResolved(result) }
            .onFailure { logError("Attribution callback threw", it) }
    }

    private suspend fun notifyError(error: Throwable) = withContext(Dispatchers.Main) {
        runCatching { attributionCallback?.onAttributionError(error) }
            .onFailure { logError("Attribution error callback threw", it) }
    }

    private fun log(message: String) {
        if (config.enableLogging) Log.d(TAG, message)
    }

    private fun logError(message: String, error: Throwable?) {
        // Errors are always surfaced; debug logging only controls verbosity.
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
    }

    /**
     * Strips identifiers before a payload reaches the log.
     *
     * The previous implementation logged the full request body at info level,
     * putting the advertising ID into logcat where any app with log access on
     * older Android versions could read it.
     */
    private fun redact(payload: JSONObject): String {
        val copy = JSONObject(payload.toString())
        for (key in listOf("advertisingId", "installReferrer", "clickToken", "installToken")) {
            if (copy.has(key)) copy.put(key, "«redacted»")
        }
        return copy.toString()
    }

    private fun JSONObject.toMap(): Map<String, Any> =
        keys().asSequence().associateWith { get(it) }

    /**
     * Reads an optional string, treating absent, JSON-null and blank alike.
     *
     * `optString(name, null)` crosses a Java interop boundary and comes back as a
     * platform type, which silently defeats Kotlin's null checking at every call
     * site. This keeps the nullability explicit.
     */
    private fun JSONObject.stringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------------
    // Rewards
    // ------------------------------------------------------------------------

    /**
     * Validates a reward for the attributed install.
     *
     * Now goes through the retrying transport, so a transient network failure no
     * longer silently reports the reward as unavailable.
     */
    fun validateReward(rewardId: String, callback: (RewardValidation?) -> Unit) {
        scope.launch {
            val result = runCatching {
                val id = installId ?: prefs.getString(KEY_INSTALL_ID, null)
                if (id == null) {
                    logError("Cannot validate reward: no install id yet", null)
                    return@runCatching null
                }

                val request = JSONObject().apply {
                    put("installId", id)
                    put("rewardId", rewardId)
                    put("deviceFingerprint", buildDeviceFingerprint())
                    installToken?.let { put("installToken", it) }
                }

                when (val outcome = http.send(
                    "${config.apiBaseUrl}/api/rewards/validate", "POST",
                    request.toString(), config.appKey,
                )) {
                    is HttpOutcome.Success -> parseRewardValidation(JSONObject(outcome.body), rewardId)
                    else -> null
                }
            }.onFailure { logError("Error validating reward", it) }.getOrNull()

            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    /** Redeems a reward using a token from [validateReward]. */
    @JvmOverloads
    fun redeemReward(
        redemptionToken: String,
        purchaseAmount: Double? = null,
        metadata: Map<String, Any>? = null,
        callback: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            var success = false
            var message: String? = null

            runCatching {
                val request = JSONObject().apply {
                    put("redemptionToken", redemptionToken)
                    put("deviceFingerprint", buildDeviceFingerprint())
                    purchaseAmount?.let { put("purchaseAmount", it) }
                    metadata?.let { put("metadata", JSONObject(it)) }
                    installToken?.let { put("installToken", it) }
                }

                when (val outcome = http.send(
                    "${config.apiBaseUrl}/api/rewards/redeem", "POST",
                    request.toString(), config.appKey,
                )) {
                    is HttpOutcome.Success -> {
                        val json = JSONObject(outcome.body)
                        success = json.optBoolean("success", false)
                        message = json.stringOrNull("message")
                    }
                    is HttpOutcome.PermanentFailure -> message = "Rejected (HTTP ${outcome.code})"
                    is HttpOutcome.TransientFailure -> message = "Network error; please retry"
                }
            }.onFailure {
                logError("Error redeeming reward", it)
                message = it.message
            }

            withContext(Dispatchers.Main) { callback(success, message) }
        }
    }

    /** Rewards attached to the deep link the user clicked, from the cached attribution result. */
    fun getAvailableRewards(): List<Reward> {
        val raw = prefs.getString("available_rewards", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            List(array.length()) { parseReward(array.getJSONObject(it)) }
        } catch (e: Exception) {
            logError("Error parsing cached rewards", e)
            emptyList()
        }
    }

    private fun parseRewardValidation(json: JSONObject, rewardId: String): RewardValidation {
        if (!json.optBoolean("valid", false)) {
            val errors = json.optJSONArray("errors")
            return RewardValidation(
                valid = false,
                redemptionToken = null,
                reward = null,
                errors = errors?.let { arr -> List(arr.length()) { arr.getString(it) } },
            )
        }

        val token = json.stringOrNull("redemptionToken")
        token?.let {
            prefs.edit().putString("redemption_token_$rewardId", it).apply()
        }

        return RewardValidation(
            valid = true,
            redemptionToken = token,
            reward = json.optJSONObject("reward")?.let { parseReward(it) },
            errors = null,
        )
    }

    private fun parseReward(obj: JSONObject) = Reward(
        id = obj.optString("id"),
        type = obj.optString("type"),
        value = obj.optJSONObject("value")?.toMap() ?: emptyMap(),
        code = obj.stringOrNull("code"),
        title = obj.optString("title"),
        description = obj.optString("description"),
        expiresAt = obj.stringOrNull("expiresAt"),
    )

    // ------------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------------

    data class Reward(
        val id: String,
        /** "discount", "credit", "unlock" or "free_trial". */
        val type: String,
        val value: Map<String, Any>,
        val code: String?,
        val title: String,
        val description: String,
        val expiresAt: String?,
    )

    data class RewardValidation(
        val valid: Boolean,
        val redemptionToken: String?,
        val reward: Reward?,
        val errors: List<String>?,
    )

    data class AttributionResult(
        val attributed: Boolean,
        val deepLinkValue: String? = null,
        val deepLinkParams: Map<String, Any> = emptyMap(),
        val campaignData: Map<String, Any> = emptyMap(),
        /** "install_referrer", "click_token", "device_id" or "fingerprint". */
        val attributionMethod: String? = null,
        /** 0.0–1.0. Deterministic matches are 1.0; fingerprint matches are scored. */
        val confidence: Double? = null,
        /** True when this device had already installed the app before. */
        val isReinstall: Boolean = false,
        /** Rewards attached to the clicked link. */
        val rewards: List<Reward> = emptyList(),
    )

    interface AttributionCallback {
        fun onAttributionResolved(result: AttributionResult)
        fun onAttributionError(error: Throwable)
        fun onDeepLinkReceived(uri: Uri)
    }
}
