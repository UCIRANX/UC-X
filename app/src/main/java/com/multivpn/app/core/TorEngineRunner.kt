package com.multivpn.app.core

import android.content.Context
import com.multivpn.app.sub.DohClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class TorBridgeOption(val id: String, val label: String)
data class TorCountryOption(val code: String, val name: String, val flag: String)

object TorEngineRunner {
    const val TOR_SOCKS_PORT = 9050
    const val TOR_HTTP_PORT = 8118

    val bridgeOptions = listOf(
        TorBridgeOption("auto", "🔄 خودکار (تست هوشمند همه پل‌ها)"),
        TorBridgeOption("obfs4", "🛡️ پل Obfs4 (پیشنهادی)"),
        TorBridgeOption("webtunnel", "🌐 پل WebTunnel (عبور از فایروال شدید)"),
        TorBridgeOption("snowflake", "❄️ پل Snowflake"),
        TorBridgeOption("meek_lite", "☁️ پل Meek Azure"),
        TorBridgeOption("vanilla", "⚡ مستقیم / Vanilla Bridge")
    )

    val countryOptions = listOf(
        TorCountryOption("auto", "خودکار (سریع‌ترین خروجی)", "🌍"),
        TorCountryOption("de", "آلمان", "🇩🇪"),
        TorCountryOption("nl", "هلند", "🇳🇱"),
        TorCountryOption("us", "آمریکا", "🇺🇸"),
        TorCountryOption("gb", "انگلیس", "🇬🇧"),
        TorCountryOption("fr", "فرانسه", "🇫🇷"),
        TorCountryOption("ch", "سوئیس", "🇨🇭"),
        TorCountryOption("se", "سوئد", "🇸🇪"),
        TorCountryOption("fi", "فنلاند", "🇫🇮"),
        TorCountryOption("at", "اتریش", "🇦🇹"),
        TorCountryOption("ca", "کانادا", "🇨🇦")
    )

    @Volatile private var torProcess: Process? = null
    @Volatile var lastError: String = ""
    private val bootRegex = Regex("Bootstrapped (\\d+)%")

    private val defaultBridges = mapOf(
        "obfs4" to listOf(
            "obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=qUVQ0srL1JI/vO6V6m/24anYXiJD3QP2HgzUKQtQ7GRqqUvs7P+tG43+Aqdh6ALP7DJQ2g iat-mode=1",
            "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
            "obfs4 85.31.186.98:443 011F2599C0E9B27EE74B353155E244813763C3E5 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=0",
            "obfs4 193.11.166.194:27015 2D82C2E354D531A68469ADF7F878FA6060C6BACA cert=4TLQPJrTSaDffMK7Nbao6LC7G9OW/NHkUwIdjLSS3KYf0Nv4/nQiiI8dY2TcsQx01NniOg iat-mode=0"
        ),
        "snowflake" to listOf(
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.l.google.com:19302,stun:stun.antisip.com:3478 utls-imitate=hellorandomizedalpn"
        ),
        "meek_lite" to listOf(
            "meek_lite 192.0.2.18:80 BE776A53492E1E044A26F17306E1BC46A55A1625 url=https://meek.azureedge.net/ front=ajax.aspnetcdn.com"
        )
    )

    private var cachedOnlineBridges = mutableMapOf<String, List<String>>()

    suspend fun fetchFreshBridges(): Int = withContext(Dispatchers.IO) {
        val urls = mapOf(
            "obfs4" to "https://raw.githubusercontent.com/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/obfs4_tested.txt",
            "webtunnel" to "https://raw.githubusercontent.com/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/webtunnel_tested.txt",
            "vanilla" to "https://raw.githubusercontent.com/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/vanilla_tested.txt"
        )
        var total = 0
        for ((type, url) in urls) {
            try {
                val text = DohClient.fetchUrlText(url)
                val lines = text.lines().map { it.trim() }.filter { it.length > 20 && !it.startsWith("#") }.take(40)
                if (lines.isNotEmpty()) {
                    cachedOnlineBridges[type] = lines
                    total += lines.size
                }
            } catch (_: Exception) {}
        }
        total
    }

    private fun startPluggableTransports(context: Context): Map<String, Long> {
        val ports = mutableMapOf<String, Long>()
        try {
            val cls = Class.forName("IPtProxy.IPtProxy")
            val stateDir = File(context.filesDir, "pt_state").apply { mkdirs() }
            cls.methods.firstOrNull { it.name.equals("setStateLocation", true) }?.invoke(null, stateDir.absolutePath)

            for (m in cls.methods) {
                val n = m.name.lowercase()
                try {
                    if (n == "startlyrebird" && m.parameterCount == 4) {
                        m.invoke(null, "WARN", false, false, null)
                    } else if (n == "startsnowflake" && m.parameterCount >= 7) {
                        val args = arrayOfNulls<Any>(m.parameterCount)
                        args[0] = "stun:stun.l.google.com:19302,stun:stun.antisip.com:3478"
                        args[1] = "https://1098762253.rsc.cdn77.org/"
                        args[2] = "www.cdn77.com,www.phpmyadmin.net"
                        args[3] = null
                        args[4] = null
                        args[5] = null
                        args[6] = false
                        if (m.parameterCount > 7) args[7] = false
                        if (m.parameterCount > 8) args[8] = false
                        if (m.parameterCount > 9) args[9] = 1
                        m.invoke(null, *args)
                    }
                } catch (_: Throwable) {}
            }

            fun getPort(methodName: String): Long {
                return (cls.methods.firstOrNull { it.name.equals(methodName, true) }?.invoke(null) as? Number)?.toLong() ?: 0L
            }

            val obfs4P = getPort("obfs4Port").takeIf { it > 0 } ?: getPort("lyrebirdPort")
            val webtunnelP = getPort("webtunnelPort")
            val meekP = getPort("meekPort")
            val snowflakeP = getPort("snowflakePort")

            if (obfs4P > 0) ports["obfs4"] = obfs4P
            if (webtunnelP > 0) ports["webtunnel"] = webtunnelP
            if (meekP > 0) ports["meek_lite"] = meekP
            if (snowflakeP > 0) ports["snowflake"] = snowflakeP
        } catch (_: Throwable) {}
        return ports
    }

    private fun installGeoIpAssets(context: Context): Pair<File?, File?> {
        val dir = File(context.filesDir, "tor_geoip").apply { mkdirs() }
        fun copyAsset(name: String): File? {
            return try {
                val out = File(dir, name)
                if (!out.exists() || out.length() < 50_000L) {
                    context.assets.open("geoip/$name").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
                if (out.exists() && out.length() > 50_000L) out else null
            } catch (_: Exception) { null }
        }
        return copyAsset("geoip") to copyAsset("geoip6")
    }

    suspend fun startTor(
        context: Context,
        bridgeType: String,
        countryCode: String,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        stopTor()
        lastError = ""

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val torBin = File(nativeDir, "libtor.so")
        if (!torBin.exists()) {
            lastError = "فایل هسته libtor.so یافت نشد!"
            return@withContext false
        }
        torBin.setExecutable(true)

        onStatus("در حال آماده‌سازی پل‌های Tor...")
        if (cachedOnlineBridges.isEmpty()) {
            fetchFreshBridges()
        }

        val ptPorts = startPluggableTransports(context)
        val (geoipV4, geoipV6) = installGeoIpAssets(context)

        val transportsToTry = if (bridgeType == "auto") {
            listOf("obfs4", "webtunnel", "snowflake", "meek_lite", "vanilla")
        } else {
            listOf(bridgeType)
        }

        for ((idx, transport) in transportsToTry.withIndex()) {
            stopProcessOnly()
            val dataDir = File(context.filesDir, "tor_data").apply { mkdirs() }
            val logFile = File(context.filesDir, "tor.log").apply { delete() }
            val torrcFile = File(context.filesDir, "torrc")

            val lines = (cachedOnlineBridges[transport].orEmpty() + defaultBridges[transport].orEmpty()).distinct().take(25)
            if (transport != "vanilla" && lines.isEmpty()) continue

            val sb = StringBuilder().apply {
                appendLine("DataDirectory ${dataDir.absolutePath}")
                appendLine("SocksPort 127.0.0.1:$TOR_SOCKS_PORT")
                appendLine("HTTPTunnelPort 127.0.0.1:$TOR_HTTP_PORT")
                appendLine("Log notice file ${logFile.absolutePath}")
                appendLine("ClientOnly 1")
                appendLine("AvoidDiskWrites 1")
                geoipV4?.let { appendLine("GeoIPFile ${it.absolutePath}") }
                geoipV6?.let { appendLine("GeoIPv6File ${it.absolutePath}") }

                if (countryCode != "auto" && countryCode.isNotBlank()) {
                    appendLine("ExitNodes {$countryCode}")
                    appendLine("StrictNodes 1")
                }

                if (lines.isNotEmpty()) {
                    appendLine("UseBridges 1")
                    ptPorts[transport]?.let { port ->
                        appendLine("ClientTransportPlugin $transport socks5 127.0.0.1:$port")
                    }
                    lines.forEach { b ->
                        val clean = if (b.startsWith("Bridge ", true)) b else "Bridge $b"
                        appendLine(clean)
                    }
                }
            }
            torrcFile.writeText(sb.toString(), Charsets.UTF_8)

            onStatus("در حال اتصال Tor با روش $transport (${idx + 1}/${transportsToTry.size})...")
            val pb = ProcessBuilder(torBin.absolutePath, "-f", torrcFile.absolutePath).apply {
                directory(context.filesDir)
                redirectErrorStream(true)
            }
            torProcess = pb.start()

            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < 75_000L) {
                delay(1000L)
                val exitVal = try { torProcess?.exitValue() } catch (_: Exception) { null }
                if (exitVal != null) break

                val pct = readBootstrapPercent(logFile)
                if (pct >= 0) {
                    onStatus("در حال اتصال Tor ($transport): $pct٪")
                }
                if (pct >= 100) {
                    onStatus("تست اتصال واقعی از مدار Tor...")
                    if (probeSocksConnection(TOR_SOCKS_PORT)) {
                        return@withContext true
                    }
                }
            }
        }

        stopTor()
        lastError = "اتصال Tor برقرار نشد؛ پل یا کشور دیگری را امتحان کنید."
        false
    }

    private fun readBootstrapPercent(logFile: File): Int {
        if (!logFile.exists()) return -1
        return try {
            RandomAccessFile(logFile, "r").use { r ->
                val len = r.length()
                val from = maxOf(0L, len - 64_000L)
                r.seek(from)
                val buf = ByteArray((len - from).toInt())
                r.readFully(buf)
                val text = String(buf, Charsets.UTF_8)
                bootRegex.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun probeSocksConnection(socksPort: Int): Boolean {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn = URL("https://cp.cloudflare.com/generate_204").openConnection(proxy) as HttpsURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) { false }
    }

    fun isTorRunning(): Boolean {
        val p = torProcess ?: return false
        return try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }

    private fun stopProcessOnly() {
        try {
            torProcess?.destroy()
            torProcess?.destroyForcibly()
        } catch (_: Exception) {} finally {
            torProcess = null
        }
    }

    fun stopTor() {
        stopProcessOnly()
        try {
            val cls = Class.forName("IPtProxy.IPtProxy")
            cls.methods.filter { it.name.startsWith("stop", true) && it.parameterCount == 0 }
                .forEach { runCatching { it.invoke(null) } }
        } catch (_: Throwable) {}
    }
}
