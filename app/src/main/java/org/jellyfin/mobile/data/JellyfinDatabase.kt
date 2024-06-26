package org.jellyfin.mobile.data

import androidx.room.Database
import androidx.room.RoomDatabase
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.mobile.data.entity.DownloadEntity

@Database(entities = [ServerEntity::class, UserEntity::class, DownloadEntity::class], version = 3)
abstract class JellyfinDatabase : RoomDatabase() {
    abstract val serverDao: ServerDao
    abstract val userDao: UserDao
    abstract val downloadDao: DownloadDao
}
