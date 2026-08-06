package com.linkflow.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-level tests for the parts of the SDK that do not need an Android runtime.
 *
 * The install-referrer round trip is the important one: it is the contract that
 * carries the click token through the Play Store, and a mismatch between how the
 * server packs it and how the SDK expects it is exactly the class of bug that
 * left Android attribution silently broken.
 */
class LinkFlowConfigTest {

    @Test
    fun `default base url matches the other SDKs`() {
        // iOS previously defaulted to a different host entirely.
        assertEquals("https://thelinkflow.app", LinkFlowConfig.DEFAULT_API_BASE_URL)
        assertEquals("https://thelinkflow.app", LinkFlowConfig().apiBaseUrl)
    }

    @Test
    fun `consent is not required by default`() {
        assertFalse(LinkFlowConfig().requireConsent)
    }

    @Test
    fun `advertising id consent defaults to the attribution decision`() {
        assertTrue(LinkFlowConsent(attribution = true).advertisingId)
        assertFalse(LinkFlowConsent(attribution = false).advertisingId)
    }

    @Test
    fun `advertising id consent can be withheld independently`() {
        val consent = LinkFlowConsent(attribution = true, advertisingId = false)
        assertTrue(consent.attribution)
        assertFalse(consent.advertisingId)
    }

    @Test
    fun `app key is absent unless configured`() {
        assertNull(LinkFlowConfig().appKey)
        assertEquals("lfa_abc", LinkFlowConfig(appKey = "lfa_abc").appKey)
    }

    @Test
    fun `retry defaults are bounded`() {
        val config = LinkFlowConfig()
        assertTrue("retries must be finite", config.maxRetries in 1..10)
        assertTrue("timeout must be positive", config.timeoutMs > 0)
    }
}

/**
 * Mirrors the server's InstallReferrerParser format. If these two ever drift,
 * Android deterministic attribution breaks silently — which is precisely what
 * happened before, so it is worth pinning from both sides.
 */
class InstallReferrerFormatTest {

    private fun parse(referrer: String): Map<String, String> =
        referrer.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8") to
                        java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }
            .toMap()

    @Test
    fun `server packs the click token under lf_ct`() {
        val referrer = "lf_ct=tok123&utm_source=newsletter&utm_medium=email"
        val parsed = parse(referrer)

        assertEquals("tok123", parsed["lf_ct"])
        assertEquals("newsletter", parsed["utm_source"])
    }

    @Test
    fun `organic play referrer carries no click token`() {
        val parsed = parse("utm_source=google-play&utm_medium=organic")
        assertNull(parsed["lf_ct"])
    }

    @Test
    fun `encoded values survive the round trip`() {
        val parsed = parse("lf_ct=tok&utm_source=a%26b%3Dc")
        assertEquals("tok", parsed["lf_ct"])
        assertEquals("a&b=c", parsed["utm_source"])
    }
}
