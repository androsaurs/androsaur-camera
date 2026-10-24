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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Teaching the camera who is who and trying how well it knows them. The list shows the people taught;
 * "Add" asks a name and takes samples of the face while the person turns their head; "Test" names the face in front of the camera.
 */
class PeopleActivity : ComponentActivity() {
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
        setContent { PeopleScreen(preview, service, notice) { finish() } }
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
        service?.stopPeople(preview)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
}

private val Green = Color(0xff63e6a4)
private val Amber = Color(0xffffb454)
private val Muted = Color(0xffc5cfc9)

private sealed class Screen {
    data object List : Screen()
    class Enroll(val name: String) : Screen()
    data object Test : Screen()
}

@Composable
private fun PeopleScreen(preview: PreviewView, service: CameraService?, notice: String?, back: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var people by remember { mutableStateOf(listOf<Pair<String, Int>>()) }
    var snap by remember { mutableStateOf<FaceId.Snapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh() { people = service?.faceId?.store?.let { s -> s.names().map { it to s.samples(it) } } ?: emptyList() }
    LaunchedEffect(service) { refresh(); while (true) { snap = service?.faceId?.snapshot(); delay(100) } }
    // The camera runs only on the two camera screens.
    LaunchedEffect(screen is Screen.List, service) {
        val s = service ?: return@LaunchedEffect
        if (screen is Screen.List) { s.stopPeople(preview); refresh() }
        else {
            val (code, reply) = s.startPeople(preview)
            if (code != 200) { message = "Не удалось включить камеру: ${reply.optString("error")}"; screen = Screen.List; return@LaunchedEffect }
            when (val now = screen) {
                is Screen.Enroll -> s.faceId.startEnroll(now.name)
                Screen.Test -> s.faceId.setMode(FaceId.Mode.TEST)
                Screen.List -> {}
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Green)) {
        Box(Modifier.fillMaxSize().background(Color(0xff111815)).safeDrawingPadding()) {
            when (val now = screen) {
                Screen.List -> PeopleList(people, notice ?: message, service != null,
                    add = { name -> screen = Screen.Enroll(name) }, test = { screen = Screen.Test },
                    forget = { name -> service?.faceId?.store?.forget(name); refresh() }, back = back)
                is Screen.Enroll -> CameraPane(preview, snap, enrolling = true) {
                    EnrollPanel(snap, save = {
                        val saved = service?.faceId?.finishEnroll() ?: -1
                        message = if (saved > 0) "«${now.name}»: сохранено снимков — $saved" else "Мало снимков для сохранения"
                        screen = Screen.List
                    }, cancel = { service?.faceId?.setMode(FaceId.Mode.IDLE); screen = Screen.List })
                }
                Screen.Test -> CameraPane(preview, snap, enrolling = false) {
                    TestPanel(snap, service) { service?.faceId?.setMode(FaceId.Mode.IDLE); screen = Screen.List }
                }
            }
        }
    }
}

@Composable
private fun PeopleList(people: List<Pair<String, Int>>, notice: String?, ready: Boolean, add: (String) -> Unit, test: () -> Unit, forget: (String) -> Unit, back: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var sure by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Люди", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("Камера запоминает не фотографии, а числа (вектор из 128 чисел) по нескольким снимкам лица. Сначала обучите, потом проверьте.", color = Muted, fontSize = 14.sp)
        notice?.let { Text(it, color = Amber) }
        if (people.isEmpty()) Text("Пока никого нет", color = Color.White)
        people.forEach { (n, count) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(n, color = Color.White, fontSize = 18.sp); Text("снимков: $count", color = Muted, fontSize = 13.sp) }
                if (sure == n) {
                    TextButton(onClick = { forget(n); sure = null }) { Text("Точно удалить", color = Amber) }
                } else TextButton(onClick = { sure = n }) { Text("Удалить") }
            }
        }
        Button(onClick = { name = ""; asking = true }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Добавить человека") }
        OutlinedButton(onClick = test, enabled = ready && people.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Проверка") }
        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Кто это?") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Имя") })
                Text("Если такое имя уже есть, снимки добавятся к нему. Человек должен быть один в кадре.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = PeopleStore.valid(name.trim()), onClick = { asking = false; add(name.trim()) }) { Text("Начать обучение") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Отмена") } },
    )
}

@Composable
private fun CameraPane(preview: PreviewView, snap: FaceId.Snapshot?, enrolling: Boolean, panel: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pictureHeight = minOf(maxWidth * 4 / 3, maxHeight * 0.55f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(pictureHeight).aspectRatio(3f / 4f)) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val face = snap?.face
                    if (face != null && (snap.faceAgeMs in 0..1500)) {
                        val known = snap.who?.name != null
                        val colour = if (enrolling || known) Green else Amber
                        val width = (face.w * size.width).toFloat(); val height = (face.h * size.height).toFloat()
                        // The preview of the front camera is a mirror; the numbers are of the unmirrored image.
                        val centre = Offset(((1 - (face.x + face.w / 2)) * size.width).toFloat(), ((face.y + face.h / 2) * size.height).toFloat())
                        rotate(degrees = (-face.angle).toFloat(), pivot = centre) {
                            drawRect(colour, Offset(centre.x - width / 2, centre.y - height / 2), Size(width, height), style = Stroke(width = 4.dp.toPx()))
                        }
                        // The five points the face is aligned by: pupils, nose tip, mouth corners.
                        for (i in intArrayOf(468, 473, 1, 61, 291)) if (face.points.size > 2 * i + 1)
                            drawCircle(Color.White, 3.dp.toPx(), Offset((1 - face.points[2 * i]) * size.width, face.points[2 * i + 1] * size.height))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { panel() }
        }
    }
}

@Composable
private fun EnrollPanel(snap: FaceId.Snapshot?, save: () -> Unit, cancel: () -> Unit) {
    val e = snap?.enroll
    Text(if (e == null) "Подготовка…" else "Обучение: ${e.name}", color = Color.White, fontSize = 20.sp)
    if (snap?.state == "loading") Text("Загружаю модели…", color = Muted)
    snap?.error?.let { Text(it, color = Amber, fontSize = 13.sp) }
    if (e != null) {
        LinearProgressIndicator(progress = { e.samples.toFloat() / e.target }, modifier = Modifier.fillMaxWidth())
        Text("Снимков: ${e.samples} из ${e.target}", color = Muted)
        Text(e.hint, color = Color.White, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Where the head has been: yaw to the right, pitch down; the ring is the head now.
            Canvas(Modifier.size(110.dp).background(Color(0xff1b2420))) {
                val c = Offset(size.width / 2, size.height / 2)
                drawLine(Color(0xff3a4a43), Offset(0f, c.y), Offset(size.width, c.y)); drawLine(Color(0xff3a4a43), Offset(c.x, 0f), Offset(c.x, size.height))
                fun at(yaw: Double, pitch: Double) = Offset((c.x + yaw / 45 * c.x).toFloat(), (c.y + pitch / 45 * c.y).toFloat())
                e.poses.forEach { drawCircle(Green, 4.dp.toPx(), at(it.first, it.second)) }
                val f = snap.face
                if (f?.yaw != null && f.pitch != null) drawCircle(Color.White, 7.dp.toPx(), at(f.yaw, f.pitch), style = Stroke(2.dp.toPx()))
            }
            Text("Зелёные точки — положения головы, на которых уже сняты образцы. Чем шире разброс, тем надёжнее узнавание.", color = Muted, fontSize = 13.sp)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = save, enabled = e?.ready == true, modifier = Modifier.weight(1f)) { Text("Сохранить") }
        OutlinedButton(onClick = cancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
    }
}

@Composable
private fun TestPanel(snap: FaceId.Snapshot?, service: CameraService?, done: () -> Unit) {
    val who = snap?.who
    val fresh = snap?.face != null && snap.faceAgeMs in 0..1500
    var threshold by remember { mutableStateOf(service?.faceId?.threshold?.toFloat() ?: 0.4f) }
    Text(when {
        snap?.state == "loading" -> "Загружаю модели…"
        !fresh -> "Лицо не найдено"
        who == null -> "Смотрю…"
        who.name != null -> "Это: ${who.name}"
        else -> "Не узнаю"
    }, color = if (who?.name != null && fresh) Green else if (fresh) Amber else Muted, fontSize = 26.sp)
    snap?.error?.let { Text(it, color = Amber, fontSize = 13.sp) }
    who?.scores?.forEach { s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.name, color = Color.White, modifier = Modifier.width(96.dp), maxLines = 1)
            LinearProgressIndicator(progress = { s.similarity.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.weight(1f),
                color = if (s.similarity >= threshold) Green else Amber)
            Text("%.2f".format(s.similarity), color = Muted, modifier = Modifier.width(44.dp))
        }
    }
    Text("Порог узнавания: %.2f (выше — строже)".format(threshold), color = Color.White, fontSize = 14.sp)
    Slider(value = threshold, onValueChange = { threshold = it; service?.faceId?.threshold = it.toDouble() }, valueRange = 0.1f..0.8f)
    snap?.let {
        Text(buildString {
            it.landmarkerMs?.let { ms -> append("поиск лица %.0f мс".format(ms)) }
            it.embedMs?.let { ms -> append(" · вектор %.0f мс".format(ms)) }
            append(" · %.1f кадров/с".format(it.fps))
        }, color = Muted, fontSize = 13.sp)
    }
    OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Назад к списку") }
}
