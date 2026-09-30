package io.github.clobrano.greenwave.model

import kotlin.math.floor

/** Intervallo di tempo [start, end), in secondi. */
data class TimeWindow(val start: Double, val end: Double) {
    val duration: Double get() = end - start
    operator fun contains(t: Double): Boolean = t >= start && t < end
}

/**
 * Piano di un semaforo a tempo fisso.
 *
 * Il verde inizia agli istanti `offset + k * cycle` (k intero) e dura `green` secondi;
 * per il resto del ciclo il semaforo è rosso (il giallo è contato come rosso).
 *
 * Tutti i tempi sono in secondi su una scala scelta dal chiamante: l'app usa i secondi
 * dalla mezzanotte locale, così un piano imparato un giorno vale anche i giorni seguenti.
 */
data class SignalPlan(val cycle: Double, val green: Double, val offset: Double) {
    init {
        require(cycle > 0.0) { "cycle must be positive: $cycle" }
        require(green in 0.0..cycle) { "green must be in [0, cycle]: $green" }
    }

    /** Secondi trascorsi dall'ultimo inizio del verde, in [0, cycle). */
    fun phaseAt(t: Double): Double = positiveMod(t - offset, cycle)

    fun isGreen(t: Double): Boolean = phaseAt(t) < green

    /** Primo inizio del verde a partire da [t] (incluso). */
    fun nextGreenStart(t: Double): Double {
        val phase = phaseAt(t)
        return if (phase == 0.0) t else t + cycle - phase
    }

    /** Secondi mancanti al prossimo cambio di colore. */
    fun secondsToChange(t: Double): Double {
        val phase = phaseAt(t)
        return if (phase < green) green - phase else cycle - phase
    }

    /** Finestre di verde che si sovrappongono all'intervallo [from, until). */
    fun greenWindows(from: Double, until: Double): List<TimeWindow> {
        if (green <= 0.0) return emptyList()
        val windows = mutableListOf<TimeWindow>()
        var start = offset + floor((from - offset) / cycle) * cycle
        while (start < until) {
            val end = start + green
            if (end > from) windows += TimeWindow(start, end)
            start += cycle
        }
        return windows
    }
}

/** Modulo sempre positivo, in [0, m). */
internal fun positiveMod(a: Double, m: Double): Double {
    val r = a % m
    return if (r < 0.0) r + m else r
}

/** Scarto con segno rispetto al multiplo di [m] più vicino, in [-m/2, m/2). */
internal fun signedMod(a: Double, m: Double): Double = positiveMod(a + m / 2.0, m) - m / 2.0
