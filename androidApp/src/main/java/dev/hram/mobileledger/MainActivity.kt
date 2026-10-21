package dev.hram.mobileledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.NetworkInterface
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Камера остановлена")
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else status = "Разрешите доступ к камере"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff24654a), background = Color(0xfff5f3ec))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(28.dp).statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("PAPER / LEDGER", fontSize = 14.sp)
                        Text("Документ-камера", style = MaterialTheme.typography.headlineLarge)
                        Text("Прототип · Redmi 12\nСнимками управляйте с компьютера.")
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { requestStart() }, modifier = Modifier.fillMaxWidth()) { Text("Запустить камеру") }
                        OutlinedButton(onClick = {
                            stopService(Intent(this@MainActivity, CameraService::class.java))
                            status = "Камера остановлена"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                        Text("Адрес пульта в этой сети", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(address(), fontSize = 16.sp) }
                        Text("После запуска экран можно выключить. Если Android остановил службу, откройте приложение и запустите её снова.")
                    }
                }
            }
        }
    }

    private fun requestStart() {
        val requested = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }
    private fun startCamera() {
        startForegroundService(Intent(this, CameraService::class.java))
        status = "Служба запущена · откройте пульт на компьютере"
    }
    private fun address(): String {
        val ip = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress ?: "IP-телефона"
        return "http://$ip:8088/#${accessToken()}"
    }
    internal fun accessToken(): String = token(this)
    companion object {
        fun token(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("camera", MODE_PRIVATE)
            return prefs.getString("access_token", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("access_token", it).apply()
            }
        }
    }
}
