package ir.uciran.uctor

/** Turns the user's choice + available bridges into an ordered list of connection attempts. */
object Planner {
    data class Attempt(val transport: String, val label: String, val lines: List<String>)

    val KNOWN = setOf("obfs4", "webtunnel", "snowflake", "meek_lite", "vanilla")
    private val AUTO_ORDER = listOf("webtunnel", "snowflake", "obfs4", "meek_lite")

    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("Bridge ", ignoreCase = true)) s = s.substring(7).trim()
        if (s.startsWith("meek-azure ")) s = "meek_lite " + s.removePrefix("meek-azure ")
        if (s.startsWith("meek ")) s = "meek_lite " + s.removePrefix("meek ")
        return s.replace(Regex("\\s+"), " ")
    }

    fun typeOf(line: String): String {
        val first = normalize(line).substringBefore(' ')
        val c = first.firstOrNull() ?: return ""
        return if (c.isDigit() || c == '[') "vanilla" else first
    }

    fun parseLines(text: String): Map<String, List<String>> =
        text.lines()
            .map { normalize(it) }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { typeOf(it) in KNOWN }
            .distinct()
            .groupBy { typeOf(it) }

    private fun pick(list: List<String>, t: String): List<String> = when (t) {
        "snowflake", "meek_lite" -> list.take(4)
        else -> (list.take(6) + list.drop(6).shuffled().take(6)).distinct()
    }

    fun label(t: String) = when (t) {
        "direct" -> "اتصال مستقیم"
        "meek_lite" -> "meek"
        else -> t
    }

    fun plan(p: Prefs, b: BridgeRepository.BridgeSet): List<Attempt> {
        val custom = parseLines(p.customBridges)
        val out = mutableListOf<Attempt>()
        fun add(t: String, lines: List<String>) {
            if (lines.isNotEmpty() && PtSupport.available(t)) out += Attempt(t, label(t), lines)
        }
        when (val sel = Transport.fromId(p.transport)) {
            Transport.AUTO -> {
                custom.forEach { (t, l) -> add(t, l) }
                val order = AUTO_ORDER.toMutableList()
                val lg = p.lastGood
                if (lg != null && order.remove(lg)) order.add(0, lg)
                order.forEach { t -> add(t, pick(b.get(t), t)) }
                out += Attempt("direct", label("direct"), emptyList())
            }
            Transport.DIRECT -> out += Attempt("direct", label("direct"), emptyList())
            else -> add(sel.id, (custom[sel.id].orEmpty() + pick(b.get(sel.id), sel.id)).distinct())
        }
        return out
    }
}
