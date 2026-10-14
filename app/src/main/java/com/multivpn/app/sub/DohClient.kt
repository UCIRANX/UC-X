package com.multivpn.app.sub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object DohClient {
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val dohDns = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
            InetAddress.getByName("8.8.8.8")
        )
        .build()

    private val dohHttpClient = bootstrapClient.newBuilder()
        .dns(dohDns)
        .build()

    suspend fun fetchUrlText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "MultiCoreVPN/1.5 (Android; Sing-box; ClashMeta)")
            .build()

        try {
            dohHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("پاسخ خالی از سرور")
            }
        } catch (e: Exception) {
            bootstrapClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("پاسخ خالی از سرور")
            }
        }
    }
}
