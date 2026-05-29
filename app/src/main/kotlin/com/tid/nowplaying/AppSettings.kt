package com.tid.nowplaying

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val KEY_SHOW_CONNECTION_LOGS = booleanPreferencesKey("show_connection_logs")
private val KEY_ENABLE_CYCLING = booleanPreferencesKey("enable_cycling")

object AppSettings {
    // Cached values so service code can read settings synchronously from any thread.
    @Volatile var showConnectionLogs: Boolean = false
        private set
    @Volatile var enableCycling: Boolean = false
        private set

    private lateinit var store: DataStore<Preferences>

    fun init(context: Context) {
        store = context.applicationContext.dataStore
        val snapshot = runBlocking { store.data.first() }
        showConnectionLogs = snapshot[KEY_SHOW_CONNECTION_LOGS] ?: false
        enableCycling = snapshot[KEY_ENABLE_CYCLING] ?: false
    }

    fun showConnectionLogsFlow(): Flow<Boolean> =
        store.data.map { it[KEY_SHOW_CONNECTION_LOGS] ?: false }

    fun enableCyclingFlow(): Flow<Boolean> =
        store.data.map { it[KEY_ENABLE_CYCLING] ?: false }

    suspend fun setShowConnectionLogs(enabled: Boolean) {
        store.edit { it[KEY_SHOW_CONNECTION_LOGS] = enabled }
        showConnectionLogs = enabled
    }

    suspend fun setEnableCycling(enabled: Boolean) {
        store.edit { it[KEY_ENABLE_CYCLING] = enabled }
        enableCycling = enabled
    }

    internal fun updateCache(showConnectionLogs: Boolean) {
        this.showConnectionLogs = showConnectionLogs
    }
}
