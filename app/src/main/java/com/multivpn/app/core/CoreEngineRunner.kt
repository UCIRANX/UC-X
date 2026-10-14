package com.multivpn.app.core

import android.content.Context
import com.multivpn.app.sub.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

object CoreEngineRunner {
    @Volatile
    private var coreProcess: Process? = null

    @Volatile
    var lastErrorLog: String = ""

    suspend fun startCore(context: Context, config: VpnConfig): Boolean = withContext(Dispatchers.IO) {
        stopCore()
        lastErrorLog = ""

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val xrayBinary = File(nativeDir, "libxray.so")

        if (!xrayBinary.exists()) {
            lastErrorLog = "فایل هسته libxray.so یافت نشد!"
            return@withContext false
        }

        xrayBinary.setExecutable(true)

        val configFile = File(context.filesDir, "active_config.json")
        val jsonContent = CoreConfigGenerator.buildXrayJson(config)
        configFile.writeText(jsonContent, Charsets.UTF_8)

        try {
            val pb = ProcessBuilder(
                xrayBinary.absolutePath,
                "run",
                "-c",
                configFile.absolutePath
            )
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            coreProcess = pb.start()

            // صبر کوتاه برای بالا آمدن پورت‌های لوکال 10808 و 10809
            repeat(12) {
                delay(200L)
                if (isLocalPortListening(CoreConfigGenerator.LOCAL_SOCKS_PORT)) {
                    return@withContext true
                }
            }

            val exitVal = try { coreProcess?.exitValue() } catch (_: Exception) { null }
            if (exitVal != null) {
                val out = coreProcess?.inputStream?.bufferedReader()?.readText()?.take(200) ?: ""
                lastErrorLog = "هسته متوقف شد: $out"
                return@withContext false
            }
            true
        } catch (e: Exception) {
            lastErrorLog = "خطا در اجرای هسته: ${e.message}"
            false
        }
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

    // تست واقعی اینترنت از داخل پروکسی هسته (پورت 10809)
    suspend fun measureRealTunnelDelay(): Long = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", CoreConfigGenerator.LOCAL_HTTP_PORT))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()

            val start = System.currentTimeMillis()
            val req = Request.Builder()
                .url("http://cp.cloudflare.com/generate_204")
                .build()

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
