package com.multivpn.app.sub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object PingTester {
    private val semaphore = Semaphore(10)

    suspend fun measureTcpPing(host: String, port: Int, timeoutMs: Int = 2500): Long = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed <= 0L) 1L else elapsed
        } catch (e: Exception) {
            -2L
        }
    }

    suspend fun pingAllConfigs(configs: List<VpnConfig>): List<VpnConfig> = coroutineScope {
        val jobs = configs.map { cfg ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val latency = measureTcpPing(cfg.host, cfg.port)
                    cfg.copy(pingMs = latency)
                }
            }
        }
        val results = jobs.awaitAll()
        results.sortedWith(compareBy {
            when {
                it.pingMs > 0 -> it.pingMs
                it.pingMs == -1L -> 999998L
                else -> 999999L
            }
        })
    }
}
