package io.github.clobrano.greenwave.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class PlanStatus {
    /** Not enough data, or ambiguous data: predictions must not be shown as reliable. */
    LEARNING,

    /** The plan explains the observations well. */
    RELIABLE,

    /** Many observations but no fixed cycle explains them: probably an actuated/adaptive light. */
    UNPREDICTABLE,
}

/** Why a light is not (yet) predictable. */
enum class Blocker {
    /** No observation at all. */
    NO_DATA,

    /** Some observations, but not enough to tell the cycle apart from chance. */
    TOO_FEW,

    /** Several cycles explain the observations equally well (see [PlanEstimate.alternativeCycles]). */
    AMBIGUOUS,

    /** No fixed cycle explains the observations (see [PlanEstimate.errorRate]). */
    INCONSISTENT,
}

data class PlanEstimate(
    /** Best plan found, or null if the data is not enough even for a guess. */
    val plan: SignalPlan?,
    val status: PlanStatus,
    /** Why the light is not predictable; null when it is. */
    val blocker: Blocker?,
    /** Evidence collected (a pass counts 1, a start of green about 4), and how much is needed. */
    val evidence: Double,
    val evidenceNeeded: Double,
    /** Share of the evidence that contradicts [plan], from 0 to 1. */
    val errorRate: Double,
    /** Root mean square distance (s) of the observed starts of green from the plan; NaN without starts. */
    val rmsError: Double,
    val greenStarts: Int,
    /** True if the green duration comes from observations, false if it is a default value. */
    val greenMeasured: Boolean,
    /** Other cycles that explain the data about as well: when not empty the cycle is ambiguous. */
    val alternativeCycles: List<Double>,
)

/**
 * Estimates cycle, phase (offset) and green duration of a fixed-time traffic light by
 * phase folding.
 *
 * Every observation becomes a green or red sample: a light seen green or red is one sample;
 * a start of green is "red just before, green just after" (and the opposite for a start of
 * red), weighted more because it pins the edge of the green. For a candidate cycle C each
 * sample is folded to its position in the cycle (time mod C). With the right C the green
 * samples fall in one contiguous arc of the cycle and the red ones outside it; with a wrong
 * C they mix. The arc with the fewest misplaced samples is found exactly with one sort and
 * a maximum circular subarray; the cycle with the fewest errors wins, and the edges of its
 * arc give the start and duration of green.
 *
 * Passes alone (no stops, no buttons) are enough given enough of them, and the method
 * tells a cycle apart from half of it whenever some red samples fall in the second half.
 */
class PlanEstimator(private val config: Config = Config()) {

    data class Config(
        val minCycle: Double = 40.0,
        val maxCycle: Double = 180.0,
        /** Evidence needed before a plan can be called reliable (about 12 passes or 3 starts of green). */
        val minEvidence: Double = 12.0,
        /** Above this share of contradicting evidence the plan is not reliable. */
        val maxErrorRate: Double = 0.10,
        /** With at least [minEvidenceForUnpredictable], above this share the light is unpredictable. */
        val unpredictableErrorRate: Double = 0.20,
        val minEvidenceForUnpredictable: Double = 40.0,
        /** Cycles scoring within this share of the evidence (or 1, if more) of the best are alternatives. */
        val ambiguitySlack: Double = 0.03,
        /** Cycles closer than this (s) to the best one are the same cycle, not alternatives. */
        val sameCycle: Double = 1.0,
        /** Distance (s) of the two samples of a start from its time, for button presses and for GPS. */
        val preciseStartMargin: Double = 1.5,
        val gpsStartMargin: Double = 4.0,
        /** Weight of each of the two samples of a start (a seen color weighs 1). */
        val preciseStartWeight: Double = 2.0,
        val gpsStartWeight: Double = 1.5,
        /** Minimum plausible duration of green and red (s). */
        val minPhase: Double = 5.0,
        /** Green fraction used when only starts of green are known. */
        val defaultGreenFraction: Double = 0.5,
        /** Only the most recent observations are used, to bound the computation. */
        val maxObservations: Int = 400,
        /** At most this many candidate cycles are tried (coarser grid on very long spans). */
        val maxCandidates: Int = 20_000,
    )

    private class Sample(val time: Double, val green: Boolean, val weight: Double)

    /** The samples as plain arrays, so that folding thousands of cycles stays fast. */
    private class Samples(list: List<Sample>) {
        val size = list.size
        val time = DoubleArray(size) { list[it].time }
        val value = DoubleArray(size) { if (list[it].green) list[it].weight else -list[it].weight }
        val totalGreen = list.filter { it.green }.sumOf { it.weight }
    }

    /** Best arc of green for one cycle: [errors] is the weight of the misplaced samples. */
    private class Fold(val errors: Double, val greenStart: Double, val greenDuration: Double)

    private class Group(val cycle: Double, val errors: Double)

    fun estimate(observations: List<Observation>): PlanEstimate {
        val used = observations.sortedBy { it.time }.takeLast(config.maxObservations)
        val samples = used.flatMap(::toSamples)
        val starts = used.filter { it.kind == ObservationKind.GREEN_START }.map { it.time }
        val evidence = samples.sumOf { it.weight }
        val span = if (samples.isEmpty()) 0.0 else samples.maxOf { it.time } - samples.minOf { it.time }
        if (samples.isEmpty()) return empty(Blocker.NO_DATA, 0.0, starts.size)
        if (evidence < config.minEvidence || span < config.minCycle) return empty(Blocker.TOO_FEW, evidence, starts.size)

        // Grid fine enough that the phase drifts by at most about half a second over the span.
        val minStep = (config.maxCycle - config.minCycle) / config.maxCandidates
        val step = (0.5 * config.minCycle / span).coerceIn(max(minStep, 0.0005), 0.05)
        val cycles = generateSequence(config.minCycle) { it + step }.takeWhile { it <= config.maxCycle }.toList()
        val folded = Samples(samples)
        val scores = DoubleArray(cycles.size) { fold(folded, cycles[it]).errors }

        val bestErrors = scores.min()
        val slack = max(1.0, config.ambiguitySlack * evidence)
        val groups = groupsWithin(cycles, scores, bestErrors + slack)
        // Equal errors: prefer the longer cycle, since submultiples of the true cycle fit
        // starts of green alone as well as the cycle itself.
        val best = groups.filter { it.errors == bestErrors }.maxBy { it.cycle }
        val alternatives = groups
            .filter { abs(it.cycle - best.cycle) > config.sameCycle }
            .sortedBy { it.errors }
            .map { it.cycle }

        val fold = fold(folded, best.cycle)
        val greenMeasured = used.any { it.kind != ObservationKind.GREEN_START }
        val green = (if (greenMeasured) fold.greenDuration else best.cycle * config.defaultGreenFraction)
            .coerceIn(config.minPhase, best.cycle - config.minPhase)
        val offset = positiveMod(fold.greenStart, best.cycle)
        val errorRate = fold.errors / evidence

        val blocker = when {
            errorRate > config.maxErrorRate -> Blocker.INCONSISTENT
            alternatives.isNotEmpty() -> Blocker.AMBIGUOUS
            else -> null
        }
        val status = when {
            evidence >= config.minEvidenceForUnpredictable && errorRate > config.unpredictableErrorRate ->
                PlanStatus.UNPREDICTABLE
            blocker == null -> PlanStatus.RELIABLE
            else -> PlanStatus.LEARNING
        }
        val rms = if (starts.isEmpty()) {
            Double.NaN
        } else {
            sqrt(starts.sumOf { val r = signedMod(it - offset, best.cycle); r * r } / starts.size)
        }
        return PlanEstimate(
            plan = SignalPlan(best.cycle, green, offset),
            status = status,
            blocker = blocker,
            evidence = evidence,
            evidenceNeeded = config.minEvidence,
            errorRate = errorRate,
            rmsError = rms,
            greenStarts = starts.size,
            greenMeasured = greenMeasured,
            alternativeCycles = alternatives.take(MAX_ALTERNATIVES),
        )
    }

    private fun empty(blocker: Blocker, evidence: Double, starts: Int) = PlanEstimate(
        plan = null,
        status = PlanStatus.LEARNING,
        blocker = blocker,
        evidence = evidence,
        evidenceNeeded = config.minEvidence,
        errorRate = Double.NaN,
        rmsError = Double.NaN,
        greenStarts = starts,
        greenMeasured = false,
        alternativeCycles = emptyList(),
    )

    private fun toSamples(o: Observation): List<Sample> {
        val margin = if (o.precise) config.preciseStartMargin else config.gpsStartMargin
        val weight = if (o.precise) config.preciseStartWeight else config.gpsStartWeight
        return when (o.kind) {
            ObservationKind.GREEN_SEEN -> listOf(Sample(o.time, green = true, weight = 1.0))
            ObservationKind.RED_SEEN -> listOf(Sample(o.time, green = false, weight = 1.0))
            ObservationKind.GREEN_START -> listOf(
                Sample(o.time - margin, green = false, weight = weight),
                Sample(o.time + margin, green = true, weight = weight),
            )
            ObservationKind.RED_START -> listOf(
                Sample(o.time - margin, green = true, weight = weight),
                Sample(o.time + margin, green = false, weight = weight),
            )
        }
    }

    /**
     * Folds the samples on [cycle] and finds the arc of green that misplaces the least
     * weight: with green samples counted +w and red ones -w in cycle order, that arc is the
     * maximum circular subarray, and the misplaced weight is (total green) - (its sum).
     */
    private fun fold(samples: Samples, cycle: Double): Fold {
        val n = samples.size
        val unsorted = DoubleArray(n) { positiveMod(samples.time[it], cycle) }
        val order = (0 until n).sortedBy { unsorted[it] }
        val phase = DoubleArray(n) { unsorted[order[it]] }
        val value = DoubleArray(n) { samples.value[order[it]] }
        val totalGreen = samples.totalGreen

        // Maximum and minimum linear subarrays (Kadane), with their bounds.
        var bestMax = Double.NEGATIVE_INFINITY
        var maxFrom = 0
        var maxTo = 0
        var bestMin = Double.POSITIVE_INFINITY
        var minFrom = 0
        var minTo = 0
        var curMax = 0.0
        var curMaxFrom = 0
        var curMin = 0.0
        var curMinFrom = 0
        for (i in 0 until n) {
            if (curMax <= 0.0) { curMax = 0.0; curMaxFrom = i }
            curMax += value[i]
            if (curMax > bestMax) { bestMax = curMax; maxFrom = curMaxFrom; maxTo = i }
            if (curMin >= 0.0) { curMin = 0.0; curMinFrom = i }
            curMin += value[i]
            if (curMin < bestMin) { bestMin = curMin; minFrom = curMinFrom; minTo = i }
        }
        // The best arc either lies inside the folded sequence or wraps around the end of
        // the cycle, in which case it is everything but the minimum subarray.
        val total = value.sum()
        val wraps = !(minFrom == 0 && minTo == n - 1) && total - bestMin > bestMax
        val (from, to, arcSum) = if (wraps) Triple((minTo + 1) % n, (minFrom - 1 + n) % n, total - bestMin)
        else Triple(maxFrom, maxTo, bestMax)

        if (arcSum <= 0.0) return Fold(totalGreen, 0.0, 0.0)
        // The edges of green lie halfway between the last red and the first green sample.
        val before = phase[(from - 1 + n) % n]
        val after = phase[(to + 1) % n]
        val start = before + positiveMod(phase[from] - before, cycle) / 2.0
        val end = phase[to] + positiveMod(after - phase[to], cycle) / 2.0
        val duration = if (n == 1) cycle else positiveMod(end - start, cycle).let { if (it == 0.0) cycle else it }
        return Fold(totalGreen - arcSum, start, duration)
    }

    /**
     * Runs of consecutive grid cycles scoring at most [limit] errors; each run is one
     * candidate cycle, represented by the middle one of its best-scoring points.
     */
    private fun groupsWithin(cycles: List<Double>, scores: DoubleArray, limit: Double): List<Group> {
        val groups = mutableListOf<Group>()
        var i = 0
        while (i < cycles.size) {
            if (scores[i] > limit) { i++; continue }
            var j = i
            while (j + 1 < cycles.size && scores[j + 1] <= limit) j++
            val runBest = (i..j).minOf { scores[it] }
            val bestPoints = (i..j).filter { scores[it] == runBest }
            groups += Group(cycles[bestPoints[bestPoints.size / 2]], runBest)
            i = j + 1
        }
        return groups
    }

    private companion object {
        const val MAX_ALTERNATIVES = 5
    }
}
