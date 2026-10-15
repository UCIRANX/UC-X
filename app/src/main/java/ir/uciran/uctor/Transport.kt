package ir.uciran.uctor

enum class Transport(val id: String, val label: String, val desc: String) {
    AUTO("auto", "خودکار (پیشنهادی)", "به‌ترتیب webtunnel، snowflake، obfs4 و meek را امتحان می‌کند"),
    WEBTUNNEL("webtunnel", "webtunnel", "شبیه ترافیک معمولی HTTPS؛ بهترین گزینه برای ایران"),
    SNOWFLAKE("snowflake", "snowflake", "از طریق پروکسی‌های داوطلب (WebRTC)؛ بریج لازم ندارد"),
    OBFS4("obfs4", "obfs4", "ترافیک را شبیه داده‌ی تصادفی نشان می‌دهد"),
    MEEK("meek_lite", "meek_lite", "از طریق CDN؛ کندتر ولی مقاوم"),
    VANILLA("vanilla", "vanilla", "بریج ساده بدون مبهم‌سازی"),
    DIRECT("direct", "بدون بریج (مستقیم)", "در ایران معمولاً مسدود است");

    companion object {
        fun fromId(id: String?): Transport = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Which transports this build can actually run (checked without loading native code). */
object PtSupport {
    private val lyrebird = setOf("obfs4", "meek_lite", "webtunnel")

    private val hasIpt: Boolean by lazy {
        runCatching { Class.forName("IPtProxy.Controller", false, PtSupport::class.java.classLoader); true }
            .getOrDefault(false)
    }

    private val names: List<String> by lazy {
        val out = mutableListOf<String>()
        for (cn in listOf("IPtProxy.IPtProxy", "IPtProxy.Controller")) {
            runCatching {
                val c = Class.forName(cn, false, PtSupport::class.java.classLoader)
                c.declaredFields.forEach { out += it.name.lowercase() }
                c.declaredMethods.forEach { out += it.name.lowercase() }
            }
        }
        out
    }

    fun available(t: String): Boolean = when (t) {
        "auto", "direct", "vanilla" -> true
        in lyrebird, "snowflake" -> hasIpt
        else -> false
    }
}
