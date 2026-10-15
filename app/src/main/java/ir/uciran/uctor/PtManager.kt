package ir.uciran.uctor

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Thin wrapper around IPtProxy (Lyrebird: obfs4/meek_lite/webtunnel, Snowflake, ...).
 * Uses reflection so small API changes between IPtProxy releases do not break the build.
 */
class PtManager(stateDir: File) {
    private val cls: Class<*> = Class.forName("IPtProxy.Controller")
    private val controller: Any
    private val started = mutableListOf<String>()

    init {
        stateDir.mkdirs()
        val ctor = cls.constructors.maxByOrNull { it.parameterTypes.size }
            ?: throw IllegalStateException("IPtProxy Controller not found")
        var s = 0
        var b = 0
        val args = ctor.parameterTypes.map { t ->
            when (t) {
                String::class.java -> if (s++ == 0) stateDir.absolutePath else "INFO"
                java.lang.Boolean.TYPE -> (b++ == 0)
                java.lang.Long.TYPE -> 0L
                Integer.TYPE -> 0
                else -> null
            }
        }.toTypedArray()
        controller = unwrap { ctor.newInstance(*args) }
    }

    /** Passes snowflake broker / fronts / ICE from the bridge line to IPtProxy (when supported). */
    fun configureSnowflake(line: String) {
        val kv = Planner.normalize(line).split(' ').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        kv["url"]?.let { set("setSnowflakeBrokerUrl", it) }
        (kv["fronts"] ?: kv["front"])?.let { set("setSnowflakeFrontDomains", it) }
        kv["ice"]?.let { set("setSnowflakeIceServers", it) }
        kv["ampcache"]?.let { set("setSnowflakeAmpCacheUrl", it) }
    }

    private fun set(name: String, v: String) {
        runCatching { cls.getMethod(name, String::class.java).invoke(controller, v) }
    }

    /** Starts a transport and returns its local SOCKS port. */
    fun start(transport: String): Int {
        val aliases = if (transport == "meek_lite") listOf("meek_lite", "meek") else listOf(transport)
        var last: Throwable? = null
        for (name in aliases) {
            try {
                val m2 = cls.methods.firstOrNull { it.name == "start" && it.parameterTypes.size == 2 }
                if (m2 != null) unwrap { m2.invoke(controller, name, "") }
                else unwrap { cls.methods.first { it.name == "start" && it.parameterTypes.size == 1 }.invoke(controller, name) }
                val portM = cls.methods.first { it.name == "port" && it.parameterTypes.size == 1 }
                val port = (unwrap { portM.invoke(controller, name) } as Number).toInt()
                if (port <= 0) throw IllegalStateException("$transport در این نسخه پشتیبانی نمی‌شود")
                started += name
                waitListening(port)
                return port
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("$transport start failed")
    }

    fun stopAll() {
        val m = cls.methods.firstOrNull { it.name == "stop" && it.parameterTypes.size == 1 } ?: return
        started.forEach { runCatching { m.invoke(controller, it) } }
        started.clear()
    }

    private fun waitListening(port: Int) {
        val until = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < until) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
    }

    private inline fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}
