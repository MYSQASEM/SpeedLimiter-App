package com.qasim.speedlimiter.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_limits")
data class AppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isLimited: Boolean,
    val speedLimitKbps: Long,
    val isBlocked: Boolean
)
