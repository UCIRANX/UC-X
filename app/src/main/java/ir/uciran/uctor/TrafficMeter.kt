package ir.uciran.uctor

import android.net.TrafficStats
import kotlinx.coroutines.delay

object TrafficMeter {
    data class Rate(val down: Long = 0, val up: Long = 0)
    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    suspend fun sample(): Rate {
        delay(1000)
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
        val r = Rate(((rx-lastRx).coerceAtLeast(0)), ((tx-lastTx).coerceAtLeast(0)))
        lastRx = rx; lastTx = tx; return r
    }
    fun text(n: Long): String = when { n >= 1_000_000 -> "%.1f MB/s".format(n/1_000_000.0); n >= 1_000 -> "%.0f KB/s".format(n/1_000.0); else -> "$n B/s" }
}
