package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException


class CloudflareKiller : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            throw IOException(e)
        }

        // We don't need to intercept successful responses
        if (response.isSuccessful) return response

        // If we get a 503, we might be able to solve the challenge
        if (response.code == 503) {
            // Closing the old response is required
            response.close()

            // Get the html and solve the challenge
            val unpacked = app.get(
                request.url.toString(),
                headers = request.headers.toMap()
            ).let { unpacked ->
                getAndUnpack(unpacked.text)
            }

            // Make a new request with the solved challenge
            val newResponse = app.get(
                request.url.toString(),
                script = unpacked,
                headers = request.headers.toMap()
            )

            // The response should be successful now, but if it isn't we will return the original response
            return if (newResponse.isSuccessful) {
                newResponse.get()
            } else {
                // You might want to log this case
                response
            }
        }

        return response
    }
}
