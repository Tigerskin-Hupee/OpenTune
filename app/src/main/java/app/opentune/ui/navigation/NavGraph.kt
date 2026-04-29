package app.opentune.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.opentune.ui.screens.*

@Composable
fun OpenTuneNavGraph(navController: NavHostController, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    NavHost(navController = navController, startDestination = Screen.Home.route, modifier = modifier) {
        composable(Screen.Home.route) { HomeScreen(navController) }
        composable(Screen.Search.route) { SearchScreen(navController) }
        composable(Screen.Library.route) { LibraryScreen(navController) }
        composable(Screen.Settings.route) { SettingsScreen() }
        composable(Screen.Player.route) { PlayerScreen(navController) }
        composable(
            route = Screen.Playlist.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { backStack ->
            PlaylistScreen(
                playlistId = backStack.arguments?.getString("playlistId") ?: return@composable,
                navController = navController,
            )
        }
        composable(
            route = Screen.Artist.route,
            arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
        ) { backStack ->
            ArtistScreen(
                artistId = backStack.arguments?.getString("artistId") ?: return@composable,
                navController = navController,
            )
        }
        composable(
            route = Screen.Album.route,
            arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
        ) { backStack ->
            AlbumScreen(
                albumId = backStack.arguments?.getString("albumId") ?: return@composable,
                navController = navController,
            )
        }
    }
}
