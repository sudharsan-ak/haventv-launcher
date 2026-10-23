package com.aktvhome.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/** Collection icons, keyed by the name stored in the layout. Order is the picker's order. */
object CollectionIcons {

    val all: List<Pair<String, ImageVector>> = listOf(
        // The built-in collections' defaults first.
        "tv" to Icons.Rounded.Tv,
        "basketball" to Icons.Rounded.SportsBasketball,
        "cable" to Icons.Rounded.Cable,
        "play" to Icons.Rounded.SmartDisplay,
        "wrench" to Icons.Rounded.Build,
        "chip" to Icons.Rounded.Memory,
        "hdmi" to Icons.Rounded.SettingsInputHdmi,
        "dots" to Icons.Rounded.Apps,
        // Extras for collections you make.
        "star" to Icons.Rounded.Star,
        "heart" to Icons.Rounded.Favorite,
        "music" to Icons.Rounded.MusicNote,
        "movie" to Icons.Rounded.Movie,
        "gaming" to Icons.Rounded.SportsEsports,
        "kids" to Icons.Rounded.ChildCare,
        "news" to Icons.Rounded.Newspaper,
        "fitness" to Icons.Rounded.FitnessCenter,
        "food" to Icons.Rounded.Restaurant,
        "travel" to Icons.Rounded.Flight,
        "education" to Icons.Rounded.School,
        "shopping" to Icons.Rounded.ShoppingBag,
        "photos" to Icons.Rounded.Photo,
        "home" to Icons.Rounded.Home,
        "cloud" to Icons.Rounded.Cloud,
        "folder" to Icons.Rounded.Folder,
    )

    private val byKey = all.toMap()

    fun of(key: String): ImageVector = byKey[key] ?: Icons.Rounded.Star
}
