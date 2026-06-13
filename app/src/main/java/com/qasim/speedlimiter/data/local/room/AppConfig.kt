package com.qasim.speedlimiter.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

// تعريف جدول في قاعدة البيانات لحفظ إعدادات كل تطبيق
@Entity(tableName = "app_config_table")
data class AppConfig(
    @PrimaryKey 
    val packageName: String, // اسم حزمة التطبيق الفريد (مثال: com.whatsapp)
    val appName: String,     // اسم التطبيق الظاهري
    val isLimited: Boolean,  // هل تم تفعيل تقييد السرعة لهذا التطبيق؟
    val speedLimit: Double,  // قيمة السرعة المحددة له
    val unit: String         // وحدة القياس (كيلوبايت، ميجابايت)
)