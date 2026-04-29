package app.opentune.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Player : Screen("player")
    data object Playlist : Screen("playlist/{playlistId}") {
        fun route(id: String) = "playlist/$id"
    }
    data object Artist : Screen("artist/{artistId}") {
        fun route(id: String) = "artist/$id"
    }
    data object Album : Screen("album/{albumId}") {
        fun route(id: String) = "album/$id"
    }
}
