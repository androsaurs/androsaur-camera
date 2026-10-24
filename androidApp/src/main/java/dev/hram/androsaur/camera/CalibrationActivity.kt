package dev.hram.androsaur.camera

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.hram.androsaur.camera.calibration.*
import kotlinx.coroutines.delay
import kotlin.math.abs

class CalibrationActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private var service by mutableStateOf<CameraService?>(null)
    private var bound = false
    private var visible = false
    private var frame by mutableStateOf(CalibrationFrame())
    private var lastFrame by mutableLongStateOf(0L)
    private var notice by mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && visible) connect() else if (!granted) notice = "Разрешите доступ к камере для калибровки"
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!visible) return
            service = (binder as CameraService.LocalBinder).service
            preview.post {
                if (!visible) return@post
                if (service?.beginCalibration(preview) { next ->
                    frame = next; lastFrame = SystemClock.elapsedRealtime(); notice = null
                } == false) notice = "Сейчас выполняется съёмка. Вернитесь назад и повторите после её завершения"
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; frame = CalibrationFrame(); notice = "Служба камеры остановлена. Откройте калибровку снова"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    view.performClick()
                    val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
                    val left = (width - frame.width * scale) / 2
                    val top = (height - frame.height * scale) / 2
                    if (event.x in left..(width - left) && event.y in top..(height - top))
                        service?.focus("focus", point = meteringPointFactory.createPoint(event.x, event.y))
                }
                true
            }
        }
        setContent {
            var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(Unit) { while (true) { delay(200); tick = SystemClock.elapsedRealtime() } }
            val fresh = lastFrame != 0L && tick - lastFrame < 1000
            CalibrationScreen(preview, if (fresh) frame else CalibrationFrame(), notice, service, { finish() })
        }
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
        service?.endCalibration(preview)
        if (bound) unbindService(connection)
        bound = false; service = null; frame = CalibrationFrame(); lastFrame = 0
        super.onStop()
    }
}

private val CalibrationGreen = Color(0xff63e6a4)
private val CalibrationAmber = Color(0xffffd080)

@Composable
@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
private fun CalibrationScreen(preview: PreviewView, frame: CalibrationFrame, notice: String?, service: CameraService?, back: () -> Unit) {
    val reading = frame.reading
    val good = frame.stable
    val colour = if (good) CalibrationGreen else CalibrationAmber
    val instructions = buildList {
        if (reading != null && !reading.aligned) {
            if (abs(reading.liftRight) > frame.tolerance)
                add(if (reading.liftRight > 0) "Приподнимите правый край телефона" else "Приподнимите левый край телефона")
            if (abs(reading.liftBottom) > frame.tolerance)
                add(if (reading.liftBottom > 0) "Приподнимите нижний край телефона" else "Приподнимите верхний край телефона")
            if (isEmpty()) add("Проверьте, что метки образуют прямоугольник и подложка плоская")
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = CalibrationGreen)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff111815)).safeDrawingPadding()) {
            // This reservation depends only on the window, never on status text or detected markers.
            val panelHeight = (maxHeight * 0.30f).coerceIn(136.dp, 240.dp)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back) { Text("Назад") }
                    Text("Калибровка", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
                    Text("${frame.markers.size} / 4", color = Color.White)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val source = frame.transform
                        val target = preview.outputTransform
                        if (source != null && target != null && frame.markers.isNotEmpty()) {
                            val entries = CalibrationCorner.entries.filter { it in frame.markers }
                            val coordinates = entries.flatMap { corner ->
                                val point = frame.markers.getValue(corner)
                                listOf((point.x * frame.width).toFloat(), (point.y * frame.height).toFloat())
                            }.toFloatArray()
                            try {
                                CoordinateTransform(source, target).mapPoints(coordinates)
                                val points = entries.indices.associate { entries[it] to Offset(coordinates[it * 2], coordinates[it * 2 + 1]) }
                                if (points.size == 4) {
                                    val path = Path().apply {
                                        val start = points.getValue(CalibrationCorner.TL); moveTo(start.x, start.y)
                                        CalibrationCorner.entries.drop(1).forEach { val p = points.getValue(it); lineTo(p.x, p.y) }
                                        close()
                                    }
                                    drawPath(path, colour.copy(alpha = 0.08f))
                                    drawPath(path, colour, style = Stroke(2.dp.toPx()))
                                }
                                points.values.forEach { point ->
                                    drawCircle(Color.Black.copy(alpha = 0.7f), 9.dp.toPx(), point)
                                    drawCircle(colour, 6.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                                }
                            } catch (_: IllegalArgumentException) { /* The preview transform is changing during rotation. */ }
                        }
                    }
                    // Overlay children never participate in measuring the PreviewView.
                    if (reading != null && !reading.aligned) {
                        if (abs(reading.liftRight) > frame.tolerance) {
                            val right = reading.liftRight > 0
                            CalibrationEdgeHint(if (right) "Приподнимите\nправый край" else "Приподнимите\nлевый край",
                                Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart))
                        }
                        if (abs(reading.liftBottom) > frame.tolerance) {
                            val bottom = reading.liftBottom > 0
                            CalibrationEdgeHint(if (bottom) "Приподнимите\nнижний край" else "Приподнимите\nверхний край",
                                Modifier.align(if (bottom) Alignment.BottomCenter else Alignment.TopCenter))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().height(panelHeight).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Допуск: %.1f%%".format(frame.tolerance * 100),
                        color = Color(0xffb9c9c0), style = MaterialTheme.typography.bodySmall)
                    FocusPanel(service)
                    Text(when {
                        notice != null -> "Калибровка недоступна"
                        frame.message != null -> "Проверьте метки"
                        frame.markers.size < 4 -> "Покажите все четыре QR-кода"
                        good -> "Камера выставлена"
                        reading?.aligned == true -> "Удерживайте телефон неподвижно…"
                        else -> "Выровняйте телефон"
                    }, style = MaterialTheme.typography.titleLarge, color = colour)
                    Text(notice ?: frame.message ?: when {
                        frame.markers.size < 4 -> "В кадре должны быть TL, TR, BR и BL. Если коды не помещаются — поднимите телефон выше."
                        good -> "Зафиксируйте крепление. Зелёная рамка означает, что перекос в пределах допуска."
                        reading?.aligned == true -> "Проверяем устойчивость положения"
                        else -> instructions.joinToString("\n")
                    }, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text("Подсказки у краёв относятся к телефону. Двигайте крепление понемногу.",
                        color = Color(0xffb9c9c0), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CalibrationEdgeHint(text: String, modifier: Modifier) {
    Surface(modifier = modifier.padding(12.dp).widthIn(max = 156.dp),
        shape = RoundedCornerShape(8.dp), color = Color(0xee111815)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = CalibrationAmber, style = MaterialTheme.typography.labelLarge)
    }
}
