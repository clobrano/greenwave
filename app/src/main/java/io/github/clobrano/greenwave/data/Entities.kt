package io.github.clobrano.greenwave.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.clobrano.greenwave.model.GeoPoint
import io.github.clobrano.greenwave.model.LightPosition
import io.github.clobrano.greenwave.model.ObservationKind

@Entity(tableName = "traffic_light")
data class TrafficLightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Driving direction I cross it with, in degrees (0 = north); null = any. */
    val approachBearing: Double?,
    val speedLimitKmh: Int = 50,
    /** Position on the route (0 = first light I meet). */
    val routeOrder: Int,
    val notes: String = "",
) {
    val position: GeoPoint get() = GeoPoint(lat, lon)
    fun toLightPosition() = LightPosition(id, position, approachBearing)
}

enum class ObservationSource { MANUAL, GPS }

@Entity(
    tableName = "observation",
    foreignKeys = [
        ForeignKey(
            entity = TrafficLightEntity::class,
            parentColumns = ["id"],
            childColumns = ["lightId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lightId"), Index("epochMillis")],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lightId: Long,
    /** UTC instant in milliseconds, corrected with GNSS time when available. */
    val epochMillis: Long,
    val kind: ObservationKind,
    val source: ObservationSource,
    /** True if [epochMillis] is aligned to GNSS time, false if it comes from the phone clock. */
    val gnssTime: Boolean,
)
