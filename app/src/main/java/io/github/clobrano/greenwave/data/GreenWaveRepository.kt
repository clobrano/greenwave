package io.github.clobrano.greenwave.data

import io.github.clobrano.greenwave.model.DetectedObservation
import io.github.clobrano.greenwave.model.GeoPoint
import io.github.clobrano.greenwave.model.Observation
import io.github.clobrano.greenwave.model.ObservationKind
import io.github.clobrano.greenwave.model.PlanEstimate
import io.github.clobrano.greenwave.model.PlanEstimator
import io.github.clobrano.greenwave.model.ScheduleSlot
import io.github.clobrano.greenwave.model.TimeBands
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plan estimate of a traffic light for the time band [slot]. */
data class LightEstimate(val slot: ScheduleSlot, val estimate: PlanEstimate)

class GreenWaveRepository(
    private val db: AppDatabase,
    private val timeBands: TimeBands = TimeBands(),
    private val estimator: PlanEstimator = PlanEstimator(),
) {
    val lights = db.trafficLights().observeAll()
    val observations = db.observations().observeAll()

    suspend fun addLight(name: String, position: GeoPoint, approachBearing: Double?, speedLimitKmh: Int): Long =
        db.trafficLights().insert(
            TrafficLightEntity(
                name = name,
                lat = position.lat,
                lon = position.lon,
                approachBearing = approachBearing,
                speedLimitKmh = speedLimitKmh,
                routeOrder = db.trafficLights().maxRouteOrder() + 1,
            ),
        )

    suspend fun updateLight(light: TrafficLightEntity) = db.trafficLights().update(light)

    suspend fun deleteLight(light: TrafficLightEntity) = db.trafficLights().delete(light)

    /** Moves a traffic light by one position on the route ([delta] = -1 up, +1 down). */
    suspend fun moveLight(light: TrafficLightEntity, delta: Int) {
        val all = db.trafficLights().getAll()
        val index = all.indexOfFirst { it.id == light.id }
        val other = all.getOrNull(index + delta) ?: return
        // Normalize the order values before swapping, in case of duplicates.
        val a = all[index].copy(routeOrder = index)
        val b = other.copy(routeOrder = index + delta)
        db.trafficLights().swapOrder(a, b)
    }

    suspend fun addObservation(observation: ObservationEntity): ObservationEntity =
        observation.copy(id = db.observations().insert(observation))

    suspend fun deleteObservation(observation: ObservationEntity) = db.observations().delete(observation)

    /**
     * Saves an observation inferred from GPS, unless the same thing was already recorded
     * nearby in time (e.g. a GREEN NOW press, which also adds the wait at red).
     * Returns the saved observation, or null if it was a duplicate.
     */
    suspend fun addDetected(detected: DetectedObservation, gnssTime: Boolean): ObservationEntity? {
        val window = when (detected.kind) {
            ObservationKind.GREEN_START -> 15_000L
            ObservationKind.RED_SEEN -> 30_000L
            else -> 0L
        }
        if (window > 0 && db.observations().countNear(
                detected.lightId, detected.kind, detected.timeMillis - window, detected.timeMillis + window,
            ) > 0
        ) {
            return null
        }
        return addObservation(
            ObservationEntity(
                lightId = detected.lightId,
                epochMillis = detected.timeMillis,
                kind = detected.kind,
                source = ObservationSource.GPS,
                gnssTime = gnssTime,
            ),
        )
    }

    /** Estimates the plans of all traffic lights for the time band containing [nowMillis]. */
    fun estimate(observations: List<ObservationEntity>, nowMillis: Long): Map<Long, LightEstimate> {
        val slot = timeBands.slotOf(nowMillis)
        return observations
            .filter { timeBands.slotOf(it.epochMillis) == slot }
            .groupBy { it.lightId }
            .mapValues { (_, list) ->
                val modelObservations = list.map { Observation(timeBands.secondsOfDay(it.epochMillis), it.kind) }
                LightEstimate(slot, estimator.estimate(modelObservations))
            }
    }

    fun secondsOfDay(epochMillis: Long): Double = timeBands.secondsOfDay(epochMillis)

    fun toEpochMillis(referenceMillis: Long, secondsOfDay: Double): Long =
        timeBands.toEpochMillis(referenceMillis, secondsOfDay)

    /** Exports all observations as CSV, one row per observation with the light's data. */
    suspend fun exportCsv(writer: Writer) {
        val lights = db.trafficLights().getAll().associateBy { it.id }
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
        writer.appendLine("light_id,light_name,lat,lon,route_order,epoch_ms,local_time,kind,source,gnss_time")
        for (o in db.observations().getAll()) {
            val light = lights[o.lightId] ?: continue
            writer.appendLine(
                listOf(
                    light.id,
                    csvQuote(light.name),
                    light.lat,
                    light.lon,
                    light.routeOrder,
                    o.epochMillis,
                    formatter.format(Instant.ofEpochMilli(o.epochMillis)),
                    o.kind,
                    o.source,
                    o.gnssTime,
                ).joinToString(","),
            )
        }
        writer.flush()
    }

    private fun csvQuote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
