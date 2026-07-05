package com.bquelhas.steer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.random.Random

/**
 * Debug helper that cycles through every [Direction] in random order, sending a
 * random instruction (street + distance + ETA) to the watch on a fixed interval
 * until [stop] is called. Used to eyeball all PDC maneuver icons + appear
 * animation without driving a real route.
 */
object DebugCycler {
    private const val TAG = "NavMe/Debug"
    private const val INTERVAL_MS = 2500L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val streets = listOf(
        "Rua da Paz", "Avenida da Liberdade", "Rua do Comércio", "Praça do Município",
        "Estrada Nacional 1", "Rua Augusta", "Avenida da República", "Rua das Flores",
        "Largo do Carmo", "Rua Direita", "Avenida do Mar", "Rua Nova",
    )

    fun isRunning(): Boolean = running

    fun start(context: Context) {
        if (running) return
        if (!NavPrefs.isDebugTests(context.applicationContext)) {
            Log.i(TAG, "debug tests OFF — not starting cycler")
            return
        }
        running = true
        Log.i(TAG, "debug cycle started")
        val app = context.applicationContext
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                PebbleEmitter.sendNav(app, randomNav())
                handler.postDelayed(this, INTERVAL_MS)
            }
        })
    }

    fun stop(context: Context) {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        PebbleEmitter.sendCancel(context.applicationContext)
        Log.i(TAG, "debug cycle stopped")
    }

    private fun randomNav(): NaviData {
        val dir = Direction.entries.random()
        val meters = Random.nextInt(1, 40) * 50          // 50 m .. 1950 m
        val distance = if (meters >= 1000) {
            "%.1f km".format(meters / 1000.0)
        } else {
            "$meters m"
        }
        val street = streets.random()
        val text = "$distance — ${prettyName(dir)} • $street"
        val etaMin = Random.nextInt(0, 60)
        val etaHour = Random.nextInt(0, 24)
        val eta = "%02d:%02d".format(etaHour, etaMin)
        return NaviData(dir, text, gpsAccuracy = "Good", eta = eta)
    }

    private fun prettyName(dir: Direction): String =
        dir.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
