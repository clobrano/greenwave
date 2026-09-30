package io.github.clobrano.greenwave.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class PlanStatus {
    /** Not enough data, or ambiguous data: predictions must not be shown as reliable. */
    LEARNING,

    /** The plan explains the observations well. */
    RELIABLE,

    /** Many observations but no fixed cycle explains them: probably an actuated/adaptive light. */
    UNPREDICTABLE,
}

data class PlanEstimate(
    /** Estimated plan, or null if the data is not enough even for a guess. */
    val plan: SignalPlan?,
    val status: PlanStatus,
    /** Root mean square error (s) between observed and predicted starts of green. */
    val rmsError: Double,
    val greenStarts: Int,
    /** Color observations (green/red seen) that contradict the plan. */
    val contradictions: Int,
    /** True if the green duration comes from observations, false if it is a default value. */
    val greenMeasured: Boolean,
    /** Other cycles that explain the data equally well: when not empty the cycle is ambiguous. */
    val alternativeCycles: List<Double>,
)

/**
 * Estimates cycle, phase (offset) and green duration of a fixed-time traffic light.
 *
 * Idea: starts of green are integer multiples of the cycle C apart. For each candidate C
 * we compute the mean phase of the starts of green (circular mean) and how far each start
 * is from that phase; the C with the smallest error is the cycle.
 *
 * With starts of green alone, C/2, C/3... explain the data too: color observations
 * (GREEN_SEEN, RED_SEEN, RED_START) are used to rule out these submultiples.
 */
class PlanEstimator(private val config: Config = Config()) {

    data class Config(
        val minCycle: Double = 40.0,
        val maxCycle: Double = 180.0,
        /** Error (s) within which a plan is considered good. */
        val goodFitRms: Double = 3.0,
        /** Error (s) above which, with many observations, the light is declared unpredictable. */
        val badFitRms: Double = 6.0,
        val minGreenStartsForReliable: Int = 3,
        val minGreenStartsForUnpredictable: Int = 10,
        /** Minimum plausible duration of green and red (s). */
        val minPhase: Double = 5.0,
        /** Tolerance (s) when judging a color observation close to a change. */
        val colorTolerance: Double = 1.5,
        /** Green fraction used when there are no color observations. */
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

        // Fewest contradictions first, then the longest cycle: submultiples of the true cycle
        // explain the starts of green equally well, so on a tie the longest one wins.
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
     * Grid search for the cycles that explain the starts of green, with a step fine enough
     * not to miss the minimum (the phase error grows with the number of cycles in the span),
     * followed by a refinement around each local minimum.
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
        // Nearly identical minima (plateaus) count once.
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

    /** Green duration and number of contradicting color observations, for a given cycle. */
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
            // The end of green is observed directly.
            val green = positiveMod(circularMean(redStarts, cycle) - fit.offset, cycle)
            val redSpread = sqrt(redStarts.sumOf { val r = signedMod(it - fit.offset - green, cycle); r * r } / redStarts.size)
            val badRedStarts = if (redSpread > config.goodFitRms) redStarts.size else 0
            val clamped = green.coerceIn(lo, hi)
            return GreenFit(clamped, contradictions(clamped) + badRedStarts, measured = true)
        }

        if (greenSeen.isEmpty() && redSeen.isEmpty()) {
            return GreenFit((cycle * config.defaultGreenFraction).coerceIn(lo, hi), 0, measured = false)
        }

        // Try every green duration (0.5 s step) and keep the middle of the longest run
        // of values with the fewest contradictions.
        val grid = generateSequence(lo) { it + 0.5 }.takeWhile { it <= hi }.toList()
        val counts = grid.map(::contradictions)
        val minCount = counts.min()
        var bestRun: IntRange? = null
        var runStart = -1
        for (i in counts.indices) {
            if (counts[i] == minCount) {
                if (runStart < 0) runStart = i
                val run = runStart..i
                if (bestRun == null || run.count() > bestRun.count()) bestRun = run
            } else {
                runStart = -1
            }
        }
        val run = checkNotNull(bestRun)
        val green = (grid[run.first] + grid[run.last]) / 2.0
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
