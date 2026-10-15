package com.multivpn.app.sub

import android.content.Context
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

object PingTester {
    private val tcpSemaphore = Semaphore(30)

    private suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int = 1300): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host.removeSurrounding("[", "]"), port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * تست پینگ واقعی. هر سرور به محض آماده شدن نتیجه‌اش از طریق onResult اعلام می‌شود
     * (هم موفق و هم ناموفق)، تا نمایش در لحظه انجام شود.
     */
    suspend fun pingAllConfigsRealHttp(
        context: Context,
        configs: List<VpnConfig>,
        onProgress: (String) -> Unit = {},
        onResult: (VpnConfig) -> Unit = {},
    ): List<VpnConfig> = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val xrayBinary = File(nativeDir, "libxray.so")
        if (!xrayBinary.exists()) return@withContext configs
        xrayBinary.setExecutable(true)

        val realResults = ConcurrentHashMap<String, Long>()
        val emitted = ConcurrentHashMap.newKeySet<String>()
        fun emit(c: VpnConfig) {
            if (emitted.add(c.id)) onResult(c)
        }

        onProgress("بررسی سریع پورت ${configs.size} سرور…")
        val openMap = coroutineScope {
            configs.map { cfg ->
                async {
                    tcpSemaphore.withPermit {
                        cfg.id to isPortOpen(cfg.host, cfg.port)
                    }
                }
            }.awaitAll().toMap()
        }

        // پورت بسته: همان لحظه بی‌پاسخ اعلام شود
        configs.forEach { cfg ->
            if (openMap[cfg.id] != true) {
                realResults[cfg.id] = -2L
                emit(cfg.copy(pingMs = -2L))
            }
        }

        val candidates = configs.filter { openMap[it.id] == true && !it.protocol.equals("hy2", true) }
        val batches = candidates.chunked(20)
        batches.forEachIndexed { idx, batch ->
            onProgress("تست واقعی دسته ${idx + 1} از ${batches.size}…")
            val validPairs = mutableListOf<Pair<VpnConfig, String>>()
            for (cfg in batch) {
                try {
                    val ip = CoreEngineRunner.resolveHostToIp(cfg.host)
                    CoreConfigGenerator.buildMainOutbound(cfg, ip)
                    validPairs.add(cfg to ip)
                } catch (_: Exception) {
                    realResults[cfg.id] = -2L
                    emit(cfg.copy(pingMs = -2L))
                }
            }

            if (validPairs.isNotEmpty()) {
                var proc: Process? = null
                try {
                    val batchFile = File(context.filesDir, "batch_ping.json")
                    batchFile.writeText(CoreConfigGenerator.buildBatchPingXrayJson(validPairs), Charsets.UTF_8)

                    proc = ProcessBuilder(xrayBinary.absolutePath, "run", "-c", batchFile.absolutePath).apply {
                        environment()["XRAY_LOCATION_ASSET"] = nativeDir
                        directory(context.filesDir)
                        redirectErrorStream(true)
                    }.start()

                    for (w in 0 until 12) {
                        delay(150L)
                        if (CoreEngineRunner.isLocalPortListening(CoreConfigGenerator.BATCH_BASE_PORT)) break
                    }

                    coroutineScope {
                        validPairs.mapIndexed { bIdx, (cfg, _) ->
                            async {
                                val httpPort = CoreConfigGenerator.BATCH_BASE_PORT + bIdx
                                val raw = CoreEngineRunner.measureRealTunnelDelay(httpPort = httpPort, timeoutSec = 4)
                                val ping = if (raw > 0) raw else -2L
                                realResults[cfg.id] = ping
                                emit(cfg.copy(pingMs = ping))
                            }
                        }.awaitAll()
                    }
                } catch (_: Exception) {
                } finally {
                    try { proc?.destroy(); proc?.destroyForcibly() } catch (_: Exception) {}
                }
            }
        }

        // هر سروری که هنوز نتیجه نگرفته (مثلاً hy2 یا خطای داخلی) بی‌پاسخ اعلام شود
        configs.forEach { cfg -> emit(cfg.copy(pingMs = realResults[cfg.id] ?: -2L)) }

        configs.map { cfg ->
            cfg.copy(pingMs = realResults[cfg.id] ?: -2L)
        }.sortedWith(compareBy {
            when {
                it.pingMs > 0 -> it.pingMs
                it.pingMs == -1L -> 999998L
                else -> 999999L
            }
        })
    }
}
