package com.aktvhome.launcher.data

/** Which YouTube app Haven should prefer when handing off searches. */
enum class YouTubeSearchPreference(val label: String) {
    AUTO("Automatic (SmartTube first)"),
    SMARTTUBE("SmartTube"),
    YOUTUBE("YouTube"),
}

enum class YouTubeSearchProvider(val label: String, val packageName: String) {
    SMARTTUBE("SmartTube", "org.smarttube.stable"),
    YOUTUBE("YouTube", "com.google.android.youtube.tv"),
}

/** Prefer the saved app, falling back to whichever enabled app is available. */
fun chooseYouTubeSearchProvider(
    preference: YouTubeSearchPreference,
    available: Set<YouTubeSearchProvider>,
): YouTubeSearchProvider? {
    val priority = when (preference) {
        YouTubeSearchPreference.AUTO, YouTubeSearchPreference.SMARTTUBE ->
            listOf(YouTubeSearchProvider.SMARTTUBE, YouTubeSearchProvider.YOUTUBE)
        YouTubeSearchPreference.YOUTUBE ->
            listOf(YouTubeSearchProvider.YOUTUBE, YouTubeSearchProvider.SMARTTUBE)
    }
    return priority.firstOrNull { it in available }
}
