package com.tid.nowplaying

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled by LifecycleEventEffect on resume */ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
            Log.d("MainActivity", "USB attached while running: ${device?.productName}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
            Log.d("MainActivity", "Launched via USB attach: ${device?.productName}")
        }
        requestPostNotificationsIfNeeded()
        setContent {
            MaterialTheme {
                var hasPermission by remember { mutableStateOf(hasNotificationAccess()) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    hasPermission = hasNotificationAccess()
                }

                NowPlayingScreen(
                    hasPermission = hasPermission,
                    onRequestPermission = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return listeners?.contains(packageName) == true
    }
}

@Composable
fun NowPlayingScreen(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TidApplication
    val scope = rememberCoroutineScope()
    val nowPlaying by NowPlayingRepository.nowPlaying.collectAsStateWithLifecycle()
    val usbStatus by UsbStatusRepository.status.collectAsStateWithLifecycle()
    val lastSent by SerialLogRepository.lastSent.collectAsStateWithLifecycle()
    val logs by DebugLogRepository.logs.collectAsStateWithLifecycle()
    val flashBusy by FirmwareOperationRepository.busy.collectAsStateWithLifecycle()
    var selectedBoard by remember { mutableStateOf(ArduinoBoard.NANO) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (!hasPermission) {
                Text(
                    text = "Notification access is required to read media sessions.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRequestPermission) {
                    Text("Grant Access")
                }
            } else {
                val info = nowPlaying
                if (info == null) {
                    Text(
                        text = "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Text(
                        text = info.title ?: "Unknown title",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    if (info.artist != null) {
                        Text(
                            text = info.artist,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (info.album != null) {
                        Text(
                            text = info.album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            UsbDebugSection(
                status = usbStatus,
                lastSent = lastSent,
                selectedBoard = selectedBoard,
                flashBusy = flashBusy,
                onBoardSelected = {
                    selectedBoard = it
                    SelectedBoardRepository.set(it)
                },
                onCheckVersion = {
                    scope.launch(Dispatchers.IO) {
                        FirmwareChecker(app.serialManager, context).queryVersion()
                    }
                },
                onFlash = {
                    scope.launch(Dispatchers.IO) {
                        FirmwareChecker(app.serialManager, context).flash(selectedBoard)
                    }
                },
                onRequestPermission = { requestUsbPermission(context) },
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            DebugLogSection(logs)
        }
    }
}

@Composable
private fun UsbDebugSection(
    status: UsbStatus,
    lastSent: String?,
    selectedBoard: ArduinoBoard,
    flashBusy: Boolean,
    onBoardSelected: (ArduinoBoard) -> Unit,
    onCheckVersion: () -> Unit,
    onFlash: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val (label, detail, color) = when (status) {
        is UsbStatus.Disconnected -> Triple("No USB device", null, Color.Gray)
        is UsbStatus.AwaitingPermission -> Triple("Awaiting permission…", null, Color(0xFFFFA500))
        is UsbStatus.Connected -> Triple(
            status.deviceName,
            "${status.vendorId.hex}:${status.productId.hex} — connected, sending…",
            Color(0xFF2196F3),
        )
        is UsbStatus.Ready -> Triple(
            status.deviceName,
            "${status.vendorId.hex}:${status.productId.hex} — ready",
            Color(0xFF4CAF50),
        )
        is UsbStatus.Error -> Triple("USB error", status.message, Color.Red)
    }

    Text(
        text = "OTG / Serial",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color)
    if (detail != null) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!lastSent.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "→ $lastSent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    BoardSelector(selectedBoard = selectedBoard, onBoardSelected = onBoardSelected)
    if (status is UsbStatus.Ready || status is UsbStatus.Connected) {
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onCheckVersion, enabled = !flashBusy) { Text("Check version") }
            Button(onClick = onFlash, enabled = !flashBusy) { Text("Flash now") }
            if (flashBusy) {
                Text(
                    text = "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (status is UsbStatus.Disconnected || status is UsbStatus.Error) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant USB Permission")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSelector(selectedBoard: ArduinoBoard, onBoardSelected: (ArduinoBoard) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedBoard.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Target board") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ArduinoBoard.entries.forEach { board ->
                DropdownMenuItem(
                    text = { Text(board.displayName) },
                    onClick = {
                        onBoardSelected(board)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun DebugLogSection(logs: List<LogEntry>) {
    Column {
        Text(
            text = "Debug logs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            items(logs) { entry ->
                val color = when (entry.level) {
                    LogLevel.E -> Color.Red
                    LogLevel.W -> Color(0xFFFFA500)
                    else -> Color.Gray
                }
                Text(
                    text = "${entry.timestamp} [${entry.tag}] ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { DebugLogRepository.clear() },
                modifier = Modifier.height(32.dp),
            ) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val Int.hex get() = "0x%04X".format(this)
