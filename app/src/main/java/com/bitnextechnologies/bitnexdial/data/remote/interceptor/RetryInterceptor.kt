package com.bitnextechnologies.bitnexdial.data.remote.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp Interceptor that retries failed requests with exponential backoff
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "RetryInterceptor"
        private const val MAX_RETRIES = 3
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 10000L
        private const val BACKOFF_MULTIPLIER = 2.0

        // HTTP status codes that should trigger a retry
        private val RETRYABLE_STATUS_CODES = setOf(
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
        )
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: IOException? = null
        var retryCount = 0
        var delay = INITIAL_DELAY_MS

        while (retryCount <= MAX_RETRIES) {
            try {
                // Close previous response if exists
                response?.close()

                // Make the request
                response = chain.proceed(request)

                // Check if response is successful or non-retryable
                if (response.isSuccessful || !shouldRetry(response.code)) {
                    return response
                }

                // Log retry attempt
                Log.w(TAG, "Request failed with status ${response.code}, attempt ${retryCount + 1}/$MAX_RETRIES")

            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Request failed with exception, attempt ${retryCount + 1}/$MAX_RETRIES: ${e.message}")
            }

            retryCount++

            // Don't wait after the last attempt
            if (retryCount <= MAX_RETRIES) {
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Retry interrupted", e)
                }

                // Exponential backoff with cap
                delay = (delay * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_DELAY_MS)
            }
        }

        // If we have a response (even if failed), return it
        response?.let { return it }

        // Otherwise, throw the last exception
        throw lastException ?: IOException("Request failed after $MAX_RETRIES retries")
    }

    private fun shouldRetry(statusCode: Int): Boolean {
        return statusCode in RETRYABLE_STATUS_CODES
    }
}
