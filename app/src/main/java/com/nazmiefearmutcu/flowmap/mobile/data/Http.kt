package com.nazmiefearmutcu.flowmap.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP failure with the status code kept, so callers can spot region blocks (451/403). */
class HttpError(val code: Int, val url: String, message: String) : IOException("HTTP $code: $message")

val HttpError.isRegionBlock: Boolean get() = code == 451 || code == 403

object Http {
    const val USER_AGENT = "FlowMapMobile/1.0 (Android; market-data reader)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).get().build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!cont.isActive) return
                        if (!it.isSuccessful) {
                            cont.resumeWithException(HttpError(it.code, url, body.take(200)))
                        } else {
                            cont.resume(body)
                        }
                    }
                }
            })
        }
    }
}

/** Human-readable reason for a failed request, used in error banners. */
fun Throwable.userMessage(): String = when (this) {
    is HttpError -> when {
        isRegionBlock -> "This venue refuses connections from your region (HTTP $code)."
        code == 429 || code == 418 -> "Rate limited by the venue. Try again in a minute."
        code >= 500 -> "The venue is having problems (HTTP $code)."
        else -> "Request failed (HTTP $code)."
    }
    is java.net.UnknownHostException -> "No internet connection."
    is java.net.SocketTimeoutException -> "The venue did not answer in time."
    is IOException -> "Network error: ${message ?: "connection lost"}"
    else -> message ?: javaClass.simpleName
}
