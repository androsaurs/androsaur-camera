package dev.hram.androsaur.camera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * A screen to watch the face detectors work: the front camera, a frame per detector over the faces it finds,
 * and buttons for the mode. The same counters are read through GET /faces and the face_lab MCP tool.
 */
class FaceLabActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private var service by mutableStateOf<CameraService?>(null)
    private var bound = false
    private var visible = false
    private var notice by mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && visible) connect() else if (!granted) notice = "Разрешите доступ к камере"
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!visible) return
            service = (binder as CameraService.LocalBinder).service
            preview.post {
                if (!visible) return@post
                val (code, reply) = service?.startFaceLab(preview) ?: return@post
                notice = if (code == 200) null else when (reply.optString("error")) {
                    "camera_busy" -> "Сейчас выполняется съёмка. Вернитесь назад и повторите"
                    "calibration_active" -> "Идёт калибровка. Закончите её и повторите"
                    "lens_unavailable" -> "На телефоне нет фронтальной камеры"
                    else -> "Не удалось включить лабораторию: ${reply.optString("error")}"
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; notice = "Служба камеры остановлена. Откройте экран снова" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContent { FaceLabScreen(preview, service, notice) { finish() } }
    }

    override fun onStart() {
        super.onStart(); visible = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) connect()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun connect() {
        if (bound || !visible) return
        try {
            startForegroundService(Intent(this, CameraService::class.java))
            bound = bindService(Intent(this, CameraService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) notice = "Не удалось подключиться к камере"
        } catch (_: Exception) { notice = "Не удалось запустить камеру. Откройте экран снова" }
    }

    override fun onStop() {
        visible = false
        service?.stopFaceLab(preview)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
}

private val Orange = Color(0xffffb454)
private val Blue = Color(0xff5aa9ff)
private val Green = Color(0xff63e6a4)
private fun colourOf(name: String) = when (name) { "hardware" -> Orange; "mlkit" -> Blue; else -> Green }
private fun titleOf(name: String) = when (name) { "hardware" -> "Чип камеры"; "mlkit" -> "ML Kit"; else -> "MediaPipe" }
private val modes = listOf("A" to "чип камеры", "B" to "чип + ML Kit", "C" to "чип + MediaPipe", "D" to "все три")

@Composable
private fun FaceLabScreen(preview: PreviewView, service: CameraService?, notice: String?, back: () -> Unit) {
    var live by remember { mutableStateOf<FaceLab.Live?>(null) }
    LaunchedEffect(service) {
        while (true) { live = service?.faceLab?.live(); delay(100) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Green)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff111815)).safeDrawingPadding()) {
            // The picture is 3:4 (upright front camera); it takes at most 60% of the height, the rest is for the controls.
            val pictureHeight = minOf(maxWidth * 4 / 3, maxHeight * 0.6f)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(pictureHeight).aspectRatio(3f / 4f)) {
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        live?.detectors?.forEach { d ->
                            if (d.ageMs in 0..1500) d.boxes.forEach { b ->
                                // The preview of the front camera is a mirror; the detectors report the unmirrored image.
                                val left = ((1 - (b.x + b.w)) * size.width).toFloat()
                                val top = (b.y * size.height).toFloat()
                                drawRect(colourOf(d.name), Offset(left, top), Size((b.w * size.width).toFloat(), (b.h * size.height).toFloat()), style = Stroke(width = 4.dp.toPx()))
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notice?.let { Text(it, color = Orange) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        modes.forEach { (id, label) ->
                            val on = live?.mode == id
                            val modifier = Modifier.weight(1f)
                            val click = { service?.faceLab?.switchTo(id); Unit }
                            if (on) Button(onClick = click, modifier = modifier) { Text(id, fontSize = 18.sp) }
                            else OutlinedButton(onClick = click, modifier = modifier) { Text(id, fontSize = 18.sp) }
                        }
                    }
                    Text("Режим ${live?.mode ?: ""}: ${modes.firstOrNull { it.first == live?.mode }?.second ?: ""}", color = Color.White)
                    live?.detectors?.forEach { d -> Readout(d) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { service?.faceLab?.reset() }, modifier = Modifier.weight(1f)) { Text("Сбросить счётчики") }
                        OutlinedButton(onClick = back, modifier = Modifier.weight(1f)) { Text("Назад") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Readout(d: FaceLab.LiveDetector) {
    val colour = colourOf(d.name)
    val fresh = d.ageMs in 0..1500
    val first = if (fresh) d.boxes.firstOrNull() else null
    val where = first?.let {
        // Seen as in the mirror preview: the person's right is on the right of the screen.
        val cx = 1 - (it.x + it.w / 2); val cy = it.y + it.h / 2
        listOf(if (cx < 0.4) "слева" else if (cx > 0.6) "справа" else "по центру",
            if (cy < 0.4) "выше" else if (cy > 0.6) "ниже" else "на высоте центра").joinToString(", ") + " · размер ${Math.round(it.w * 100)}%"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (d.state == "off") Color.Gray else colour))
            Spacer(Modifier.width(8.dp))
            Text(titleOf(d.name), color = if (d.state == "off") Color.Gray else colour)
            Spacer(Modifier.width(8.dp))
            Text(when {
                d.state == "off" -> "выключен в этом режиме"
                d.state != "ready" -> d.state
                !fresh -> "нет данных"
                d.boxes.isEmpty() -> "лиц нет"
                else -> "лиц: ${d.boxes.size}"
            }, color = Color.White)
        }
        if (d.state != "off") Text(buildString {
            where?.let { append(it).append("\n") }
            d.avgMs?.let { append("%.0f мс на кадр · ".format(it)) }
            append("%.1f результатов/с · лицо в %d%% кадров".format(d.fps, d.withFacePercent))
        }, color = Color(0xffc5cfc9), fontSize = 13.sp)
    }
}
