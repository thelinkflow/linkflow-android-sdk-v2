package com.linkflow.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow
import kotlin.random.Random

/** Outcome of an HTTP call. */
internal sealed class HttpOutcome {
    data class Success(val body: String) : HttpOutcome()

    /** 4xx other than 408/429 — the request will never succeed, so do not retry. */
    data class PermanentFailure(val code: Int, val body: String?) : HttpOutcome()

    /** Network error, timeout, 5xx, 408 or 429 — worth retrying. */
    data class TransientFailure(val code: Int?, val error: Throwable?) : HttpOutcome()
}

/**
 * Minimal HTTP transport with exponential backoff and jitter.
 *
 * The previous implementation fired a single request and dropped the result on
 * any failure, so a user who opened the app on a flaky connection lost their
 * install attribution permanently. Retrying matters most for exactly that call.
 */
internal class LinkFlowHttp(
    private val config: LinkFlowConfig,
    private val log: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit,
) {

    /** Sends a request, retrying transient failures with backoff. */
    suspend fun send(url: String, method: String, body: String?, appKey: String? = null): HttpOutcome {
        var lastTransient: HttpOutcome.TransientFailure? = null

        for (attempt in 0 until maxOf(1, config.maxRetries)) {
            if (attempt > 0) {
                // Full jitter: base * 2^n, randomised across the whole interval so a
                // fleet of devices recovering from an outage does not synchronise.
                val ceiling = config.retryBaseDelayMs * 2.0.pow(attempt - 1).toLong()
                val backoff = Random.nextLong(config.retryBaseDelayMs, ceiling + config.retryBaseDelayMs)
                log("Retrying request (attempt ${attempt + 1}) after ${backoff}ms")
                delay(backoff)
            }

            when (val outcome = attempt(url, method, body, appKey)) {
                is HttpOutcome.Success -> return outcome
                is HttpOutcome.PermanentFailure -> return outcome
                is HttpOutcome.TransientFailure -> lastTransient = outcome
            }
        }

        return lastTransient ?: HttpOutcome.TransientFailure(null, null)
    }

    private suspend fun attempt(
        url: String,
        method: String,
        body: String?,
        appKey: String?,
    ): HttpOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!appKey.isNullOrBlank()) {
                    setRequestProperty("X-LinkFlow-App-Key", appKey)
                }
                connectTimeout = config.timeoutMs
                readTimeout = config.timeoutMs
                instanceFollowRedirects = false
            }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode

            if (code in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext HttpOutcome.Success(text)
            }

            val errorBody = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()

            // 408 and 429 are explicitly retryable; other 4xx will never succeed.
            return@withContext if (code == 408 || code == 429 || code >= 500) {
                HttpOutcome.TransientFailure(code, null)
            } else {
                HttpOutcome.PermanentFailure(code, errorBody)
            }
        } catch (e: IOException) {
            HttpOutcome.TransientFailure(null, e)
        } catch (e: Exception) {
            logError("Unexpected error during HTTP request", e)
            HttpOutcome.TransientFailure(null, e)
        } finally {
            connection?.disconnect()
        }
    }
}
