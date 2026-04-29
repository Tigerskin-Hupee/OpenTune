package app.opentune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.opentune.ui.navigation.OpenTuneNavGraph
import app.opentune.ui.navigation.Screen
import app.opentune.ui.theme.OpenTuneTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenTuneTheme {
                OpenTuneApp()
            }
        }
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
