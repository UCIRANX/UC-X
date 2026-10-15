package ir.uciran.uctor

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object SocksProbe {
    data class Result(val ok: Boolean, val message: String)

    /** A real HTTPS request through Tor's SOCKS5 proxy. Never falls back to direct. */
    fun httpsThroughSocks(port: Int): Result {
        if (port <= 0) return Result(false, "پورت SOCKS نامعتبر است")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val targets = listOf("https://check.torproject.org/", "https://example.com/")
        var last = "timeout"
        for (target in targets) {
            var c: HttpURLConnection? = null
            try {
                c = URL(target).openConnection(proxy) as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "UCTor-connectivity-check")
                val code = c.responseCode
                if (code in 200..399) {
                    c.inputStream.use { it.readNBytes(512) }
                    return Result(true, "HTTPS $code")
                }
                last = "HTTP $code"
            } catch (t: Throwable) {
                last = t.message ?: t.javaClass.simpleName
            } finally { c?.disconnect() }
        }
        return Result(false, last)
    }
}
