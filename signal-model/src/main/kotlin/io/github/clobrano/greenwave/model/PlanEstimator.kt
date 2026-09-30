package io.github.clobrano.greenwave.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class PlanStatus {
    /** Dati insufficienti o ambigui: le previsioni non vanno mostrate come affidabili. */
    LEARNING,

    /** Il piano spiega bene le osservazioni. */
    RELIABLE,

    /** Molte osservazioni ma nessun ciclo fisso le spiega: semaforo probabilmente attuato/adattivo. */
    UNPREDICTABLE,
}

data class PlanEstimate(
    /** Piano stimato, o null se i dati non bastano nemmeno per un'ipotesi. */
    val plan: SignalPlan?,
    val status: PlanStatus,
    /** Scarto quadratico medio (s) tra gli inizi verde osservati e quelli previsti. */
    val rmsError: Double,
    val greenStarts: Int,
    /** Osservazioni di colore (verde/rosso visto) incompatibili con il piano. */
    val contradictions: Int,
    /** True se la durata del verde è dedotta da osservazioni, false se è un valore di default. */
    val greenMeasured: Boolean,
    /** Altri cicli che spiegano i dati altrettanto bene: se non è vuota il ciclo è ambiguo. */
    val alternativeCycles: List<Double>,
)

/**
 * Stima ciclo, fase (offset) e durata del verde di un semaforo a tempo fisso.
 *
 * Idea: gli inizi del verde distano tra loro multipli interi del ciclo C. Per ogni C
 * candidato si calcola la fase media degli inizi verde (media circolare) e lo scarto
 * dei singoli inizi rispetto a quella fase; il C con lo scarto minore è il ciclo.
 *
 * Con i soli inizi verde anche C/2, C/3... spiegano i dati: le osservazioni di colore
 * (GREEN_SEEN, RED_SEEN, RED_START) servono a scartare questi sottomultipli.
 */
class PlanEstimator(private val config: Config = Config()) {

    data class Config(
        val minCycle: Double = 40.0,
        val maxCycle: Double = 180.0,
        /** Scarto (s) entro cui un piano è considerato buono. */
        val goodFitRms: Double = 3.0,
        /** Scarto (s) oltre cui, con molte osservazioni, il semaforo è dichiarato imprevedibile. */
        val badFitRms: Double = 6.0,
        val minGreenStartsForReliable: Int = 3,
        val minGreenStartsForUnpredictable: Int = 10,
        /** Durata minima plausibile di verde e rosso (s). */
        val minPhase: Double = 5.0,
        /** Tolleranza (s) nel giudicare un'osservazione di colore vicino al cambio. */
        val colorTolerance: Double = 1.5,
        /** Frazione di verde usata quando non ci sono osservazioni di colore. */
        val defaultGreenFraction: Double = 0.5,
    )

    private data class Fit(val cycle: Double, val offset: Double, val rms: Double)

    private data class GreenFit(val green: Double, val contradictions: Int, val measured: Boolean)

    private data class Candidate(val fit: Fit, val greenFit: GreenFit)

    fun estimate(observations: List<Observation>): PlanEstimate {
        val starts = observations.filter { it.kind == ObservationKind.GREEN_START }.map { it.time }.sorted()
        val span = if (starts.isEmpty()) 0.0 else starts.last() - starts.first()
        if (starts.size < 2 || span < config.minCycle / 2.0) {
            return PlanEstimate(null, PlanStatus.LEARNING, Double.NaN, starts.size, 0, false, emptyList())
        }

        val candidates = findCycleCandidates(starts, span)
            .map { Candidate(it, fitGreen(it, observations)) }
        val good = candidates.filter { it.fit.rms <= config.goodFitRms }
        val pool = good.ifEmpty { candidates }

        // Prima meno contraddizioni, poi il ciclo più lungo: i sottomultipli del ciclo vero
        // spiegano gli inizi verde altrettanto bene, quindi a parità si preferisce il più lungo.
        val best = pool.minWith(
            compareBy<Candidate> { it.greenFit.contradictions }
                .thenByDescending { if (it.fit.rms <= config.goodFitRms) it.fit.cycle else -it.fit.rms },
        )
        val alternatives = good
            .filter { it.greenFit.contradictions == best.greenFit.contradictions }
            .map { it.fit.cycle }
            .filter { abs(it - best.fit.cycle) > 0.5 }
            .sorted()

        val status = when {
            starts.size >= config.minGreenStartsForUnpredictable && best.fit.rms > config.badFitRms ->
                PlanStatus.UNPREDICTABLE
            starts.size >= config.minGreenStartsForReliable &&
                best.fit.rms <= config.goodFitRms &&
                best.greenFit.contradictions == 0 &&
                alternatives.isEmpty() -> PlanStatus.RELIABLE
            else -> PlanStatus.LEARNING
        }

        return PlanEstimate(
            plan = SignalPlan(best.fit.cycle, best.greenFit.green, positiveMod(best.fit.offset, best.fit.cycle)),
            status = status,
            rmsError = best.fit.rms,
            greenStarts = starts.size,
            contradictions = best.greenFit.contradictions,
            greenMeasured = best.greenFit.measured,
            alternativeCycles = alternatives,
        )
    }

    /**
     * Ricerca a griglia dei cicli che spiegano gli inizi verde, con passo abbastanza fine
     * da non perdere il minimo (l'errore di fase cresce con il numero di cicli nell'intervallo),
     * seguita da un raffinamento attorno a ciascun minimo locale.
     */
    private fun findCycleCandidates(starts: List<Double>, span: Double): List<Fit> {
        val step = (config.minCycle / span).coerceIn(0.0005, 0.05)
        val coarse = generateSequence(config.minCycle) { it + step }
            .takeWhile { it <= config.maxCycle }
            .map { fitCycle(starts, it) }
            .toList()

        val minima = coarse.indices
            .filter { i ->
                val rms = coarse[i].rms
                (i == 0 || rms <= coarse[i - 1].rms) && (i == coarse.lastIndex || rms <= coarse[i + 1].rms)
            }
            .map { coarse[it] }
            .sortedBy { it.rms }
            .take(MAX_CANDIDATES)

        val refined = minima.map { m ->
            (-10..10).map { k -> fitCycle(starts, m.cycle + k * step / 10.0) }.minBy { it.rms }
        }
        // Minimi quasi coincidenti (plateau) contano una volta sola.
        return refined.sortedBy { it.rms }.fold(mutableListOf()) { acc, fit ->
            if (acc.none { abs(it.cycle - fit.cycle) < 2 * step }) acc += fit
            acc
        }
    }

    private fun fitCycle(starts: List<Double>, cycle: Double): Fit {
        val offset = circularMean(starts, cycle)
        val sumSq = starts.sumOf { val r = signedMod(it - offset, cycle); r * r }
        return Fit(cycle, offset, sqrt(sumSq / starts.size))
    }

    /** Durata del verde e numero di osservazioni di colore incompatibili, per un dato ciclo. */
    private fun fitGreen(fit: Fit, observations: List<Observation>): GreenFit {
        val cycle = fit.cycle
        fun phase(o: Observation) = positiveMod(o.time - fit.offset, cycle)
        val greenSeen = observations.filter { it.kind == ObservationKind.GREEN_SEEN }.map(::phase)
        val redSeen = observations.filter { it.kind == ObservationKind.RED_SEEN }.map(::phase)
        val redStarts = observations.filter { it.kind == ObservationKind.RED_START }.map { it.time }
        val tol = config.colorTolerance
        val lo = config.minPhase
        val hi = cycle - config.minPhase

        fun contradictions(green: Double) =
            greenSeen.count { it >= green + tol } + redSeen.count { it < green - tol }

        if (redStarts.isNotEmpty()) {
            // La fine del verde è osservata direttamente.
            val green = positiveMod(circularMean(redStarts, cycle) - fit.offset, cycle)
            val redSpread = sqrt(redStarts.sumOf { val r = signedMod(it - fit.offset - green, cycle); r * r } / redStarts.size)
            val badRedStarts = if (redSpread > config.goodFitRms) redStarts.size else 0
            val clamped = green.coerceIn(lo, hi)
            return GreenFit(clamped, contradictions(clamped) + badRedStarts, measured = true)
        }

        if (greenSeen.isEmpty() && redSeen.isEmpty()) {
            return GreenFit((cycle * config.defaultGreenFraction).coerceIn(lo, hi), 0, measured = false)
        }

        // Si prova ogni durata di verde (passo 0,5 s) e si tiene il centro dell'intervallo
        // più lungo di valori con il minimo di contraddizioni.
        val grid = generateSequence(lo) { it + 0.5 }.takeWhile { it <= hi }.toList()
        val counts = grid.map(::contradictions)
        val minCount = counts.min()
        var bestRun = 0 to 0
        var runStart = -1
        for (i in counts.indices) {
            if (counts[i] == minCount) {
                if (runStart < 0) runStart = i
                if (i - runStart > bestRun.second - bestRun.first) bestRun = runStart to i
            } else {
                runStart = -1
            }
        }
        val green = (grid[bestRun.first] + grid[bestRun.second]) / 2.0
        return GreenFit(green, minCount, measured = true)
    }

    private fun circularMean(times: List<Double>, cycle: Double): Double {
        var s = 0.0
        var c = 0.0
        for (t in times) {
            val angle = 2.0 * PI * positiveMod(t, cycle) / cycle
            s += sin(angle)
            c += cos(angle)
        }
        return positiveMod(atan2(s, c) / (2.0 * PI) * cycle, cycle)
    }

    private companion object {
        const val MAX_CANDIDATES = 60
    }
}
