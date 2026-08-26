package tw.umaya.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "track_points")
data class TrackPoint(
    @PrimaryKey val clientId: String = UUID.randomUUID().toString(),
    val hikeId: Long,
    val lat: Double,
    val lng: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val markerType: String = "normal", // normal | safe | sos
    val batteryPct: Int?,
    val recordedAtIso: String,
    val synced: Boolean = false,
)
