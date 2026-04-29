package app.opentune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.media3.common.util.UnstableApi
import app.opentune.playback.MusicService
import app.opentune.prefs.AppPreferences
import app.opentune.ui.navigation.OpenTuneNavGraph
import app.opentune.ui.navigation.Screen
import app.opentune.ui.theme.OpenTuneTheme
import app.opentune.ui.viewmodels.PlayerController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var playerController: PlayerController

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playerController.setBinder(service as? MusicService.Binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playerController.setBinder(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start MusicService and bind for direct ExoPlayer access (ViTune pattern).
        val bindIntent = Intent(this, MusicService::class.java).apply {
            action = MusicService.BINDER_ACTION
        }
        startService(bindIntent)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val prefs by dataStore.data.collectAsState(initial = null)
            val dynamicColor = prefs?.get(AppPreferences.DYNAMIC_COLOR) ?: true
            val themePref    = prefs?.get(AppPreferences.THEME) ?: "System"
            val isDark = when (themePref) {
                "Dark"  -> true
                "Light" -> false
                else    -> isSystemInDarkTheme()
            }
            OpenTuneTheme(darkTheme = isDark, dynamicColor = dynamicColor) {
                OpenTuneApp()
            }
        }
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

@Composable
private fun OpenTuneApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val bottomNavItems = listOf(
        Triple(Screen.Home.route, Icons.Default.Home, R.string.home),
        Triple(Screen.Search.route, Icons.Default.Search, R.string.search),
        Triple(Screen.Library.route, Icons.Default.LibraryMusic, R.string.library),
        Triple(Screen.Settings.route, Icons.Default.Settings, R.string.settings),
    )

    val showBottomBar = currentRoute in bottomNavItems.map { it.first }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { (route, icon, labelRes) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        OpenTuneNavGraph(
            navController = navController,
            modifier = Modifier.padding(padding),
        )
    }
}
