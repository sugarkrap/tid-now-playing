package com.tid.nowplaying

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun hasNotificationAccess(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return listeners?.contains(packageName) == true
    }
}

@Composable
fun NowPlayingScreen(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val nowPlaying by NowPlayingRepository.nowPlaying.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            if (!hasPermission) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Notification access is required to read media sessions.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRequestPermission) {
                        Text("Grant Access")
                    }
                }
            } else {
                val info = nowPlaying
                if (info == null) {
                    Text(
                        text = "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
            }
        }
    }
}
