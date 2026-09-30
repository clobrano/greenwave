package io.github.clobrano.greenwave.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrafficLightDao {
    @Query("SELECT * FROM traffic_light ORDER BY routeOrder, id")
    fun observeAll(): Flow<List<TrafficLightEntity>>

    @Query("SELECT * FROM traffic_light ORDER BY routeOrder, id")
    suspend fun getAll(): List<TrafficLightEntity>

    @Query("SELECT COALESCE(MAX(routeOrder), -1) FROM traffic_light")
    suspend fun maxRouteOrder(): Int

    @Insert
    suspend fun insert(light: TrafficLightEntity): Long

    @Update
    suspend fun update(vararg lights: TrafficLightEntity)

    @Delete
    suspend fun delete(light: TrafficLightEntity)

    /** Scambia la posizione nel percorso di due semafori. */
    @Transaction
    suspend fun swapOrder(a: TrafficLightEntity, b: TrafficLightEntity) {
        update(a.copy(routeOrder = b.routeOrder), b.copy(routeOrder = a.routeOrder))
    }
}

@Dao
interface ObservationDao {
    @Query("SELECT * FROM observation ORDER BY epochMillis DESC")
    fun observeAll(): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM observation ORDER BY epochMillis")
    suspend fun getAll(): List<ObservationEntity>

    @Insert
    suspend fun insert(observation: ObservationEntity): Long

    @Delete
    suspend fun delete(observation: ObservationEntity)
}

@Database(
    entities = [TrafficLightEntity::class, ObservationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trafficLights(): TrafficLightDao
    abstract fun observations(): ObservationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "greenwave.db").build()
    }
}
