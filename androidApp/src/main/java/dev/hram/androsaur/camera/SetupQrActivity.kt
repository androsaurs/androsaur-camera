package dev.hram.androsaur.camera

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Signature-protected camera-module UI; decoded secrets return only to the trusted caller. */
class SetupQrActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private var service: CameraService? = null
    private var bound = false
    private var visible = false
    private var finished = false
    private var notice by mutableStateOf("Наведите заднюю камеру на QR-код подставки")
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && visible) connect() else notice = "Для чтения QR разрешите камеру"
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!visible) return
            service = (binder as CameraService.LocalBinder).service
            preview.post {
                if (visible && service?.beginSetupQr(preview) { raw ->
                    if (!finished && visible) {
                        finished = true
                        setResult(RESULT_OK, Intent().putExtra("qr", raw)); finish()
                    }
                } == false) notice = "Камера занята съёмкой. Повторите после её завершения"
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; notice = "Камера отключилась. Откройте сканирование снова" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller == null || packageManager.checkSignatures(packageName, caller) != PackageManager.SIGNATURE_MATCH) { finish(); return }
        val wifi = intent.getStringExtra("purpose") == "wifi"
        notice = if (wifi) "Наведите заднюю камеру на QR-код Wi-Fi на роутере или другом телефоне" else "Наведите заднюю камеру на QR-код подставки"
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff63e6a4))) {
                Column(Modifier.fillMaxSize().background(Color(0xff111815)).safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(onClick = { finish() }) { Text("Назад") }
                        Text(if (wifi) "QR Wi-Fi" else "QR подставки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                    }
                    AndroidView(factory = { preview }, modifier = Modifier.weight(1f).fillMaxWidth())
                    Text(notice, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
    override fun onStart() {
        super.onStart(); if (!::preview.isInitialized) return
        visible = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) connect()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun connect() {
        if (bound || !visible) return
        try {
            startForegroundService(Intent(this, CameraService::class.java))
            bound = bindService(Intent(this, CameraService::class.java), connection, BIND_AUTO_CREATE)
        } catch (_: Exception) { notice = "Не удалось открыть камеру" }
    }
    override fun onStop() {
        visible = false
        if (::preview.isInitialized) service?.endSetupQr(preview)
        if (bound) unbindService(connection)
        bound = false; service = null; super.onStop()
    }
}
