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
import java.util.concurrent.ConcurrentLinkedQueue

object PingTester {
    private const val BATCH_SIZE = 40          // کانفیگ در هر فرایند Xray
    private const val PARALLEL = 4             // تعداد دسته‌های هم‌زمان
    private const val PORTS_PER_SLOT = 64      // بازهٔ پورت جدا برای هر اسلات (بزرگ‌تر از BATCH_SIZE)
    private val tcpSemaphore = Semaphore(60)

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
     * تست پینگ واقعی. نتیجهٔ هر سرور همان لحظه از طریق onResult اعلام می‌شود (موفق یا ناموفق).
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
        val emit: (VpnConfig) -> Unit = { c -> if (emitted.add(c.id)) onResult(c) }

        onProgress("بررسی سریع پورت ${configs.size} سرور…")
        val openMap = coroutineScope {
            configs.map { cfg ->
                async { tcpSemaphore.withPermit { cfg.id to isPortOpen(cfg.host, cfg.port) } }
            }.awaitAll().toMap()
        }

        configs.forEach { cfg ->
            if (openMap[cfg.id] != true) {
                realResults[cfg.id] = -2L
                emit(cfg.copy(pingMs = -2L))
            }
        }

        val candidates = configs.filter { openMap[it.id] == true && !it.protocol.equals("hy2", true) }
        val batches = candidates.chunked(BATCH_SIZE)
        onProgress("تست واقعی ${candidates.size} سرور…")

        val freeSlots = ConcurrentLinkedQueue<Int>((0 until PARALLEL).toList())
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            batches.map { batch ->
                async {
                    gate.withPermit {
                        val slot = freeSlots.poll() ?: 0
                        try {
                            pingBatch(context, nativeDir, xrayBinary, batch, slot, realResults, emit)
                        } finally {
                            freeSlots.add(slot)
                        }
                    }
                }
            }.awaitAll()
        }

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

    private suspend fun pingBatch(
        context: Context,
        nativeDir: String,
        xrayBinary: File,
        batch: List<VpnConfig>,
        slot: Int,
        results: ConcurrentHashMap<String, Long>,
        emit: (VpnConfig) -> Unit,
    ) {
        val portBase = CoreConfigGenerator.BATCH_BASE_PORT + slot * PORTS_PER_SLOT
        val valid = mutableListOf<Pair<VpnConfig, String>>()
        for (cfg in batch) {
            try {
                val ip = CoreEngineRunner.resolveHostToIp(cfg.host)
                CoreConfigGenerator.buildMainOutbound(cfg, ip)
                valid.add(cfg to ip)
            } catch (_: Exception) {
                results[cfg.id] = -2L
                emit(cfg.copy(pingMs = -2L))
            }
        }
        if (valid.isEmpty()) return

        var proc: Process? = null
        try {
            val file = File(context.filesDir, "batch_ping_$slot.json")
            file.writeText(CoreConfigGenerator.buildBatchPingXrayJson(valid, portBase), Charsets.UTF_8)
            proc = ProcessBuilder(xrayBinary.absolutePath, "run", "-c", file.absolutePath).apply {
                environment()["XRAY_LOCATION_ASSET"] = nativeDir
                directory(context.filesDir)
                redirectErrorStream(true)
            }.start()

            for (w in 0 until 20) {
                delay(150L)
                if (CoreEngineRunner.isLocalPortListening(portBase)) break
            }

            coroutineScope {
                valid.mapIndexed { i, pair ->
                    async {
                        val cfg = pair.first
                        val raw = CoreEngineRunner.measureRealTunnelDelay(httpPort = portBase + i, timeoutSec = 4)
                        val ping = if (raw > 0) raw else -2L
                        results[cfg.id] = ping
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
