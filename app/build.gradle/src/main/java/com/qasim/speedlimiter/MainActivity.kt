package com.qasim.speedlimiter

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qasim.speedlimiter.data.services.LocalVpnService

data class AppItemInfo(
    val name: String,
    val packageName: String,
    var isWifiBlocked: Boolean = false,
    var isMobileBlocked: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            
            MaterialTheme(
                colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NetGuardStyleApp(
                        onStartVpn = { prepareAndStartVpn() },
                        onStopVpn = { stopVpnService() },
                        isDark = isDarkMode,
                        onThemeToggle = { isDarkMode = !isDarkMode }
                    )
                }
            }
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        intent?.let { vpnLauncher.launch(it) } ?: startVpnService()
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, LocalVpnService::class.java)
            startService(intent)
            Toast.makeText(this, "Guard Protection Activated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting VPN Service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(this, LocalVpnService::class.java)
            stopService(intent)
            Toast.makeText(this, "Protection Deactivated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetGuardStyleApp(
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    isDark: Boolean,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    var isVpnRunning by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf(listOf<AppItemInfo>()) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } 
            .map { AppItemInfo(name = it.loadLabel(pm).toString(), packageName = it.packageName) }
            .sortedBy { it.name }
        installedApps = apps
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("NetGuard Speed Limiter", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            actions = {
                IconButton(onClick = onThemeToggle) {
                    Text(if (isDark) "☀️" else "🌙", fontSize = 20.sp)
                }
                Switch(
                    checked = isVpnRunning,
                    onCheckedChange = {
                        isVpnRunning = it
                        if (isVpnRunning) onStartVpn() else onStopVpn()
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            placeholder = { Text("Search installed apps...") },
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        val filteredApps = installedApps.filter {
            it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(filteredApps) { app ->
                var wifiState by remember { mutableStateOf(app.isWifiBlocked) }
                var mobileState by remember { mutableStateOf(app.isMobileBlocked) }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(text = app.packageName, fontSize = 12.sp, color = Color.Gray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { 
                            wifiState = !wifiState
                            app.isWifiBlocked = wifiState
                            LocalVpnService.setAppBlockState(app.packageName, wifiState || mobileState)
                        }) {
                            Text(if (wifiState) "📶❌" else "📶✅", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { 
                            mobileState = !mobileState
                            app.isMobileBlocked = mobileState
                            LocalVpnService.setAppBlockState(app.packageName, wifiState || mobileState)
                        }) {
                            Text(if (mobileState) "🌐❌" else "🌐✅", fontSize = 18.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.Gray.copy(alpha = 0.2f)))
            }
        }
        
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("💼 تطبيقاتنا / Our More Apps")
        }
    }
}
