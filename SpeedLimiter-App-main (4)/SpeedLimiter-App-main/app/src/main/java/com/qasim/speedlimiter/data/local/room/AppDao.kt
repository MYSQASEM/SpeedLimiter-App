package com.qasim.speedlimiter.data.local.room

import androidx.room.*

@Dao
interface AppDao {
    @Query("SELECT * FROM apps_config")
    fun getAllAppsConfig(): List<AppEntity>

    @Query("SELECT * FROM apps_config WHERE packageName = :packageName LIMIT 1")
    fun getAppConfig(packageName: String): AppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(app: AppEntity)

    @Delete
    fun delete(app: AppEntity)
}