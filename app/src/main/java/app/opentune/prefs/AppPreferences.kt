package app.opentune.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object AppPreferences {
    val DYNAMIC_COLOR  = booleanPreferencesKey("dynamic_color")
    val AUDIO_QUALITY  = stringPreferencesKey("audio_quality")
}
