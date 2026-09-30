package io.github.clobrano.greenwave.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class DayType { WEEKDAY, SATURDAY, SUNDAY }

/** Fascia oraria [start, end) di un tipo di giorno: i semafori possono cambiare piano tra una fascia e l'altra. */
data class ScheduleSlot(val dayType: DayType, val start: LocalTime, val end: LocalTime) {
    val label: String get() = "${dayType.label} $start–${if (end == LocalTime.MAX) "24:00" else end.toString()}"
}

private val DayType.label: String
    get() = when (this) {
        DayType.WEEKDAY -> "Feriale"
        DayType.SATURDAY -> "Sabato"
        DayType.SUNDAY -> "Domenica"
    }

/**
 * Suddivisione della giornata in fasce. I piani vengono stimati separatamente per fascia
 * e i tempi passati al modello sono i secondi dalla mezzanotte locale.
 * Le festività infrasettimanali non sono riconosciute (valgono come feriali).
 */
class TimeBands(
    private val boundaries: List<LocalTime> = listOf(
        LocalTime.of(7, 0),
        LocalTime.of(9, 30),
        LocalTime.of(17, 0),
        LocalTime.of(20, 0),
    ),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun slotOf(epochMillis: Long): ScheduleSlot = slotOf(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun slotOf(time: ZonedDateTime): ScheduleSlot {
        val dayType = when (time.dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.SATURDAY
            DayOfWeek.SUNDAY -> DayType.SUNDAY
            else -> DayType.WEEKDAY
        }
        val local = time.toLocalTime()
        val start = boundaries.lastOrNull { it <= local } ?: LocalTime.MIDNIGHT
        val end = boundaries.firstOrNull { it > local } ?: LocalTime.MAX
        return ScheduleSlot(dayType, start, end)
    }

    /** Secondi dalla mezzanotte locale, con i millisecondi. */
    fun secondsOfDay(epochMillis: Long): Double {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val midnight = time.toLocalDate().atStartOfDay(zone)
        return (epochMillis - midnight.toInstant().toEpochMilli()) / 1000.0
    }

    /** Istante (epoch ms) corrispondente a [secondsOfDay] nello stesso giorno locale di [referenceMillis]. */
    fun toEpochMillis(referenceMillis: Long, secondsOfDay: Double): Long {
        val midnight = Instant.ofEpochMilli(referenceMillis).atZone(zone).toLocalDate().atStartOfDay(zone)
        return midnight.toInstant().toEpochMilli() + (secondsOfDay * 1000).toLong()
    }
}
