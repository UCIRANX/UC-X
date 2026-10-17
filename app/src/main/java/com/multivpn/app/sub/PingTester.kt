package com.multivpn.app.sub

import android.content.Context
import com.multivpn.app.core.CoreConfigGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * تست پینگ واقعی به روش v2rayNG (Test real delay):
 * هر سرور با یک درخواست HTTP از داخل تونل سنجیده می‌شود، بدون بررسی TCP.
 * اگر یک دسته کانفیگ بالا نیاید، دسته نصف می‌شود تا کانفیگ خراب بقیه را از بین نبرد.
 * Hysteria2 با فرایند جدا تست می‌شود.
 */
object PingTester {
    private const val XRAY_BATCH = 40
    private const val XRAY_PARALLEL = 4
    private const val PORTS_PER_SLOT = 64
    private const val HY2_PARALLEL = 6
    private const val HY2_PORT_BASE = 12500
    private const val TIMEOUT_SEC = 4L
    private const val TEST_URL = "http://www.gstatic.com/generate_204"
    private const val READY_WAIT_MS = 3000L

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SEC + 1, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** یک درخواست از طریق پروکسی؛ زمان به میلی‌ثانیه یا -1 در صورت خطا. */
    private fun probe(proxy: Proxy): Long {
        return try {
            val client = baseClient.newBuilder().proxy(proxy).build()
            val start = System.nanoTime()
            client.newCall(Request.Builder().url(TEST_URL).build()).execute().use { resp ->
                if (resp.code == 204 || resp.isSuccessful) {
                    maxOf(1L, (System.nanoTime() - start) / 1_000_000L)
                } else {
                    -1L
                }
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private suspend fun portOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitReady(proc: Process, port: Int): Boolean {
        val end = System.currentTimeMillis() + READY_WAIT_MS
        while (System.currentTimeMillis() < end) {
            if (!proc.isAlive) return false
            if (portOpen(port)) return true
            delay(100L)
        }
        return false
    }

    suspend fun pingAllConfigsRealHttp(
        context: Context,
        configs: List<VpnConfig>,
        onProgress: (String) -> Unit = {},
        onResult: (VpnConfig) -> Unit = {},
    ): List<VpnConfig> = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val xrayBinary = File(nativeDir, "libxray.so").apply { if (exists()) setExecutable(true) }
        val hyBinary = File(nativeDir, "libhysteria.so").apply { if (exists()) setExecutable(true) }

        val realResults = ConcurrentHashMap<String, Long>()
        val emitted = ConcurrentHashMap.newKeySet<String>()
        val emit: (VpnConfig) -> Unit = { c -> if (emitted.add(c.id)) onResult(c) }

        val hy2 = configs.filter { it.protocol.equals("hy2", true) }
        val xray = configs.filter { !it.protocol.equals("hy2", true) && it.coreType != "AmneziaWG" }
        onProgress("تست ${configs.size} سرور…")

        coroutineScope {
            if (xray.isNotEmpty() && xrayBinary.exists()) {
                launch { pingXray(context, nativeDir, xrayBinary, xray, realResults, emit) }
            }
            if (hy2.isNotEmpty() && hyBinary.exists()) {
                launch { pingHy2(context, nativeDir, hyBinary, hy2, realResults, emit) }
            }
        }

        configs.forEach { cfg -> emit(cfg.copy(pingMs = realResults[cfg.id] ?: (if (cfg.coreType == "AmneziaWG") -1L else -2L))) }

        configs.map { cfg ->
            cfg.copy(pingMs = realResults[cfg.id] ?: (if (cfg.coreType == "AmneziaWG") -1L else -2L))
        }.sortedWith(compareBy {
            when {
                it.pingMs > 0 -> it.pingMs
                it.pingMs == -1L -> 999998L
                else -> 999999L
            }
        })
    }

    private suspend fun pingXray(
        context: Context,
        nativeDir: String,
        xrayBinary: File,
        configs: List<VpnConfig>,
        results: ConcurrentHashMap<String, Long>,
        emit: (VpnConfig) -> Unit,
    ) {
        val batches = configs.chunked(XRAY_BATCH)
        val freeSlots = ConcurrentLinkedQueue<Int>((0 until XRAY_PARALLEL).toList())
        val gate = Semaphore(XRAY_PARALLEL)
        coroutineScope {
            batches.map { batch ->
                async {
                    gate.withPermit {
                        val slot = freeSlots.poll() ?: 0
                        try {
                            pingGroup(context, nativeDir, xrayBinary, batch, slot, results, emit)
                        } finally {
                            freeSlots.add(slot)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** یک گروه را با یک فرایند Xray می‌سنجد؛ اگر بالا نیاید، نصف می‌شود و دوباره امتحان می‌شود. */
    private suspend fun pingGroup(
        context: Context,
        nativeDir: String,
        xrayBinary: File,
        group: List<VpnConfig>,
        slot: Int,
        results: ConcurrentHashMap<String, Long>,
        emit: (VpnConfig) -> Unit,
    ) {
        if (group.isEmpty()) return
        val portBase = CoreConfigGenerator.BATCH_BASE_PORT + slot * PORTS_PER_SLOT

        val valid = mutableListOf<VpnConfig>()
        for (cfg in group) {
            val ok = try {
                CoreConfigGenerator.buildMainOutbound(cfg, cfg.host)
                true
            } catch (_: Exception) {
                false
            }
            if (ok) {
                valid.add(cfg)
            } else {
                results[cfg.id] = -2L
                emit(cfg.copy(pingMs = -2L))
            }
        }
        if (valid.isEmpty()) return

        var proc: Process? = null
        var ready = false
        try {
            val file = File(context.filesDir, "batch_ping_$slot.json")
            file.writeText(
                CoreConfigGenerator.buildBatchPingXrayJson(valid.map { it to it.host }, portBase),
                Charsets.UTF_8
            )
            proc = ProcessBuilder(xrayBinary.absolutePath, "run", "-c", file.absolutePath).apply {
                environment()["XRAY_LOCATION_ASSET"] = nativeDir
                directory(context.filesDir)
                redirectErrorStream(true)
            }.start()
            ready = waitReady(proc, portBase + valid.size - 1)

            if (ready) {
                coroutineScope {
                    valid.mapIndexed { i, cfg ->
                        async {
                            val raw = withContext(Dispatchers.IO) {
                                probe(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", portBase + i)))
                            }
                            val ping = if (raw > 0) raw else -2L
                            results[cfg.id] = ping
                            emit(cfg.copy(pingMs = ping))
                        }
                    }.awaitAll()
                }
            }
        } catch (_: Exception) {
            ready = false
        } finally {
            try { proc?.destroy(); proc?.destroyForcibly() } catch (_: Exception) {}
        }

        if (!ready) {
            if (valid.size == 1) {
                results[valid[0].id] = -2L
                emit(valid[0].copy(pingMs = -2L))
            } else {
                val mid = valid.size / 2
                pingGroup(context, nativeDir, xrayBinary, valid.subList(0, mid), slot, results, emit)
                pingGroup(context, nativeDir, xrayBinary, valid.subList(mid, valid.size), slot, results, emit)
            }
        }
    }

    private suspend fun pingHy2(
        context: Context,
        nativeDir: String,
        hyBinary: File,
        configs: List<VpnConfig>,
        results: ConcurrentHashMap<String, Long>,
        emit: (VpnConfig) -> Unit,
    ) {
        val freeSlots = ConcurrentLinkedQueue<Int>((0 until HY2_PARALLEL).toList())
        val gate = Semaphore(HY2_PARALLEL)
        coroutineScope {
            configs.map { cfg ->
                async {
                    gate.withPermit {
                        val slot = freeSlots.poll() ?: 0
                        val port = HY2_PORT_BASE + slot
                        var proc: Process? = null
                        var ping = -2L
                        try {
                            val file = File(context.filesDir, "hy2_ping_$slot.json")
                            file.writeText(CoreConfigGenerator.buildHysteria2Json(cfg, cfg.host, port), Charsets.UTF_8)
                            proc = ProcessBuilder(hyBinary.absolutePath, "client", "-c", file.absolutePath).apply {
                                environment()["XRAY_LOCATION_ASSET"] = nativeDir
                                directory(context.filesDir)
                                redirectErrorStream(true)
                            }.start()
                            if (waitReady(proc, port)) {
                                val raw = withContext(Dispatchers.IO) {
                                    probe(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                                }
                                ping = if (raw > 0) raw else -2L
                            }
                        } catch (_: Exception) {
                            ping = -2L
                        } finally {
                            try { proc?.destroy(); proc?.destroyForcibly() } catch (_: Exception) {}
                            freeSlots.add(slot)
                        }
                        results[cfg.id] = ping
                        emit(cfg.copy(pingMs = ping))
                    }
                }
            }.awaitAll()
        }
    }
}
