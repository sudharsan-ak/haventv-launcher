package com.aktvhome.launcher.ui.settings

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A single image or accessible folder, never an arbitrary path supplied by another app. */
internal data class TvWallpaperEntry(
    val name: String,
    val folder: File? = null,
    val image: File? = null,
    val mediaUri: Uri? = null,
    val size: Long = 0L,
) {
    val isFolder get() = folder != null
}

/**
 * A TV-first fallback for devices without DocumentsUI / ACTION_OPEN_DOCUMENT handlers.
 * If Haven has All files access, browse internal storage and any mounted USB volumes.
 * Otherwise show images published to the Android media library (with user permission).
 * No external file manager or document picker needs to be installed.
 */
@Composable
internal fun WallpaperTvImageBrowser(
    context: Context,
    colors: ThemeColors,
    permissionRevision: Int,
    onRequestPhotoAccess: () -> Unit,
    onPick: (TvWallpaperEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var folder by remember { mutableStateOf<File?>(null) }
    var volumeRoot by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<TvWallpaperEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val canBrowseFiles = Environment.isExternalStorageManager()
    val canReadPhotos = hasMediaPermission(context)

    LaunchedEffect(folder, canBrowseFiles, permissionRevision) {
        loading = true
        error = ""
        entries = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    canBrowseFiles && folder == null -> mountedVolumes(context)
                    canBrowseFiles && folder != null -> imagesAndFolders(folder!!)
                    canReadPhotos -> mediaImages(context)
                    else -> emptyList()
                }
            }.getOrElse {
                error = "Couldn't read the folder. Choose another location or check storage access."
                emptyList()
            }
        }
        loading = false
    }

    fun backOneLevel() {
        val selected = folder
        when {
            selected == null -> onDismiss()
            selected == volumeRoot -> { folder = null; volumeRoot = null }
            else -> {
                val parent = selected.parentFile
                if (parent == null || !parent.exists()) {
                    folder = null; volumeRoot = null
                } else folder = parent
            }
        }
    }

    val first = remember(folder, entries) { FocusRequester() }
    HavenOverlay(dim = STACKED_DIM, onDismiss = { backOneLevel() }) {
        Column(
            Modifier.align(Alignment.Center).width(570.dp).fillMaxHeight(0.88f)
                .glassPanel(colors, androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    alpha = STACKED_GLASS)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Eyebrow("Wallpaper / My photo", colors)
            Text("Choose from TV", color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    canBrowseFiles && folder == null -> "Choose internal storage or a USB drive."
                    canBrowseFiles -> folder?.absolutePath ?: ""
                    else -> "Photos available in the TV's Android media library."
                },
                color = colors.inkSoft, fontSize = 11.sp, maxLines = 2,
            )
            if (canBrowseFiles && folder != null) {
                Pill("‹  Up one folder", colors, onClick = { backOneLevel() })
            }
            when {
                !canBrowseFiles && !canReadPhotos -> {
                    Text("To show TV photos, grant Haven photo access. You can still use Send from device without this permission.",
                        color = colors.inkSoft, fontSize = 13.sp)
                    Pill("Allow photo access", colors, focusRequester = first,
                        onClick = onRequestPhotoAccess)
                }
                loading -> Text("Loading photos…", color = colors.inkSoft, fontSize = 13.sp)
                error.isNotBlank() -> Text(error, color = colors.inkSoft, fontSize = 13.sp)
                entries.isEmpty() -> Text(
                    if (canBrowseFiles) "No supported photos in this folder. Open another folder or insert a USB drive."
                    else "No photos found in Android's media library. Try Send from device instead.",
                    color = colors.inkSoft, fontSize = 13.sp,
                )
            }
            if (entries.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    itemsIndexed(entries) { index, item ->
                        Pill(
                            (if (item.isFolder) "📁  " else "🖼  ") + item.name,
                            colors,
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = if (index == 0) first else null,
                        ) {
                            if (item.folder != null) {
                                if (folder == null) volumeRoot = item.folder
                                folder = item.folder
                            } else onPick(item)
                        }
                    }
                }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Pill("Cancel", colors,
                    focusRequester = if (entries.isEmpty() && (canBrowseFiles || canReadPhotos)) first else null,
                    onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(folder, entries, loading, canReadPhotos, canBrowseFiles) {
        if (!loading) runCatching { first.requestFocus() }
    }
}

private fun mountedVolumes(context: Context): List<TvWallpaperEntry> {
    val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
        .mapNotNull { volume ->
            val directory = volume.directory ?: return@mapNotNull null
            if (!directory.isDirectory || !directory.canRead()) return@mapNotNull null
            TvWallpaperEntry(
                name = if (volume.isPrimary) "Internal storage" else volume.getDescription(context),
                folder = directory,
            )
        }.distinctBy { it.folder?.canonicalPath }
    if (volumes.isNotEmpty()) return volumes
    val fallback = Environment.getExternalStorageDirectory()
    return if (fallback.isDirectory && fallback.canRead())
        listOf(TvWallpaperEntry("Internal storage", folder = fallback)) else emptyList()
}

private fun imagesAndFolders(folder: File): List<TvWallpaperEntry> =
    folder.listFiles().orEmpty()
        .asSequence()
        .filter { it.canRead() && !it.name.startsWith(".") }
        .filter { it.isDirectory || isSupportedPhoto(it.name) }
        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        .take(500)
        .map { entry ->
            if (entry.isDirectory) TvWallpaperEntry(entry.name, folder = entry)
            else TvWallpaperEntry(entry.name, image = entry, size = entry.length())
        }.toList()

private fun isSupportedPhoto(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf("jpg", "jpeg", "png", "webp")
}

private fun hasMediaPermission(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 34 ->
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    Build.VERSION.SDK_INT >= 33 ->
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

/** Android's indexed photo library is available without a separate document picker app. */
private fun mediaImages(context: Context): List<TvWallpaperEntry> {
    val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val columns = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
    )
    val output = mutableListOf<TvWallpaperEntry>()
    context.contentResolver.query(
        uri, columns, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC",
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        while (cursor.moveToNext() && output.size < 300) {
            val name = cursor.getString(nameIndex) ?: continue
            val mime = cursor.getString(mimeIndex) ?: continue
            if (mime !in setOf("image/jpeg", "image/png", "image/webp")) continue
            val size = cursor.getLong(sizeIndex)
            if (size !in 1..(16L * 1024 * 1024)) continue
            val imageUri = ContentUris.withAppendedId(uri, cursor.getLong(idIndex))
            output.add(TvWallpaperEntry(name, mediaUri = imageUri, size = size))
        }
    }
    return output
}
