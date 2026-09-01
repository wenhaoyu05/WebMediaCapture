package com.webmediacapture.network

import android.content.Context
import android.net.ConnectivityManager
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

object HttpClientProvider {
    const val DOWNLOAD_PARALLEL = 16
    const val DOWNLOAD_BUFFER = 256 * 1024

    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        app = context.applicationContext
    }

    val client: OkHttpClient by lazy { build(http1Only = false) }

    /** Dedicated download client: HTTP/1.1 so parallel ranges/segments open real TCP connections. */
    val downloadClient: OkHttpClient by lazy { build(http1Only = true) }

    private fun build(http1Only: Boolean): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = DOWNLOAD_PARALLEL
        }
        val builder = OkHttpClient.Builder()
            .dns(androidDns())
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(DOWNLOAD_PARALLEL, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
        if (http1Only) builder.protocols(listOf(Protocol.HTTP_1_1))
        androidSocketFactory()?.let { builder.socketFactory(it) }
        return builder.build()
    }

    private fun androidDns(): Dns {
        val context = app
        if (context == null) return IPV4_FIRST
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val network = context.getSystemService(ConnectivityManager::class.java).activeNetwork
                val resolved = try {
                    network?.getAllByName(hostname)?.toList().orEmpty()
                } catch (_: Throwable) {
                    emptyList()
                }.ifEmpty { Dns.SYSTEM.lookup(hostname) }
                return preferIpv4(resolved)
            }
        }
    }

    private fun androidSocketFactory(): SocketFactory? =
        app?.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.socketFactory

    internal val IPV4_FIRST = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = preferIpv4(Dns.SYSTEM.lookup(hostname))
    }

    internal fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> {
        val v4 = addresses.filterIsInstance<Inet4Address>()
        return v4.ifEmpty { addresses }
    }
}
