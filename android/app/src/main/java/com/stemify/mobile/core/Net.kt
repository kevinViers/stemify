package com.stemify.mobile.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

class HttpException(val code: Int, message: String) : IOException(message)

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Awaits the call and reads the body off the main thread (big bodies stream from the socket). */
suspend fun Call.awaitBody(): String = withContext(Dispatchers.IO) {
    await().use { r ->
        if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} from ${r.request.url.host}")
        r.body?.string() ?: ""
    }
}

/** Online / Wi-Fi state, for offline fallbacks and Wi-Fi-only downloads. */
class Connectivity(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(true)
    private val _unmetered = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    init {
        update(cm.getNetworkCapabilities(cm.activeNetwork))
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
            override fun onLost(network: Network) = update(null)
        })
    }

    private fun update(caps: NetworkCapabilities?) {
        _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _unmetered.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }
}
