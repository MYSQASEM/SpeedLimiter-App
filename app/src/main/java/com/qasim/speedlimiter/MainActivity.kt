package com.qasim.speedlimiter

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qasim.speedlimiter.data.services.LocalVpnService

class MainActivity : ComponentActivity() {

    private val VPN_REQUEST_CODE = 24

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)

        setContent {
            val backgroundColor = Color(0xFF111625) 
            val cardColor = Color(0xFF1E293B)       
            val primaryPurple = Color(0xFF6366F1)   
            val successGreen = Color(0xFF10B981)    
            val lightBlue = Color(0xFF38BDF8)

            var isVpnEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("is_enabled", false)) }
            
            // تهيئة السلايدر بالقيمة المحفوظة أو القيمة الافتراضية 1024
            var speedLimit by remember { mutableStateOf(sharedPrefs.getInt("speed_limit", 1024).toFloat()) }

            Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. العنوان
                    Text(
                        text = "Speed Limiter Pro",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    // 2. العداد المركزي وحالة الخدمة
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor).padding(24.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(140.dp).clip(CircleShape).background(if (isVpnEnabled) successGreen.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val speedDisplay = if (speedLimit >= 1000) "${String.format("%.1f", speedLimit / 1000f)} Mbps" else "${speedLimit.toInt()} Kbps"
                                Text(
                                    text = speedDisplay,
                                    color = if (isVpnEnabled) successGreen else Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = if (isVpnEnabled) "الحد الأقصى نشط" else "الخدمة متوقفة",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // 📊 مؤشرات التنزيل والتحميل المتزنة والمحسنة منطقياً
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // مؤشر التنزيل
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "📥 التنزيل (DL)", color = lightBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                val dlSpeed = if (speedLimit >= 1000) "${String.format("%.1f", (speedLimit * 0.85f) / 1000f)} Mbps" else "${(speedLimit * 0.85f).toInt()} Kbps"
                                Text(text = if (isVpnEnabled) dlSpeed else "0.0 Kbps", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                            
                            // فاصل عمودي
                            Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color.Gray.copy(alpha = 0.3f)))

                            // مؤشر التحميل
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "📤 التحميل (UL)", color = primaryPurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                val ulSpeed = if (speedLimit >= 1000) "${String.format("%.1f", (speedLimit * 0.50f) / 1000f)} Mbps" else "${(speedLimit * 0.50f).toInt()} Kbps"
                                Text(text = if (isVpnEnabled) ulSpeed else "0.0 Kbps", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    // 3. السلايدر المحدث بالنطاق الجديد المطلق
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor).padding(24.dp)) {
                        Text(text = "اسحب لتحديد سقف السرعة الإجمالية:", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(16.dp))

                        Slider(
                            value = speedLimit,
                            onValueChange = { newValue ->
                                speedLimit = newValue
                                sharedPrefs.edit().putInt("speed_limit", newValue.toInt()).apply()
                                
                                // مزامنة حية وإرسال التحديث للخدمة النشطة مباشرة أثناء السحب
                                if (isVpnEnabled) {
                                    val intent = Intent(this@MainActivity, LocalVpnService::class.java).apply { action = "START" }
                                    startService(intent)
                                }
                            },
                            valueRange = 100f..30000f, // النطاق الحسابي المحدث: من 100 Kbps إلى 30 Mbps
                            colors = SliderDefaults.colors(thumbColor = primaryPurple, activeTrackColor = primaryPurple, inactiveTrackColor = Color.Gray.copy(alpha = 0.3f))
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "100 Kbps", color = Color.Gray, fontSize = 12.sp)
                            Text(text = "30 Mbps", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    // 4. زر التشغيل الإيقاف
                    Button(
                        onClick = {
                            if (isVpnEnabled) {
                                val intent = Intent(this@MainActivity, LocalVpnService::class.java).apply { action = "STOP" }
                                startService(intent)
                                isVpnEnabled = false
                                sharedPrefs.edit().putBoolean("is_enabled", false).apply()
                            } else {
                                val vpnIntent = VpnService.prepare(this@MainActivity)
                                if (vpnIntent != null) {
                                    startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                                } else {
                                    onActivityResult(VPN_REQUEST_CODE, ComponentActivity.RESULT_OK, null)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isVpnEnabled) Color(0xFFEF4444) else primaryPurple),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(60.dp).padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = if (isVpnEnabled) "إيقاف محدد السرعة" else "تشغيل وتحديد السرعة الآن",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == ComponentActivity.RESULT_OK) {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("is_enabled", true).apply()
            
            val intent = Intent(this, LocalVpnService::class.java).apply { action = "START" }
            startService(intent)
            recreate()
        }
    }
}
