package tw.umaya.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insert(point: TrackPoint)

    @Query("SELECT * FROM track_points WHERE hikeId = :hikeId AND synced = 0 ORDER BY recordedAtIso ASC LIMIT :limit")
    suspend fun getPending(hikeId: Long, limit: Int = 100): List<TrackPoint>

    @Query("SELECT COUNT(*) FROM track_points WHERE hikeId = :hikeId AND synced = 0")
    suspend fun pendingCount(hikeId: Long): Int

    @Update
    suspend fun update(point: TrackPoint)

    @Query("UPDATE track_points SET synced = 1 WHERE clientId IN (:clientIds)")
    suspend fun markSynced(clientIds: List<String>)
}
