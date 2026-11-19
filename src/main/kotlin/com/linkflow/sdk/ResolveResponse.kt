package com.linkflow.sdk

import org.json.JSONObject

/**
 * How the server matched the install (2.2.0+), from the resolve response's
 * optional `match` object. Absent when talking to an older server.
 */
data class AttributionMatch(
    /** "install_referrer", "click_token", "advertising_id", "probabilistic" or "organic". */
    val method: String,
    /** 0.0–1.0 when reported. */
    val confidence: Double? = null,
)

/** Coarse outcome of the last attribution request, for diagnostics. Never carries identifiers. */
enum class ResolveResultCategory(val wireName: String) {
    ATTRIBUTED("attributed"),
    ORGANIC("organic"),
    /** The server answered 4xx; the SDK does not retry. */
    REJECTED("rejected"),
    /** Network error, timeout or 5xx; retried on the next launch. */
    NETWORK_ERROR("network_error"),
    /** 2xx with a body the SDK could not read. */
    INVALID_RESPONSE("invalid_response");

    companion object {
        fun fromWireName(name: String?): ResolveResultCategory? = values().firstOrNull { it.wireName == name }
    }
}

/**
 * Platform-independent reading of a `/api/attribution/resolve` response.
 *
 * Kept free of Android types so it can be unit-tested on the JVM.
 */
internal data class ParsedResolveResponse(
    val attributed: Boolean,
    /** Present for attributed installs and, from server 2.2, for organic ones too. */
    val installId: String?,
    /** Returned once, when the install is created. */
    val installToken: String?,
    val match: AttributionMatch?,
    val deepLinkValue: String?,
    val deepLinkParams: Map<String, Any>,
    val campaignData: Map<String, Any>,
    val attributionMethod: String?,
    val confidence: Double?,
    val isReinstall: Boolean,
) {
    val category: ResolveResultCategory
        get() = if (attributed) ResolveResultCategory.ATTRIBUTED else ResolveResultCategory.ORGANIC

    companion object {
        fun parse(json: JSONObject): ParsedResolveResponse = ParsedResolveResponse(
            attributed = json.optBoolean("attributed", false),
            installId = json.stringOrNull("installId"),
            installToken = json.stringOrNull("installToken"),
            match = parseMatch(json.optJSONObject("match")),
            deepLinkValue = json.stringOrNull("deepLinkValue"),
            deepLinkParams = objectOrJsonString(json, "deepLinkParams"),
            campaignData = objectOrJsonString(json, "campaignData"),
            attributionMethod = json.stringOrNull("attributionMethod"),
            confidence = json.doubleOrNull("confidence"),
            isReinstall = json.optBoolean("isReinstall", false),
        )

        internal fun parseMatch(obj: JSONObject?): AttributionMatch? {
            val method = obj?.stringOrNull("method") ?: return null
            return AttributionMatch(method = method, confidence = obj.doubleOrNull("confidence"))
        }

        /**
         * The server stores link params as a JSON string column and may return
         * either an object or that string (see docs/SDK_COMPATIBILITY.md rule 4).
         * Reading only the object form silently dropped the payload.
         */
        private fun objectOrJsonString(json: JSONObject, name: String): Map<String, Any> {
            if (!json.has(name) || json.isNull(name)) return emptyMap()
            json.optJSONObject(name)?.let { return it.toMap() }
            val raw = json.optString(name).trim()
            if (!raw.startsWith("{")) return emptyMap()
            return runCatching { JSONObject(raw).toMap() }.getOrDefault(emptyMap())
        }

        private fun JSONObject.toMap(): Map<String, Any> =
            keys().asSequence().associateWith { get(it) }

        private fun JSONObject.stringOrNull(name: String): String? {
            if (!has(name) || isNull(name)) return null
            return optString(name).takeIf { it.isNotBlank() }
        }

        private fun JSONObject.doubleOrNull(name: String): Double? {
            if (!has(name) || isNull(name)) return null
            return optDouble(name).takeUnless { it.isNaN() }
        }
    }
}
