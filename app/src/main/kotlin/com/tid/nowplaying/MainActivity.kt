package com.tid.nowplaying

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

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

                MainScreen(
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

private enum class Tab { Home, Debug, Settings }

@Composable
fun MainScreen(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    var selectedTab by remember { mutableStateOf(Tab.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            when (tab) {
                                Tab.Home -> Icon(Icons.Filled.Home, contentDescription = null)
                                Tab.Debug -> Icon(Icons.Filled.Build, contentDescription = null)
                                Tab.Settings -> Icon(Icons.Filled.Settings, contentDescription = null)
                            }
                        },
                        label = {
                            Text(stringResource(when (tab) {
                                Tab.Home -> R.string.tab_home
                                Tab.Debug -> R.string.tab_debug
                                Tab.Settings -> R.string.tab_settings
                            }))
                        },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                    )
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (selectedTab) {
                Tab.Home -> HomeTab(hasPermission = hasPermission, onRequestPermission = onRequestPermission)
                Tab.Debug -> DebugTab()
                Tab.Settings -> SettingsTab()
            }
        }
    }
}

@Composable
private fun HomeTab(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val nowPlaying by NowPlayingRepository.nowPlaying.collectAsStateWithLifecycle()
    val navInfo    by NavigationRepository.navInfo.collectAsStateWithLifecycle()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        if (!hasPermission) {
            Text(
                text = stringResource(R.string.permission_required),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.btn_grant_access))
            }
        } else {
            val hasAny = navInfo != null || nowPlaying != null
            if (!hasAny) {
                Text(
                    text = stringResource(R.string.nothing_active),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                navInfo?.let { nav ->
                    InfoCard(label = stringResource(R.string.card_navigation)) {
                        Text(
                            text = nav.displayText,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (nav.road.isNotBlank()) {
                            Text(
                                text = nav.road,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        nav.totalTimeSeconds?.let { secs ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = formatRemainingTime(secs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                nowPlaying?.let { media ->
                    if (navInfo != null) Spacer(Modifier.height(16.dp))
                    InfoCard(label = stringResource(R.string.card_media)) {
                        Text(
                            text = media.title ?: stringResource(R.string.unknown_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (media.artist != null) {
                            Text(
                                text = media.artist,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (media.album != null) {
                            Text(
                                text = media.album,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugTab() {
    val context = LocalContext.current
    val app = context.applicationContext as TidApplication
    val scope = rememberCoroutineScope()
    val usbStatus by UsbStatusRepository.status.collectAsStateWithLifecycle()
    val lastSent by SerialLogRepository.lastSent.collectAsStateWithLifecycle()
    val logs by DebugLogRepository.logs.collectAsStateWithLifecycle()
    val flashBusy by FirmwareOperationRepository.busy.collectAsStateWithLifecycle()
    val selectedBoard by SelectedBoardRepository.board.collectAsStateWithLifecycle()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        UsbDebugSection(
            status = usbStatus,
            lastSent = lastSent,
            selectedBoard = selectedBoard,
            flashBusy = flashBusy,
            onBoardSelected = {
                SelectedBoardRepository.set(context, it)
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
            onSendRandom = {
                scope.launch(Dispatchers.IO) {
                    val chars = (1..64).map { (32..126).random().toChar() }.joinToString("")
                    app.serialManager.send("$chars\n")
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

@Composable
private fun UsbDebugSection(
    status: UsbStatus,
    lastSent: String?,
    selectedBoard: ArduinoBoard,
    flashBusy: Boolean,
    onBoardSelected: (ArduinoBoard) -> Unit,
    onCheckVersion: () -> Unit,
    onFlash: () -> Unit,
    onSendRandom: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val (label, detail, color) = when (status) {
        is UsbStatus.Disconnected -> Triple(stringResource(R.string.usb_no_device), null as String?, Color.Gray)
        is UsbStatus.AwaitingPermission -> Triple(stringResource(R.string.usb_awaiting_permission), null as String?, Color(0xFFFFA500))
        is UsbStatus.Connected -> Triple(
            status.deviceName,
            stringResource(R.string.usb_detail_connected, status.vendorId.hex, status.productId.hex),
            Color(0xFF2196F3),
        )
        is UsbStatus.Ready -> Triple(
            status.deviceName,
            stringResource(R.string.usb_detail_ready, status.vendorId.hex, status.productId.hex),
            Color(0xFF4CAF50),
        )
        is UsbStatus.Error -> Triple(stringResource(R.string.usb_error), status.message as String?, Color.Red)
    }

    Text(
        text = stringResource(R.string.usb_section_label),
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
            text = stringResource(R.string.usb_last_sent, lastSent),
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
            Button(onClick = onCheckVersion, enabled = !flashBusy) { Text(stringResource(R.string.btn_check_version)) }
            Button(onClick = onFlash, enabled = !flashBusy) { Text(stringResource(R.string.btn_flash)) }
            Button(onClick = onSendRandom, enabled = !flashBusy) { Text(stringResource(R.string.btn_send_random)) }
            if (flashBusy) {
                Text(
                    text = stringResource(R.string.flash_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (status is UsbStatus.Disconnected || status is UsbStatus.Error) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.btn_grant_usb))
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
            label = { Text(stringResource(R.string.board_selector_label)) },
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
            text = stringResource(R.string.debug_logs_label),
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
                Text(stringResource(R.string.btn_clear), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class LanguageOption(val code: String, val nativeName: String)

private val LANGUAGES = listOf(
    LanguageOption("", ""),      // system default — label resolved via stringResource
    LanguageOption("en", "English"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showConnectionLogs by AppSettings.showConnectionLogsFlow()
        .collectAsStateWithLifecycle(initialValue = AppSettings.showConnectionLogs)
    val enableCycling by AppSettings.enableCyclingFlow()
        .collectAsStateWithLifecycle(initialValue = AppSettings.enableCycling)
    var currentLanguage by remember { mutableStateOf(LocaleManager.getLanguage(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // Language selector
        Text(
            text = stringResource(R.string.settings_language_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        var langExpanded by remember { mutableStateOf(false) }
        val systemDefaultLabel = stringResource(R.string.settings_language_system)
        val currentLabel = LANGUAGES.firstOrNull { it.code == currentLanguage }
            ?.let { if (it.code.isEmpty()) systemDefaultLabel else it.nativeName }
            ?: systemDefaultLabel
        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                LANGUAGES.forEach { lang ->
                    val label = if (lang.code.isEmpty()) systemDefaultLabel else lang.nativeName
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            langExpanded = false
                            if (lang.code != currentLanguage) {
                                currentLanguage = lang.code
                                LocaleManager.setLanguage(context, lang.code)
                                (context as? Activity)?.recreate()
                            }
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Cycling toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_cycling_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_cycling_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enableCycling,
                onCheckedChange = { scope.launch { AppSettings.setEnableCycling(it) } },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Connection logs toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_connection_logs_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.settings_connection_logs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showConnectionLogs,
                onCheckedChange = { scope.launch { AppSettings.setShowConnectionLogs(it) } },
            )
        }
    }
}

@Composable
private fun InfoCard(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun formatRemainingTime(seconds: Long): String {
    val hours   = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours >= 1   -> stringResource(R.string.time_left_hm, hours, minutes)
        minutes >= 1 -> stringResource(R.string.time_left_m, minutes)
        else         -> stringResource(R.string.time_left_less_1min)
    }
}

private val Int.hex get() = "0x%04X".format(this)
