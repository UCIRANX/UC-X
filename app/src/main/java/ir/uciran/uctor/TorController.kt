package ir.uciran.uctor

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.withContext

enum class Phase { IDLE, CONNECTING, CONNECTED, STOPPING, ERROR }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val progress: Int = 0,
    val status: String = "آماده‌ی اتصال",
    val detail: String = "",
    val activeTransport: String? = null,
    val socksPort: Int = 0,
    val httpPort: Int = 0,
    val log: List<String> = emptyList(),
    val bridgeSummary: String = "",
    val vpnReady: Boolean = false,
    val vpnError: String = "",
    val bridgeCounts: List<Pair<String, Int>> = emptyList(),
    val bridgeUpdated: String = "",
    val bridgeBusy: Boolean = false,
    val bridgeFailed: Boolean = false,
)

/** Runs in the main process: plans attempts, starts/stops the :tor process and watches tor's log. */
object TorController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private lateinit var app: Context
    private val bootRe = Regex("Bootstrapped (\\d+)%")

    /** اگر اتصال به‌خاطر نبودن خروجی در کشور انتخابی شکست بخورد، دلیلش اینجا می‌آید. */
    @Volatile private var countryFailure: String? = null

    /** مسیر فایل‌های GeoIP نصب‌شده برای اتصال فعلی (null = داخل این نسخه‌ی برنامه نیست). */
    @Volatile private var geoip: GeoIp.Paths? = null

    fun logFile(ctx: Context) = File(ctx.filesDir, "tor.log")
    fun stateFile(ctx: Context) = File(ctx.filesDir, "runner.state")

    fun setVpnStatus(ready: Boolean, error: String = "") {
        _state.update { it.copy(vpnReady = ready, vpnError = error) }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        scope.launch {
            refreshBridgeSummary()
            val p = Prefs(app)
            if (p.socksPort > 0 && isListening(p.socksPort) && readLog().first >= 100) {
                _state.update {
                    it.copy(
                        phase = Phase.CONNECTED, progress = 100, status = "متصل",
                        detail = p.activeTransport?.let { t -> "از طریق ${Planner.label(t)}" } ?: "",
                        activeTransport = p.activeTransport, socksPort = p.socksPort, httpPort = p.httpPort,
                        vpnReady = p.vpnRunning,
                    )
                }
                if (job?.isActive != true) job = scope.launch { monitor() }
            }
        }
    }

    fun connect() {
        if (job?.isActive == true) return
        // فوراً وارد حالت «در حال اتصال» می‌شویم تا سرویس VPN بداند باید منتظر Tor بماند.
        _state.update { it.copy(phase = Phase.CONNECTING, progress = 0, status = "در حال اتصال…", detail = "", vpnReady = false, vpnError = "") }
        // سرویس VPN همین حالا (وقتی برنامه جلوی چشم کاربر است) به‌صورت foreground شروع می‌شود؛
        // اگر کاربر وسط اتصال برنامه را ببندد، سرویس خودش بعد از آماده شدن Tor VPN را بالا می‌آورد.
        if (android.net.VpnService.prepare(app) == null) runCatching { FullVpnService.start(app) }
        job = scope.launch {
            try {
                runConnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stopRunner()
                _state.update {
                    it.copy(phase = Phase.ERROR, progress = 0, status = "خطا", detail = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun disconnect() {
        runCatching { FullVpnService.stop(app) }
        val j = job
        job = null
        scope.launch {
            _state.update { it.copy(phase = Phase.STOPPING, status = "در حال قطع اتصال…") }
            j?.cancelAndJoin()
            stopRunner()
            Prefs(app).activeTransport = null
            _state.update { it.copy(phase = Phase.IDLE, progress = 0, status = "قطع شد", detail = "", activeTransport = null, vpnReady = false, vpnError = "") }
        }
    }

    fun requestBridgeUpdate() {
        if (state.value.bridgeBusy) return
        scope.launch {
            _state.update { it.copy(bridgeBusy = true, bridgeFailed = false) }
            val ok = try {
                if (state.value.phase == Phase.CONNECTED) {
                    BridgeRepository.refreshViaTor(app, Prefs(app).httpPort, force = true) || BridgeRepository.refreshDirect(app)
                } else {
                    BridgeRepository.refreshDirect(app)
                }
            } catch (e: Exception) { false }
            refreshBridgeSummary()
            _state.update { it.copy(bridgeBusy = false, bridgeFailed = !ok) }
        }
    }

    private fun refreshBridgeSummary() {
        val b = BridgeRepository.load(app)
        val order = listOf("webtunnel", "obfs4", "snowflake", "meek_lite", "vanilla")
        _state.update { it.copy(bridgeSummary = b.summary(), bridgeCounts = order.map { t -> t to b.get(t).size }, bridgeUpdated = b.updated) }
    }

    private suspend fun runConnect() {
        val prefs = Prefs(app)
        countryFailure = null
        ExitCountries.find(prefs.exitCountry)?.let { c ->
            val tag = "{${c.code}}"
            val excluded = listOf(prefs.excludeNodes, prefs.excludeExitNodes).any { it.contains(tag, ignoreCase = true) }
            if (excluded) {
                _state.update {
                    it.copy(
                        phase = Phase.ERROR, progress = 0, status = "تنظیمات با هم تناقض دارند",
                        detail = "کشور خروجی ${c.name} انتخاب شده ولی در تنظیمات پیشرفته (Exclude) هم جزو کشورهای حذف‌شده است. یکی از این دو را تغییر دهید.",
                    )
                }
                return
            }
        }
        stopRunner()
        val bridges = BridgeRepository.load(app)
        val plan = Planner.plan(prefs, bridges)
        if (plan.isEmpty()) {
            _state.update {
                it.copy(
                    phase = Phase.ERROR, progress = 0, status = "بریجی پیدا نشد",
                    detail = "برای این روش بریجی موجود نیست. بریج‌ها را به‌روزرسانی کنید یا بریج دستی وارد کنید.",
                )
            }
            return
        }
        for ((i, a) in plan.withIndex()) {
            currentCoroutineContext().ensureActive()
            _state.update {
                it.copy(
                    phase = Phase.CONNECTING, progress = 0, status = "در حال اتصال…",
                    detail = "روش ${a.label} (${i + 1} از ${plan.size})", activeTransport = a.transport,
                )
            }
            if (attempt(prefs, a)) {
                prefs.lastGood = a.transport
                prefs.activeTransport = a.transport
                val exit = ExitCountries.find(prefs.exitCountry)?.let { c -> " · خروجی: ${c.flag} ${c.name}" }.orEmpty()
                _state.update {
                    it.copy(phase = Phase.CONNECTED, progress = 100, status = "متصل", detail = "از طریق ${a.label}$exit")
                }
                scope.launch {
                    BridgeRepository.refreshViaTor(app, prefs.httpPort)
                    refreshBridgeSummary()
                }
                monitor()
                return
            }
            stopRunner()
            // شکست به‌خاطر کشور است، نه بریج؛ امتحان روش‌های دیگر فقط وقت را تلف می‌کند.
            countryFailure?.let { why ->
                val c = ExitCountries.find(prefs.exitCountry)
                _state.update {
                    it.copy(
                        phase = Phase.ERROR, progress = 0,
                        status = "خروجی ${c?.name ?: "کشور انتخابی"} در دسترس نیست",
                        detail = why, activeTransport = null,
                    )
                }
                return
            }
        }
        _state.update {
            it.copy(
                phase = Phase.ERROR, progress = 0, status = "اتصال برقرار نشد",
                detail = "همه‌ی روش‌ها امتحان شدند. اینترنت را بررسی کنید، بریج‌ها را به‌روزرسانی کنید یا بریج دستی وارد کنید.",
                activeTransport = null,
            )
        }
    }

    private suspend fun attempt(prefs: Prefs, a: Planner.Attempt): Boolean {
        val socks = freePort(listOf(9050, 9052, 9150, 19050, 29050))
        val http = freePort(listOf(8118, 8119, 18118, 28118).filter { it != socks })
        prefs.socksPort = socks
        prefs.httpPort = http
        _state.update { it.copy(socksPort = socks, httpPort = http) }

        logFile(app).delete()
        File(logFile(app).path + ".circ").delete()
        stateFile(app).delete()
        // فایل GeoIP برای انتخاب کشور لازم است؛ همیشه نصب می‌شود تا Tor کد کشورها را بشناسد.
        val geoipPaths = withContext(Dispatchers.IO) { GeoIp.install(app) }
        geoip = geoipPaths
        val cfg = JSONObject().apply {
            put("socks", socks)
            put("http", http)
            put("log", logFile(app).absolutePath)
            put("bridges", JSONArray(a.lines))
            put("excludeNodes", prefs.excludeNodes)
            put("excludeExitNodes", prefs.excludeExitNodes)
            put("exitNodes", prefs.exitNodes)
            put("exitCountry", prefs.exitCountry)
            geoipPaths?.let { g ->
                put("geoip", g.v4.absolutePath)
                g.v6?.let { put("geoip6", it.absolutePath) }
            }
        }
        try {
            TorRunnerService.start(app, cfg.toString())
        } catch (e: Exception) {
            _state.update { it.copy(detail = "اجرای سرویس ممکن نشد؛ برنامه را باز نگه دارید و دوباره امتحان کنید.") }
            return false
        }

        val start = System.currentTimeMillis()
        var best = -1
        var lastChange = start
        val stallMs = if (a.transport == "snowflake" || a.transport == "meek_lite") 100_000L else 50_000L
        val maxMs = 240_000L
        while (true) {
            delay(1000)
            val st = stateFile(app).takeIf { it.exists() }?.readText()?.trim().orEmpty()
            if (st.startsWith("error:")) {
                _state.update { it.copy(detail = "${a.label}: " + st.removePrefix("error:")) }
                return false
            }
            val (pct, tail) = readLog()
            val now = System.currentTimeMillis()
            if (pct > best) {
                best = pct
                lastChange = now
            }
            _state.update {
                it.copy(
                    progress = pct.coerceAtLeast(0), log = tail,
                    status = if (pct >= 0) "در حال اتصال… $pct٪" else "در حال راه‌اندازی…",
                )
            }
            if (pct >= 100) {
                val country = ExitCountries.find(prefs.exitCountry)
                var probe = withContext(Dispatchers.IO) { SocksProbe.httpsThroughSocks(prefs.socksPort) }
                // ساختن مدار با خروجی یک کشور مشخص (مخصوصاً از راه بریج) کندتر است و Tor باید چند مدار را امتحان کند.
                // تا ۲.۵ دقیقه دوباره امتحان می‌کنیم و فقط بعد از آن شکست را به کشور نسبت می‌دهیم.
                if (!probe.ok && country != null) {
                    val until = System.currentTimeMillis() + 150_000L
                    while (!probe.ok && System.currentTimeMillis() < until) {
                        currentCoroutineContext().ensureActive()
                        _state.update {
                            it.copy(status = "در حال ساخت مدار با خروجی ${country.name}…", detail = "${a.label} · این مرحله ممکن است تا چند دقیقه طول بکشد")
                        }
                        probe = withContext(Dispatchers.IO) { SocksProbe.httpsThroughSocks(prefs.socksPort) }
                    }
                }
                if (probe.ok) return true
                if (country != null) {
                    countryFailure = countryReason(country, probe.message)
                    return false
                }
                _state.update { it.copy(detail = "${a.label}: SOCKS برقرار است اما درخواست واقعی HTTPS پاسخ نداد (${probe.message})") }
                return false
            }
            if (now - lastChange > stallMs || now - start > maxMs) return false
        }
    }

    private suspend fun monitor() {
        val p = Prefs(app)
        while (true) {
            delay(3000)
            if (!isListening(p.socksPort)) {
                // Tor مرده است؛ VPN را هم ببند تا ترافیک گوشی در تونل بی‌مقصد گیر نکند.
                runCatching { FullVpnService.stop(app) }
                p.activeTransport = null
                _state.update { it.copy(phase = Phase.IDLE, progress = 0, status = "اتصال قطع شد", detail = "", activeTransport = null, vpnReady = false, vpnError = "") }
                return
            }
            val (_, tail) = readLog()
            _state.update { it.copy(log = tail) }
        }
    }

    private suspend fun stopRunner() {
        val st = stateFile(app).takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val p = Prefs(app)
        val running = st == "starting" || st == "running" || (p.socksPort > 0 && isListening(p.socksPort))
        if (!running) return
        TorRunnerService.stop(app)
        val until = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < until && p.socksPort > 0 && isListening(p.socksPort)) delay(300)
        delay(700)
    }

    private fun readText(f: File, maxBytes: Long = 400_000L): String = try {
        if (!f.exists()) "" else RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val from = maxOf(0L, len - maxBytes)
            r.seek(from)
            val buf = ByteArray((len - from).toInt())
            r.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }

    /** دلیل قابل‌فهم برای وقتی که Tor وصل شده ولی مداری با خروجی کشور انتخابی ساخته نمی‌شود. */
    private fun countryReason(c: ExitCountry, probeMsg: String): String {
        val main = readText(logFile(app)).lowercase()
        val circ = readText(File(logFile(app).path + ".circ")).lowercase()
        val bad = "unable|could not|couldn't|cannot|can't|no such file|failed|not found"
        val geoipProblem = Regex("geoip[^\\n]*($bad)|($bad)[^\\n]*geoip").containsMatchIn(main)
        val why = when {
            geoip == null ->
                "این نسخه‌ی برنامه فایل GeoIP ندارد (دانلودش هنگام بیلد ناموفق بوده)؛ بدون آن Tor معمولاً کد کشور را نمی‌شناسد و مداری با خروجی کشور انتخابی نمی‌سازد."
            geoipProblem ->
                "Tor فایل GeoIP را نخواند و نمی‌تواند خروجی‌ها را بر اساس کشور انتخاب کند."
            "failed to find node for hop" in circ || "strictnodes" in circ ->
                "در این لحظه هیچ خروجی (exit) فعال و در دسترسی در ${c.name} پیدا نشد. چون فقط ${c.name} انتخاب شده، Tor سراغ کشور دیگری نمی‌رود."
            else ->
                "Tor به شبکه وصل شد اما در چند دقیقه مداری با خروجی ${c.name} نساخت ($probeMsg). معمولاً یعنی خروجی‌های این کشور فعلاً کم، شلوغ یا آفلاین‌اند."
        }
        return why + "\nکشور دیگری را امتحان کنید یا روی «خودکار» بگذارید."
    }

    /** Returns bootstrap percent (-1 if unknown) and the last log lines. */
    private fun readLog(): Pair<Int, List<String>> {
        val f = logFile(app)
        if (!f.exists()) return Pair(-1, emptyList())
        val text = try {
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val from = maxOf(0L, len - 1_000_000L)
                r.seek(from)
                val buf = ByteArray((len - from).toInt())
                r.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return Pair(-1, emptyList())
        }
        val pct = bootRe.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val lines = text.lines().filter { it.isNotBlank() }.takeLast(40) + vpnLogTail()
        return Pair(pct, lines)
    }

    /** آخرین خط‌های لاگ VPN و tun2socks، برای نمایش در کارت لاگ. */
    private fun vpnLogTail(): List<String> {
        fun tail(f: File, tag: String): List<String> = runCatching {
            if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(15).map { if (tag.isEmpty()) it else "[$tag] $it" } else emptyList()
        }.getOrDefault(emptyList())
        return tail(FullVpnService.vpnLogFile(app), "") + tail(FullVpnService.hevLogFile(app), "hev")
    }

    private fun isListening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (e: Exception) {
        false
    }

    private fun freePort(candidates: List<Int>): Int {
        for (c in candidates) {
            try {
                ServerSocket(c, 1, InetAddress.getByName("127.0.0.1")).close()
                return c
            } catch (_: Exception) {
            }
        }
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }
}
