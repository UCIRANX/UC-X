package com.multivpn.app.core

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Tun2SocksBridge {
    private const val CLS = "hev.htproxy.TProxyService"

    @Volatile var lastError: String = ""
    @Volatile private var started = false
    private var cls: Class<*>? = null
    private var inst: Any? = null

    private fun load(): Class<*>? {
        cls?.let { return it }
        return try {
            Class.forName(CLS).also { cls = it }
        } catch (t: Throwable) {
            lastError = "کتابخانه‌ی tun2socks بارگذاری نشد (${t.javaClass.simpleName}: ${t.message})"
            null
        }
    }

    fun describe(): String {
        val c = load() ?: return lastError
        return c.declaredMethods.joinToString("; ") { m ->
            (if (Modifier.isStatic(m.modifiers)) "static " else "") +
                m.returnType.simpleName + " " + m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")"
        }
    }

    private fun find(name: String): Method? {
        val c = load() ?: return null
        return (c.declaredMethods.asList() + c.methods.asList()).firstOrNull { it.name == name }
            ?.also { it.isAccessible = true }
    }

    private fun call(m: Method, vararg args: Any?): Any? {
        val c = load()!!
        val target: Any? = if (Modifier.isStatic(m.modifiers)) null else {
            inst ?: c.getDeclaredConstructor().also { it.isAccessible = true }.newInstance().also { inst = it }
        }
        return try {
            m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    @Synchronized
    fun start(config: File, fd: Int): Boolean {
        val m = find("TProxyStartService") ?: run {
            if (cls != null) lastError = "متد TProxyStartService پیدا نشد. متدهای موجود: ${describe()}"
            return false
        }
        return try {
            val r = call(m, config.absolutePath, fd)
            started = true
            if (r is Boolean && !r) {
                lastError = "TProxyStartService مقدار false برگرداند"
                started = false
                false
            } else true
        } catch (t: Throwable) {
            lastError = "اجرای TProxyStartService ناموفق بود (${t.javaClass.simpleName}: ${t.message})"
            started = false
            false
        }
    }

    fun isRunning(): Boolean {
        if (!started) return false
        val m = find("TProxyIsRunning") ?: return true
        return runCatching { call(m) as? Boolean }.getOrNull() ?: true
    }

    fun stats(): LongArray {
        if (!started) return longArrayOf()
        val m = find("TProxyGetStats") ?: return longArrayOf()
        return runCatching { call(m) as? LongArray }.getOrNull() ?: longArrayOf()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        find("TProxyStopService")?.let { runCatching { call(it) } }
        started = false
    }
}
