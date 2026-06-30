package com.qasim.speedlimiter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qasim.speedlimiter.data.services.LocalVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    var isChecked: Boolean
)

class MainActivity : ComponentActivity() {

    private val VPN_REQUEST_CODE = 24

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPrefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            val scope = rememberCoroutineScope()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val context = LocalContext.current

            val backgroundColor = Color(0xFF111625)
            val cardColor = Color(0xFF1E293B)
            val primaryPurple = Color(0xFF6366F1)
            val successGreen = Color(0xFF10B981)
            val lightBlue = Color(0xFF38BDF8)
            val accentBlue = Color(0xFF00B4D8)

            var isVpnEnabled by remember { mutableStateOf(sharedPrefs.getBoolean(AppConfig.KEY_IS_ENABLED, false)) }
            var speedLimit by remember { mutableStateOf(sharedPrefs.getInt(AppConfig.KEY_SPEED_LIMIT, 1024).toFloat()) }
            
            var installedApps by remember { mutableStateOf<List<AppUiInfo>>(emptyList()) }
            var searchQuery by remember { mutableStateOf("") }
            val selectedPackages = remember { mutableStateOf(AppConfig.getTargetApps(context)) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
                    val list = mutableListOf<AppUiInfo>()
                    for (packageInfo in packages) {
                        if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
                            val name = packageInfo.applicationInfo.loadLabel(pm).toString()
                            val icon = packageInfo.applicationInfo.loadIcon(pm)
                            val pName = packageInfo.packageName
                            list.add(AppUiInfo(name, pName, icon, selectedPackages.value.contains(pName)))
                        }
                    }
                    list.sortBy { it.appName }
                    withContext(Dispatchers.Main) {
                        installedApps = list
                    }
                }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(300.dp).fillMaxHeight()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp).background(accentBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier.size(70.dp).clip(CircleShape).background(Color(0xFF03045E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("4G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("محدد السرعة", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("الإصدار 5.0", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val menuItems = listOf(
                            "✨ احصل على النسخة المدفوعة" to Color(0xFFF77F00),
                            "📊 الإحصائيات المتقدمة" to Color.Black,
                            "📈 الرسم البياني" to Color.Black,
                            "🌐 لغة التطبيق" to Color.Black,
                            "📱 تطبيقاتنا" to Color.Black,
                            "❓ ماذا نقدم" to Color.Black
                        )

                        menuItems.forEach { (title, color) ->
                            // ✅ تم التعديل هنا إلى Divider ليتوافق مع إصدار مشروعك
                            Divider(color = Color.LightGray.copy(alpha = 0.3f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { drawerState.close() }
                                        Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = title, color = color, fontSize = 16.sp, fontWeight = if(color != Color.Black) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                            Text("محدد السرعة", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor).padding(16.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(120.dp).clip(CircleShape).background(if (isVpnEnabled) successGreen.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val speedDisplay = if (speedLimit >= 1000) "${String.format("%.1f", speedLimit / 1000f)} Mbps" else "${speedLimit.toInt()} Kbps"
                                    Text(text = speedDisplay, color = if (isVpnEnabled) successGreen else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                                    Text(text = if (isVpnEnabled) "الحد الأقصى نشط" else "الخدمة متوقفة", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "📥 التنزيل (DL)", color = lightBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    val dlSpeed = if (speedLimit >= 1000) "${String.format("%.1f", (speedLimit * 0.85f) / 1000f)} Mbps" else "${(speedLimit * 0.85f).toInt()} Kbps"
                                    Text(text = if (isVpnEnabled) dlSpeed else "0.0 Kbps", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                                Box(modifier = Modifier.width(1.dp).height(30.dp).background(Color.Gray.copy(alpha = 0.3f)))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "📤 التحميل (UL)", color = primaryPurple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    val ulSpeed = if (speedLimit >= 1000) "${String.format("%.1f", (speedLimit * 0.50f) / 1000f)} Mbps" else "${(speedLimit * 0.50f).toInt()} Kbps"
                                    Text(text = if (isVpnEnabled) ulSpeed else "0.0 Kbps", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor).padding(16.dp)) {
                            Text(text = "اسحب لتحديد سقف السرعة الإجمالية:", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Slider(
                                value = speedLimit,
                                onValueChange = { newValue ->
                                    speedLimit = newValue
                                    sharedPrefs.edit().putInt(AppConfig.KEY_SPEED_LIMIT, newValue.toInt()).apply()
                                    if (isVpnEnabled) {
                                        val intent = Intent(this@MainActivity, LocalVpnService::class.java).apply { action = "START" }
                                        startService(intent)
                                    }
                                },
                                valueRange = 100f..30000f,
                                colors = SliderDefaults.colors(thumbColor = primaryPurple, activeTrackColor = primaryPurple, inactiveTrackColor = Color.Gray.copy(alpha = 0.3f))
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(cardColor).padding(16.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("ابحث عن تطبيق...", color = Color.Gray) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(text = "التطبيقات المتاحة للتحكم:", color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))

                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                val filteredApps = installedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
                                items(filteredApps) { app ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Switch(
                                            checked = app.isChecked,
                                            onCheckedChange = { isChecked ->
                                                app.isChecked = isChecked
                                                val currentSet = selectedPackages.value.toMutableSet()
                                                if (isChecked) currentSet.add(app.packageName) else currentSet.remove(app.packageName)
                                                
                                                selectedPackages.value = currentSet
                                                AppConfig.saveTargetApps(context, currentSet)
                                                
                                                if (isVpnEnabled) {
                                                    val intent = Intent(context, LocalVpnService::class.java).apply { action = "START" }
                                                    context.startService(intent)
                                                }
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = successGreen, checkedTrackColor = successGreen.copy(alpha = 0.5f))
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                                Text(text = app.appName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Text(text = app.packageName, color = Color.Gray, fontSize = 10.sp)
                                            }
                                            Image(
                                                bitmap = drawableToBitmap(app.icon).asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                            )
                                        }
                                    }
                                    // ✅ تم التعديل هنا أيضاً إلى Divider ليتوافق مع إصدار مشروعك
                                    Divider(color = Color.Gray.copy(alpha = 0.1f))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (isVpnEnabled) {
                                    val intent = Intent(this@MainActivity, LocalVpnService::class.java).apply { action = "STOP" }
                                    startService(intent)
                                    isVpnEnabled = false
                                    sharedPrefs.edit().putBoolean(AppConfig.KEY_IS_ENABLED, false).apply()
                                } else {
                                    val vpnIntent = VpnService.prepare(this@MainActivity)
                                    if (vpnIntent != null) {
                                        @Suppress("DEPRECATION")
                                        startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        onActivityResult(VPN_REQUEST_CODE, ComponentActivity.RESULT_OK, null)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isVpnEnabled) Color(0xFFEF4444) else primaryPurple),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().height(55.dp)
                        ) {
                            Text(text = if (isVpnEnabled) "إيقاف محدد السرعة" else "تشغيل وتحديد السرعة الآن", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == ComponentActivity.RESULT_OK) {
            val sharedPrefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean(AppConfig.KEY_IS_ENABLED, true).apply()
            
            val intent = Intent(this, LocalVpnService::class.java).apply { action = "START" }
            startService(intent)
            recreate()
        }
    }
}
