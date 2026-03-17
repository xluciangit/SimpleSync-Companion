package com.simplesync.companion.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("simplesynccompanion_prefs")

class Prefs(private val context: Context) {

    companion object {
        val SERVER_URL  = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val NOTIF_ON = booleanPreferencesKey("notifications_on")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DIRECT_URL = stringPreferencesKey("direct_url")
        val UPLOADS_PAUSED  = booleanPreferencesKey("uploads_paused")

        @Volatile private var INSTANCE: Prefs? = null
        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Prefs(ctx.applicationContext).also { INSTANCE = it }
        }
    }

    val serverUrl: Flow<String>  = context.dataStore.data.map { it[SERVER_URL] ?: "" }
    val apiKey:    Flow<String>  = context.dataStore.data.map { it[API_KEY]    ?: "" }
    val notifsOn:  Flow<Boolean> = context.dataStore.data.map { it[NOTIF_ON]  ?: true }
    val appTheme:  Flow<String>  = context.dataStore.data.map { it[APP_THEME] ?: "dark" }
    val directUrl: Flow<String>    = context.dataStore.data.map { it[DIRECT_URL]     ?: "" }
    val uploadsPaused: Flow<Boolean> = context.dataStore.data.map { it[UPLOADS_PAUSED] ?: false }

    suspend fun setServerUrl(v: String)  = context.dataStore.edit { it[SERVER_URL]  = v.trimEnd('/') }
    suspend fun setApiKey(v: String)     = context.dataStore.edit { it[API_KEY]     = v.trim() }
    suspend fun setNotifsOn(v: Boolean)  = context.dataStore.edit { it[NOTIF_ON]    = v }
    suspend fun setAppTheme(v: String)   = context.dataStore.edit { it[APP_THEME]   = v }
    suspend fun setDirectUrl(v: String)     = context.dataStore.edit { it[DIRECT_URL]     = v.trimEnd('/') }
    suspend fun setUploadsPaused(v: Boolean) = context.dataStore.edit { it[UPLOADS_PAUSED] = v }

    suspend fun isConfigured(): Boolean {
        val data = context.dataStore.data.first()
        return (data[SERVER_URL] ?: "").isNotEmpty() && (data[API_KEY] ?: "").isNotEmpty()
    }

    fun getThemeBlocking(): String {
        return kotlinx.coroutines.runBlocking {
            context.dataStore.data.first()[APP_THEME] ?: "dark"
        }
    }
}
