package io.github.clobrano.greenwave.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeBandsTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val bands = TimeBands(zone = zone)

    @Test
    fun `band and day type`() {
        // Wednesday 30 September 2026, 8:15.
        val slot = bands.slotOf(ZonedDateTime.of(2026, 9, 30, 8, 15, 0, 0, zone))
        assertEquals(ScheduleSlot(DayType.WEEKDAY, LocalTime.of(7, 0), LocalTime.of(9, 30)), slot)

        val night = bands.slotOf(ZonedDateTime.of(2026, 10, 3, 22, 0, 0, 0, zone))
        assertEquals(ScheduleSlot(DayType.SATURDAY, LocalTime.of(20, 0), LocalTime.MAX), night)
        assertEquals("Saturday 20:00–24:00", night.label)
    }

    @Test
    fun `seconds since midnight and back`() {
        val time = ZonedDateTime.of(2026, 9, 30, 8, 15, 30, 250_000_000, zone)
        val millis = time.toInstant().toEpochMilli()
        val seconds = bands.secondsOfDay(millis)
        assertEquals(8 * 3600 + 15 * 60 + 30.25, seconds, 1e-9)
        assertEquals(millis, bands.toEpochMillis(millis, seconds))
    }
}
