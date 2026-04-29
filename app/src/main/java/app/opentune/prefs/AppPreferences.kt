package app.opentune.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object AppPreferences {
    val DYNAMIC_COLOR    = booleanPreferencesKey("dynamic_color")
    val AUDIO_QUALITY    = stringPreferencesKey("audio_quality")    // Auto/Low/Medium/High/Best
    val THEME            = stringPreferencesKey("theme")            // System/Dark/Light
    val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
    val SKIP_SILENCE     = booleanPreferencesKey("skip_silence")
    val LYRICS_FONT_SIZE = stringPreferencesKey("lyrics_font_size") // Small/Medium/Large
    val SKIP_DURATION    = intPreferencesKey("skip_duration")       // seconds: 5/10/15/30
    val MAX_CACHE_SIZE   = longPreferencesKey("max_cache_size")     // bytes; 0 = unlimited
}
