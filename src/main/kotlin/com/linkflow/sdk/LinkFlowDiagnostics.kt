package com.linkflow.sdk

/**
 * Support snapshot of the SDK's state (2.2.0+), from [LinkFlowSDK.getDiagnostics].
 *
 * Answers "why didn't my deep link fire?" without exposing anything that
 * identifies the user or device: no install id, token, advertising id, customer
 * user id, click token or app key — only whether they exist.
 */
data class LinkFlowDiagnostics(
    val sdkVersion: String,
    val platform: String = "android",
    /** Host of the configured API base URL, e.g. "thelinkflow.app". Never the full URL. */
    val apiHost: String?,
    val appKeyConfigured: Boolean,
    /** "not_required", "pending", "granted" or "denied". */
    val consentState: String,
    val advertisingIdConsent: Boolean?,
    /** True while a launch is buffered waiting for consent. */
    val launchPendingConsent: Boolean,
    val hasInstallId: Boolean,
    val hasInstallToken: Boolean,
    val customerUserIdSet: Boolean,
    val attributionComplete: Boolean,
    /** Epoch milliseconds of the last attribution request's outcome, if any. */
    val lastResolveAtMillis: Long?,
    val lastResolveResult: ResolveResultCategory?,
    /** `match.method` from the last successful resolve, when the server reported one. */
    val lastMatchMethod: String?,
    val queuedEventCount: Int,
    val deepLinksHeld: Boolean,
    val heldDeepLinkCount: Int,
    val attributionCallbackSet: Boolean,
) {
    /** JSON-friendly map, e.g. for logging or attaching to a support ticket. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "sdkVersion" to sdkVersion,
        "platform" to platform,
        "apiHost" to apiHost,
        "appKeyConfigured" to appKeyConfigured,
        "consentState" to consentState,
        "advertisingIdConsent" to advertisingIdConsent,
        "launchPendingConsent" to launchPendingConsent,
        "hasInstallId" to hasInstallId,
        "hasInstallToken" to hasInstallToken,
        "customerUserIdSet" to customerUserIdSet,
        "attributionComplete" to attributionComplete,
        "lastResolveAt" to lastResolveAtMillis?.let { LinkFlowSDK.isoUtc(it) },
        "lastResolveResult" to lastResolveResult?.wireName,
        "lastMatchMethod" to lastMatchMethod,
        "queuedEventCount" to queuedEventCount,
        "deepLinksHeld" to deepLinksHeld,
        "heldDeepLinkCount" to heldDeepLinkCount,
        "attributionCallbackSet" to attributionCallbackSet,
    )

    companion object {
        /** Host only; null for an unparseable URL. Never returns path, query or credentials. */
        internal fun hostOf(url: String): String? =
            runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }

        internal fun consentState(requireConsent: Boolean, consent: LinkFlowConsent?): String = when {
            consent == null -> "pending"
            !consent.attribution -> "denied"
            !requireConsent -> "not_required"
            else -> "granted"
        }
    }
}
