package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pylaunch_settings")

data class EditorSettings(
    val isDarkTheme: Boolean = true,
    val fontSizeSp: Int = 14,
    val tabSize: Int = 4,
    val wordWrap: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FONT_SIZE_SP = intPreferencesKey("font_size_sp")
        val TAB_SIZE = intPreferencesKey("tab_size")
        val WORD_WRAP = booleanPreferencesKey("word_wrap")
    }

    val settingsFlow: Flow<EditorSettings> = context.settingsDataStore.data.map { prefs ->
        EditorSettings(
            isDarkTheme = prefs[Keys.DARK_THEME] ?: true,
            fontSizeSp = (prefs[Keys.FONT_SIZE_SP] ?: 14).coerceIn(11, 22),
            tabSize = if ((prefs[Keys.TAB_SIZE] ?: 4) == 2) 2 else 4,
            wordWrap = prefs[Keys.WORD_WRAP] ?: false
        )
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun setFontSize(sizeSp: Int) {
        context.settingsDataStore.edit { it[Keys.FONT_SIZE_SP] = sizeSp.coerceIn(11, 22) }
    }

    suspend fun setTabSize(spaces: Int) {
        context.settingsDataStore.edit { it[Keys.TAB_SIZE] = if (spaces == 2) 2 else 4 }
    }

    suspend fun setWordWrap(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WORD_WRAP] = enabled }
    }
}
