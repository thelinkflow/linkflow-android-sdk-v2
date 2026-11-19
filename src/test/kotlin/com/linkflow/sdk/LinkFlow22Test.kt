package com.linkflow.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.0: deep link hold/release. */
class DeepLinkHoldQueueTest {

    @Test
    fun `not held by default so links are delivered immediately`() {
        val queue = DeepLinkHoldQueue<String>()
        assertFalse(queue.isHeld)
        assertFalse(queue.offer(DeepLinkKind.DIRECT, "a"))
        assertEquals(0, queue.size())
    }

    @Test
    fun `held links are queued and released in arrival order`() {
        val queue = DeepLinkHoldQueue<String>()
        queue.hold()
        assertTrue(queue.offer(DeepLinkKind.DEFERRED, "deferred"))
        assertTrue(queue.offer(DeepLinkKind.DIRECT, "direct"))

        assertEquals(listOf("deferred", "direct"), queue.release())
        assertFalse(queue.isHeld)
        assertEquals(0, queue.size())
    }

    @Test
    fun `only the latest link of each kind is kept`() {
        val queue = DeepLinkHoldQueue<String>()
        queue.hold()
        queue.offer(DeepLinkKind.DIRECT, "first")
        queue.offer(DeepLinkKind.DEFERRED, "deferred")
        queue.offer(DeepLinkKind.DIRECT, "second")
        queue.offer(DeepLinkKind.DIRECT, "third")

        assertEquals(2, queue.size())
        // The direct link moved behind the deferred one because it arrived last.
        assertEquals(listOf("deferred", "third"), queue.release())
    }

    @Test
    fun `release without hold is a harmless no-op`() {
        val queue = DeepLinkHoldQueue<String>()
        assertEquals(emptyList<String>(), queue.release())
        assertFalse(queue.offer(DeepLinkKind.DIRECT, "after release"))
    }

    @Test
    fun `only attributed results carrying a link count as deferred deep links`() {
        assertTrue(LinkFlowSDK.AttributionResult(attributed = true, deepLinkValue = "app://x").carriesDeepLink())
        assertTrue(LinkFlowSDK.AttributionResult(attributed = true, deepLinkParams = mapOf("p" to 1)).carriesDeepLink())
        assertFalse(LinkFlowSDK.AttributionResult(attributed = true).carriesDeepLink())
        assertFalse(LinkFlowSDK.AttributionResult(attributed = false, deepLinkValue = "app://x").carriesDeepLink())
    }
}

/** 2.2.0: organic installs and match info in the resolve response. */
class ResolveResponseTest {

    @Test
    fun `organic install id and token are read when the server returns them`() {
        val parsed = ParsedResolveResponse.parse(JSONObject(
            """{"attributed":false,"installId":"i-1","installToken":"t-1","match":{"method":"organic","confidence":0}}"""
        ))
        assertFalse(parsed.attributed)
        assertEquals("i-1", parsed.installId)
        assertEquals("t-1", parsed.installToken)
        assertEquals(AttributionMatch("organic", 0.0), parsed.match)
        assertEquals(ResolveResultCategory.ORGANIC, parsed.category)
    }

    @Test
    fun `pre 2_2 organic response has no install id and no match`() {
        val parsed = ParsedResolveResponse.parse(JSONObject(
            """{"attributed":false,"message":"No matching click found"}"""
        ))
        assertNull(parsed.installId)
        assertNull(parsed.installToken)
        assertNull(parsed.match)
    }

    @Test
    fun `attributed response keeps every 2_1 field and adds match`() {
        val parsed = ParsedResolveResponse.parse(JSONObject(
            """{"attributed":true,"installId":"i","installToken":"t","attributionMethod":"click_token",
               "confidence":1.0,"isReinstall":true,"deepLinkValue":"app://p/1",
               "deepLinkParams":{"sku":"A"},"campaignData":{"utmSource":"x"},
               "match":{"method":"click_token","confidence":1.0}}"""
        ))
        assertTrue(parsed.attributed)
        assertEquals("click_token", parsed.attributionMethod)
        assertEquals(1.0, parsed.confidence!!, 0.0)
        assertTrue(parsed.isReinstall)
        assertEquals("app://p/1", parsed.deepLinkValue)
        assertEquals("A", parsed.deepLinkParams["sku"])
        assertEquals("x", parsed.campaignData["utmSource"])
        assertEquals("click_token", parsed.match?.method)
        assertEquals(ResolveResultCategory.ATTRIBUTED, parsed.category)
    }

    @Test
    fun `deep link params sent as a JSON string are decoded`() {
        // The server stores params as a string column and may return either shape.
        val parsed = ParsedResolveResponse.parse(JSONObject(
            """{"attributed":true,"deepLinkParams":"{\"screen\":\"promo\"}"}"""
        ))
        assertEquals("promo", parsed.deepLinkParams["screen"])
    }

    @Test
    fun `malformed match and params are ignored rather than thrown`() {
        val parsed = ParsedResolveResponse.parse(JSONObject(
            """{"attributed":true,"match":{"confidence":0.5},"deepLinkParams":"not json","confidence":null}"""
        ))
        assertNull(parsed.match)
        assertTrue(parsed.deepLinkParams.isEmpty())
        assertNull(parsed.confidence)
    }

    @Test
    fun `match confidence is optional`() {
        assertEquals(AttributionMatch("probabilistic", null),
            ParsedResolveResponse.parseMatch(JSONObject("""{"method":"probabilistic"}""")))
    }
}

/** 2.2.0: diagnostics never carry identifiers. */
class LinkFlowDiagnosticsTest {

    private fun sample() = LinkFlowDiagnostics(
        sdkVersion = LinkFlowSDK.SDK_VERSION,
        apiHost = LinkFlowDiagnostics.hostOf("https://user:pw@go.example.com:8443/path?q=1"),
        appKeyConfigured = true,
        consentState = "granted",
        advertisingIdConsent = false,
        launchPendingConsent = false,
        hasInstallId = true,
        hasInstallToken = true,
        customerUserIdSet = true,
        attributionComplete = true,
        lastResolveAtMillis = 0L,
        lastResolveResult = ResolveResultCategory.ORGANIC,
        lastMatchMethod = "organic",
        queuedEventCount = 2,
        deepLinksHeld = true,
        heldDeepLinkCount = 1,
        attributionCallbackSet = true,
    )

    @Test
    fun `sdk version is 2_2_0`() {
        assertEquals("2.2.0", LinkFlowSDK.SDK_VERSION)
    }

    @Test
    fun `api host is reduced to the host name`() {
        assertEquals("go.example.com", sample().apiHost)
        assertEquals("thelinkflow.app", LinkFlowDiagnostics.hostOf(LinkFlowConfig.DEFAULT_API_BASE_URL))
        assertNull(LinkFlowDiagnostics.hostOf("not a url"))
    }

    @Test
    fun `map exposes only flags, counts and categories`() {
        val map = sample().toMap()
        assertEquals("1970-01-01T00:00:00.000Z", map["lastResolveAt"])
        assertEquals("organic", map["lastResolveResult"])
        assertEquals(2, map["queuedEventCount"])
        for (forbidden in listOf("installId", "installToken", "appKey", "customerUserId", "advertisingId", "clickToken")) {
            assertFalse("diagnostics must not expose $forbidden", map.containsKey(forbidden))
        }
        map.values.filterIsInstance<String>().forEach {
            assertFalse("no URL in diagnostics: $it", it.contains("://"))
        }
    }

    @Test
    fun `consent state reflects policy and decision`() {
        assertEquals("pending", LinkFlowDiagnostics.consentState(true, null))
        assertEquals("granted", LinkFlowDiagnostics.consentState(true, LinkFlowConsent(true)))
        assertEquals("denied", LinkFlowDiagnostics.consentState(true, LinkFlowConsent(false)))
        assertEquals("not_required", LinkFlowDiagnostics.consentState(false, LinkFlowConsent(true)))
        assertEquals("denied", LinkFlowDiagnostics.consentState(false, LinkFlowConsent(false)))
    }

    @Test
    fun `result categories round trip by wire name`() {
        ResolveResultCategory.values().forEach {
            assertEquals(it, ResolveResultCategory.fromWireName(it.wireName))
        }
        assertNull(ResolveResultCategory.fromWireName(null))
        assertNull(ResolveResultCategory.fromWireName("unknown"))
    }
}
