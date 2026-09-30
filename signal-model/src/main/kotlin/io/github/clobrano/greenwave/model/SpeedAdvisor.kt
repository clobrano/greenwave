package io.github.clobrano.greenwave.model

/** Consiglio di velocità verso il prossimo semaforo. Velocità in m/s, tempi in secondi. */
sealed interface SpeedAdvice {
    /** Arrivando a una velocità in [minSpeed, maxSpeed] si trova verde; [targetSpeed] è la più vicina a quella attuale. */
    data class Go(
        val targetSpeed: Double,
        val minSpeed: Double,
        val maxSpeed: Double,
        val window: TimeWindow,
    ) : SpeedAdvice

    /** Nessuna velocità ammessa arriva col verde: rallentare dolcemente. */
    data class RedUnavoidable(
        /** Inizio del primo verde raggiungibile. */
        val nextGreenStart: Double,
        /** Velocità (anche sotto il minimo) per arrivare proprio all'inizio di quel verde. */
        val speedToCatchIt: Double,
    ) : SpeedAdvice

    /** Nessun piano affidabile per questo semaforo. */
    data object NoPrediction : SpeedAdvice
}

/**
 * GLOSA (Green Light Optimal Speed Advisory): quale velocità tenere per arrivare
 * al prossimo semaforo durante una finestra di verde.
 *
 * L'arrivo al tempo `now + d / v` deve cadere in una finestra [a, b] ridotta di un margine
 * di sicurezza ai due bordi, quindi `v ∈ [d / (b - margin - now), d / (a + margin - now)]`,
 * intersecato con [velocità minima, limite]. Non si consiglia mai di superare il limite.
 */
class SpeedAdvisor(private val config: Config = Config()) {

    data class Config(
        /** Sotto questa velocità si intralcia il traffico (default 25 km/h). */
        val minSpeed: Double = 25.0 / 3.6,
        /** Margine (s) dai bordi della finestra di verde. */
        val safetyMargin: Double = 2.0,
        /** Quanto avanti (s) cercare finestre di verde. */
        val horizon: Double = 300.0,
    )

    fun advise(
        plan: SignalPlan?,
        distance: Double,
        now: Double,
        currentSpeed: Double,
        speedLimit: Double,
    ): SpeedAdvice {
        if (plan == null || plan.green <= 2 * config.safetyMargin) return SpeedAdvice.NoPrediction
        val vMax = speedLimit
        val vMin = minOf(config.minSpeed, vMax)
        val d = maxOf(distance, 0.0)

        for (window in plan.greenWindows(now, now + config.horizon)) {
            // Una finestra già iniziata non richiede margine all'inizio.
            val earliest = if (window.start > now) window.start + config.safetyMargin else now
            val latest = window.end - config.safetyMargin
            if (latest <= earliest || latest <= now) continue

            val lo = maxOf(d / (latest - now), vMin)
            val hi = if (earliest <= now) vMax else minOf(d / (earliest - now), vMax)
            if (lo <= hi) {
                return SpeedAdvice.Go(currentSpeed.coerceIn(lo, hi), lo, hi, window)
            }
        }

        val earliestArrival = now + d / vMax
        val nextGreen = plan.nextGreenStart(earliestArrival)
        val catchTime = nextGreen + config.safetyMargin - now
        return SpeedAdvice.RedUnavoidable(nextGreen, if (catchTime > 0) d / catchTime else vMin)
    }
}
