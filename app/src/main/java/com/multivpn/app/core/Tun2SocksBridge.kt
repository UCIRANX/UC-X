package com.multivpn.app.core

import java.io.File
import java.lang.reflect.Method

object Tun2SocksBridge {
    @Volatile
    var lastError: String = ""

    @Volatile
    private var active: Boolean = false

    private var cls: Class<*>? = null
    private var startMethod: Method? = null
    private var stopMethod: Method? = null
    private var statsMethod: Method? = null

    private fun ensureLoaded() {
        if (cls != null) return
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (_: Throwable) {}

        val candidates = listOf(
            "hev.htproxy.TProxyService",
            "hev.sockstunnel.TProxyService"
        )
        for (name in candidates) {
            try {
                val c = Class.forName(name)
                cls = c
                startMethod = c.declaredMethods.firstOrNull { it.name.contains("Start", ignoreCase = true) }?.apply { isAccessible = true }
                stopMethod = c.declaredMethods.firstOrNull { it.name.contains("Stop", ignoreCase = true) }?.apply { isAccessible = true }
                statsMethod = c.declaredMethods.firstOrNull { it.name.contains("Stats", ignoreCase = true) }?.apply { isAccessible = true }
                return
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun start(configFile: File, fd: Int): Boolean {
        return try {
            ensureLoaded()
            val m = startMethod ?: error("کلاس TProxyService در فایل AAR پیدا نشد")
            val instance = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else cls?.getDeclaredConstructor()?.newInstance()
            m.invoke(instance, configFile.absolutePath, fd)
            active = true
            lastError = ""
            true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            lastError = cause.message ?: cause.javaClass.simpleName
            active = false
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            ensureLoaded()
            val m = stopMethod ?: return
            val instance = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else cls?.getDeclaredConstructor()?.newInstance()
            m.invoke(instance)
        } catch (_: Throwable) {
        } finally {
            active = false
        }
    }

    fun isRunning(): Boolean = active

    fun stats(): LongArray {
        return try {
            ensureLoaded()
            val m = statsMethod ?: return LongArray(0)
            val instance = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else cls?.getDeclaredConstructor()?.newInstance()
            (m.invoke(instance) as? LongArray) ?: LongArray(0)
        } catch (_: Throwable) {
            LongArray(0)
        }
    }
}
