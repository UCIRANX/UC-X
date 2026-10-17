package com.multivpn.app.awg

import android.util.Base64
import java.net.URLDecoder

/** تجزیه و یکسان‌سازی کانفیگ AmneziaWG و ساخت تنظیمات UAPI برای هسته. */
object AwgConfig {

    class Parsed(
        val iface: Map<String, String>,
        val peer: Map<String, String>,
        val allowed: List<String>,
        val endpointHost: String,
        val endpointPort: Int,
        val addresses: List<Pair<String, Int>>,
        val dns: List<String>,
        val mtu: Int,
    )

    private val NAMES = mapOf(
        "privatekey" to "PrivateKey", "address" to "Address", "dns" to "DNS", "mtu" to "MTU",
        "jc" to "Jc", "jmin" to "Jmin", "jmax" to "Jmax",
        "s1" to "S1", "s2" to "S2", "s3" to "S3", "s4" to "S4",
        "h1" to "H1", "h2" to "H2", "h3" to "H3", "h4" to "H4",
        "i1" to "I1", "i2" to "I2", "i3" to "I3", "i4" to "I4", "i5" to "I5",
        "publickey" to "PublicKey", "presharedkey" to "PresharedKey", "allowedips" to "AllowedIPs",
        "endpoint" to "Endpoint", "persistentkeepalive" to "PersistentKeepalive",
    )
    private val IFACE_ORDER = listOf(
        "privatekey", "address", "dns", "mtu", "jc", "jmin", "jmax",
        "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
    )

    private fun dec(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun decodeB64(s: String): String? = try {
        String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        try {
            String(Base64.decode(s.trim(), Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** هر فرمت ورودی (awg://base64، awg:// با پارامترهای URL، wireguard://، base64 خام) را به متن .conf تبدیل می‌کند. */
    fun toConf(raw: String, depth: Int = 0): String? {
        if (depth > 3) return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.contains("[Interface]", ignoreCase = true)) return t
        val lower = t.lowercase()
        if (lower.startsWith("awg://") || lower.startsWith("wg://") || lower.startsWith("wireguard://")) {
            val body = t.substringAfter("://").substringBefore("#")
            return if (body.contains("@") || body.contains("?")) {
                fromUrl(t)
            } else {
                toConf(decodeB64(body) ?: return null, depth + 1)
            }
        }
        return toConf(decodeB64(t) ?: return null, depth + 1)
    }

    /** فرم URL: کلید خصوصی در بخش کاربر، سرور و پارامترها در query. پارامتر reserved نادیده گرفته می‌شود. */
    private fun fromUrl(t: String): String? {
        val main = t.substringAfter("://").substringBefore("#")
        val authority = main.substringBefore("?").substringBefore("/")
        val query = if (main.contains("?")) main.substringAfter("?") else ""
        val userinfo = if (authority.contains("@")) authority.substringBeforeLast("@") else ""
        val hostPort = authority.substringAfterLast("@")
        val q = HashMap<String, String>()
        for (pair in query.split("&")) {
            if (!pair.contains("=")) continue
            q[dec(pair.substringBefore("=")).lowercase()] = dec(pair.substringAfter("="))
        }
        val privateKey = dec(userinfo)
        val publicKey = q["publickey"] ?: return null
        if (privateKey.isEmpty() || hostPort.isEmpty()) return null
        val sb = StringBuilder("[Interface]\n")
        sb.append("PrivateKey = ").append(privateKey).append('\n')
        q["address"]?.let { sb.append("Address = ").append(it).append('\n') }
        q["dns"]?.let { sb.append("DNS = ").append(it).append('\n') }
        q["mtu"]?.let { sb.append("MTU = ").append(it).append('\n') }
        for (k in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")) {
            q[k]?.let { sb.append(NAMES.getValue(k)).append(" = ").append(it).append('\n') }
        }
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(publicKey).append('\n')
        q["presharedkey"]?.takeIf { it.isNotBlank() }?.let { sb.append("PresharedKey = ").append(it).append('\n') }
        sb.append("AllowedIPs = ").append(q["allowedips"] ?: "0.0.0.0/0, ::/0").append('\n')
        sb.append("Endpoint = ").append(hostPort).append('\n')
        q["keepalive"]?.let { sb.append("PersistentKeepalive = ").append(it).append('\n') }
        return sb.toString()
    }

    fun parse(conf: String): Parsed? {
        val iface = HashMap<String, String>()
        val peer = HashMap<String, String>()
        val allowed = mutableListOf<String>()
        var section = ""
        for (raw in conf.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.equals("[Interface]", ignoreCase = true)) {
                section = "i"
                continue
            }
            if (line.equals("[Peer]", ignoreCase = true)) {
                section = "p"
                continue
            }
            if (!line.contains("=")) continue
            val key = line.substringBefore("=").trim().lowercase()
            val value = line.substringAfter("=").trim()
            when (section) {
                "i" -> {
                    iface[key] = value
                }
                "p" -> {
                    if (key == "allowedips") {
                        allowed.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    } else {
                        peer[key] = value
                    }
                }
            }
        }
        if (iface["privatekey"] == null || peer["publickey"] == null) return null
        val (host, port) = splitEndpoint(peer["endpoint"] ?: return null) ?: return null
        val addresses = (iface["address"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { a ->
            val ip = a.substringBefore("/")
            val prefix = a.substringAfter("/", if (ip.contains(":")) "128" else "32").toIntOrNull() ?: 32
            ip to prefix
        }
        val dns = (iface["dns"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val mtu = iface["mtu"]?.toIntOrNull() ?: 1280
        return Parsed(iface, peer, allowed, host, port, addresses, dns, mtu)
    }

    /** متن استاندارد و یکتا: ترتیب ثابت فیلدها، تا کانفیگ‌های یکسان یک نتیجه بدهند. */
    fun canonicalConf(raw: String): String? {
        val conf = toConf(raw) ?: return null
        val p = parse(conf) ?: return null
        val sb = StringBuilder("[Interface]\n")
        for (k in IFACE_ORDER) {
            p.iface[k]?.takeIf { it.isNotBlank() }?.let { sb.append(NAMES.getValue(k)).append(" = ").append(it).append('\n') }
        }
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(p.peer.getValue("publickey")).append('\n')
        p.peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { sb.append("PresharedKey = ").append(it).append('\n') }
        if (p.allowed.isNotEmpty()) sb.append("AllowedIPs = ").append(p.allowed.joinToString(", ")).append('\n')
        sb.append("Endpoint = ").append(p.peer.getValue("endpoint")).append('\n')
        p.peer["persistentkeepalive"]?.takeIf { it.isNotBlank() }?.let { sb.append("PersistentKeepalive = ").append(it).append('\n') }
        return sb.toString()
    }

    /** لینک داخلی استاندارد: awg://<base64 از متن .conf استاندارد>. */
    fun encodeLink(conf: String): String =
        "awg://" + Base64.encodeToString(conf.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** برای سرویس: از هر فرمت ورودی متن .conf را می‌گیرد. */
    fun decodeConf(raw: String): String? = toConf(raw)

    private fun splitEndpoint(e: String): Pair<String, Int>? {
        return if (e.startsWith("[")) {
            val host = e.substringBefore("]").removePrefix("[")
            val port = e.substringAfter("]:", "").toIntOrNull()
            if (port != null && host.isNotEmpty()) host to port else null
        } else {
            val host = e.substringBeforeLast(":")
            val port = e.substringAfterLast(":").toIntOrNull()
            if (port != null && host.isNotEmpty()) host to port else null
        }
    }

    private fun hex(b64: String): String =
        Base64.decode(b64, Base64.DEFAULT).joinToString("") { "%02x".format(it) }

    /** تنظیمات UAPI؛ آدرس endpoint باید IP باشد، پس قبلاً از بیرون resolve شده است. */
    fun toUapi(p: Parsed, endpointIp: String): String {
        val sb = StringBuilder()
        sb.append("set=1\n")
        sb.append("private_key=").append(hex(p.iface.getValue("privatekey"))).append('\n')
        for (k in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")) {
            val v = p.iface[k] ?: continue
            sb.append(k).append('=').append(v).append('\n')
        }
        sb.append("public_key=").append(hex(p.peer.getValue("publickey"))).append('\n')
        p.peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let {
            sb.append("preshared_key=").append(hex(it)).append('\n')
        }
        val ep = if (endpointIp.contains(":")) "[$endpointIp]" else endpointIp
        sb.append("endpoint=").append(ep).append(':').append(p.endpointPort).append('\n')
        p.peer["persistentkeepalive"]?.toIntOrNull()?.let {
            sb.append("persistent_keepalive_interval=").append(it).append('\n')
        }
        for (a in p.allowed) sb.append("allowed_ip=").append(a).append('\n')
        return sb.toString()
    }
}
