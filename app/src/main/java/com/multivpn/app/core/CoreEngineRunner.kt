package com.multivpn.app.core

import android.content.Context
import com.multivpn.app.sub.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

object CoreEngineRunner {
    @Volatile
    private var coreProcess: Process? = null

    @Volatile
    var lastErrorLog: String = ""

    private val dohResolveClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun startCore(context: Context, config: VpnConfig): Boolean = withContext(Dispatchers.IO) {
        stopCore()
        lastErrorLog = ""

        try {
            val resolvedIp = resolveHostToIp(config.host.removeSurrounding("[", "]"))
            val nativeDir = context.applicationInfo.nativeLibraryDir

            val isHysteria2 = config.protocol.equals("hy2", ignoreCase = true) ||
                config.rawConfig.startsWith("hy2://", ignoreCase = true) ||
                config.rawConfig.startsWith("hysteria2://", ignoreCase = true)

            val pb = if (isHysteria2) {
                val hyBinary = File(nativeDir, "libhysteria.so")
                if (!hyBinary.exists()) {
                    lastErrorLog = "فایل هسته libhysteria.so یافت نشد!"
                    return@withContext false
                }
                hyBinary.setExecutable(true)
                val configFile = File(context.filesDir, "active_hy2.json")
                configFile.writeText(CoreConfigGenerator.buildHysteria2Json(config, resolvedIp), Charsets.UTF_8)
                ProcessBuilder(hyBinary.absolutePath, "client", "-c", configFile.absolutePath)
            } else {
                val xrayBinary = File(nativeDir, "libxray.so")
                if (!xrayBinary.exists()) {
                    lastErrorLog = "فایل هسته libxray.so یافت نشد!"
                    return@withContext false
                }
                xrayBinary.setExecutable(true)
                val configFile = File(context.filesDir, "active_xray.json")
                configFile.writeText(CoreConfigGenerator.buildXrayJson(config, resolvedIp), Charsets.UTF_8)
                ProcessBuilder(xrayBinary.absolutePath, "run", "-c", configFile.absolutePath).apply {
                    environment()["XRAY_LOCATION_ASSET"] = nativeDir
                }
            }

            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            coreProcess = pb.start()

            repeat(15) {
                delay(200L)
                if (isLocalPortListening(CoreConfigGenerator.LOCAL_SOCKS_PORT)) {
                    return@withContext true
                }
                val exitVal = try { coreProcess?.exitValue() } catch (_: Exception) { null }
                if (exitVal != null) break
            }

            val exitVal = try { coreProcess?.exitValue() } catch (_: Exception) { null }
            if (exitVal != null) {
                val out = coreProcess?.inputStream?.bufferedReader()?.readText()?.take(220) ?: ""
                lastErrorLog = "خطای هسته: $out"
                return@withContext false
            }
            true
        } catch (e: Exception) {
            lastErrorLog = "خطا در آماده‌سازی کانفیگ: ${e.message}"
            false
        }
    }

    private fun resolveHostToIp(host: String): String {
        if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) || host.contains(":")) return host
        try {
            val ip = InetAddress.getByName(host).hostAddress
            if (!ip.isNullOrBlank() && !ip.startsWith("10.") && !ip.startsWith("127.")) return ip
        } catch (_: Exception) {}
        try {
            val req = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$host&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohResolveClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return host
                val answers = JSONObject(body).optJSONArray("Answer") ?: return host
                for (i in 0 until answers.length()) {
                    val item = answers.getJSONObject(i)
                    if (item.optInt("type") == 1) {
                        val data = item.optString("data", "")
                        if (data.isNotBlank()) return data
                    }
                }
            }
        } catch (_: Exception) {}
        return host
    }

    fun stopCore() {
        try {
            coreProcess?.destroy()
            coreProcess?.destroyForcibly()
        } catch (_: Exception) {
        } finally {
            coreProcess = null
        }
    }

    fun isCoreRunning(): Boolean {
        val proc = coreProcess ?: return false
        return try {
            proc.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    private fun isLocalPortListening(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 250)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun measureRealTunnelDelay(): Long = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", CoreConfigGenerator.LOCAL_HTTP_PORT))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(7, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .build()
            val start = System.currentTimeMillis()
            val req = Request.Builder().url("http://cp.cloudflare.com/generate_204").build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 204 || resp.isSuccessful) {
                    val elapsed = System.currentTimeMillis() - start
                    return@withContext if (elapsed <= 0L) 1L else elapsed
                }
            }
            -1L
        } catch (e: Exception) {
            -1L
        }
    }
}
