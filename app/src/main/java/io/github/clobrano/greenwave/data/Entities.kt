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
    /** Direzione di marcia con cui lo attraverso, in gradi (0 = nord); null = qualsiasi. */
    val approachBearing: Double?,
    val speedLimitKmh: Int = 50,
    /** Posizione nel percorso (0 = primo semaforo che incontro). */
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
    /** Istante UTC in millisecondi, corretto con l'ora GNSS quando disponibile. */
    val epochMillis: Long,
    val kind: ObservationKind,
    val source: ObservationSource,
    /** True se [epochMillis] è allineato all'ora GNSS, false se viene dall'orologio del telefono. */
    val gnssTime: Boolean,
)
