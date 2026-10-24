package dev.hram.androsaur.camera

import android.Manifest
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.os.IBinder
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.hram.androsaur.camera.lens.Lens
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.NetworkInterface
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Камера остановлена")
    private var permissionRequested = false
    private var manuallyStopped = false
    private var cameraService by mutableStateOf<CameraService?>(null)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { cameraService = (binder as CameraService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { cameraService = null }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else status = "Разрешите доступ к камере"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff24654a), background = Color(0xfff5f3ec))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ANDROSAUR", fontSize = 14.sp)
                        Text("Камера", style = MaterialTheme.typography.headlineLarge)
                        LensLine(cameraService)
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        FocusPanel(cameraService)
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Калибровка") }
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@MainActivity, FaceLabActivity::class.java))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Лаборатория лиц") }
                        Button(onClick = { requestStart() }, modifier = Modifier.fillMaxWidth()) { Text("Запустить камеру") }
                        OutlinedButton(onClick = {
                            manuallyStopped = true
                            disconnect()
                            stopService(Intent(this@MainActivity, CameraService::class.java))
                            status = "Камера остановлена"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                        Text("Адрес пульта в этой сети", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(address(), fontSize = 16.sp) }
                        Text("Подключение запускается автоматически. Камерой управляют по сети (HTTP и MCP): другие модули Androsaur, агент или сканер paper-ledger; экран телефона можно выключить.")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (manuallyStopped) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (!permissionRequested) {
            permissionRequested = true
            requestStart()
        }
    }

    private fun requestStart() {
        val requested = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }
    private fun startCamera() {
        manuallyStopped = false
        try {
            startForegroundService(Intent(this, CameraService::class.java))
            if (!bound) bound = bindService(Intent(this, CameraService::class.java), connection, Context.BIND_AUTO_CREATE)
            status = "Камера включена · управление по сети"
        } catch (_: IllegalStateException) {
            status = "Android отложил запуск. Откройте приложение снова"
        } catch (_: SecurityException) {
            status = "Разрешите приложению доступ к камере"
        }
    }
    private fun disconnect() {
        if (bound) unbindService(connection)
        bound = false; cameraService = null
    }
    override fun onStop() { disconnect(); super.onStop() }
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

/** The camera in use; it can be changed through the API at any time, so it is re-read while the screen is open. */
@Composable
private fun LensLine(service: CameraService?) {
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(service) {
        while (true) {
            shown = service?.lens?.let { if (it == Lens.FRONT) "фронтальная" else "задняя" } ?: ""
            delay(1000)
        }
    }
    Text(if (shown.isEmpty()) "Камера: сервис не запущен" else "Камера: $shown")
}
