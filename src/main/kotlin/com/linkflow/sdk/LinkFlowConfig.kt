package com.linkflow.sdk

/**
 * SDK configuration.
 *
 * The older `initialize(context, apiBaseUrl, enableLogging)` entry point still
 * works and maps onto this, so existing integrations need no changes.
 */
data class LinkFlowConfig @JvmOverloads constructor(

    /** LinkFlow API base URL. */
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,

    /**
     * Public app key issued from the dashboard (App Keys). Embeddable in the app
     * binary. Optional today; required once the server sets
     * `ATTRIBUTION_REQUIRE_APP_KEY`.
     */
    val appKey: String? = null,

    /** Emit debug logs. Identifiers are redacted regardless of this setting. */
    val enableLogging: Boolean = false,

    /**
     * When true the SDK collects nothing and sends nothing until
     * [LinkFlowSDK.setConsent] is called. Use this if your app must gate
     * attribution behind a consent prompt (GDPR, DMA, CCPA).
     *
     * Attribution work requested while consent is pending is buffered, not
     * dropped, and runs as soon as consent is granted.
     */
    val requireConsent: Boolean = false,

    /**
     * Whether to read the Google Advertising ID. Independent of [requireConsent]
     * so an app can attribute without ever touching an advertising identifier.
     */
    val collectAdvertisingId: Boolean = true,

    /** Attempts for a network call before giving up on this app run. */
    val maxRetries: Int = 4,

    /** Base delay for exponential backoff, in milliseconds. */
    val retryBaseDelayMs: Long = 1_000,

    /** Per-request timeout, in milliseconds. */
    val timeoutMs: Int = 15_000,
) {
    companion object {
        const val DEFAULT_API_BASE_URL = "https://thelinkflow.app"
    }
}

/** User consent state. */
data class LinkFlowConsent(
    /** Permits attribution resolution and event reporting. */
    val attribution: Boolean,
    /** Permits reading the advertising identifier. */
    val advertisingId: Boolean = attribution,
)
