package dev.hram.mobileledger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun FocusPanel(service: CameraService?, modifier: Modifier = Modifier) {
    var state by remember(service) { mutableStateOf(service?.focusStatus()) }
    var busy by remember(service) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    LaunchedEffect(service) {
        while (service != null) {
            state = service.focusStatus(); busy = service.status().optBoolean("busy")
            delay(400)
        }
    }
    val disabled = service == null || busy || state?.optString("state") in listOf("focusing", "restoring", "locking")
    fun command(action: String) {
        val result = service?.focus(action) ?: return
        if (result.first == 202) { state = result.second; error = null }
        else error = when (result.second.optString("error")) {
            "camera_busy" -> "Дождитесь завершения снимка"
            "focus_busy" -> "Фокус уже настраивается"
            "focus_unsupported" -> "Камера не поддерживает автофокус в этой области"
            "focus_lock_unavailable" -> "Фиксация текущего положения недоступна"
            else -> "Камера ещё не готова. Повторите команду"
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { command("focus") }, enabled = !disabled, modifier = Modifier.fillMaxWidth()) {
            Text("Навести и зафиксировать")
        }
        TextButton(onClick = { command("lock") }, enabled = !disabled && state?.optBoolean("manualLock") == true,
            modifier = Modifier.fillMaxWidth()) { Text("Зафиксировать текущий") }
        Text(error ?: state?.optString("message") ?: "Подключаем управление фокусом…", style = MaterialTheme.typography.bodySmall)
    }
}
