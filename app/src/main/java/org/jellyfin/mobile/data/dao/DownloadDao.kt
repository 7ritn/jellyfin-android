package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadEntity.Key.TABLE_NAME
import kotlin.random.Random

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Query("SELECT * FROM $TABLE_NAME ORDER BY download_name DESC")
    suspend fun getAllDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM $TABLE_NAME WHERE item_id LIKE :downloadId")
    suspend fun getDownload(downloadId: String): DownloadEntity
}