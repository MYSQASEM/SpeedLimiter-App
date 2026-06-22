package com.qasim.speedlimiter.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps_config")
data class AppEntity(
    @PrimaryKey val packageName: String,
    val speedLimitKbps: Long,
    val isBlocked: Boolean
)