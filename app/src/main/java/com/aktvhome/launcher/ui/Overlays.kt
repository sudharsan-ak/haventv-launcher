package com.aktvhome.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.ui.theme.ThemeColors

/** The hold-OK menu and its follow-up dialogs. Shared by every theme. */
@Composable
fun AppMenuOverlays(state: HavenState, colors: ThemeColors) {
    var pickerFor by remember { mutableStateOf<AppEntry?>(null) }
    var newCollectionFor by remember { mutableStateOf<AppEntry?>(null) }
    val store = state.layoutStore

    state.menuFor?.let { app ->
        AppMenuDialog(
            app, colors,
            onMove = { state.menuFor = null; state.moving = app.pkg },
            onMoveTo = { state.menuFor = null; pickerFor = app },
            onHide = { state.menuFor = null; store.hide(app.pkg) },
            onDismiss = { state.menuFor = null },
        )
    }

    pickerFor?.let { app ->
        CollectionPickerDialog(
            colors,
            names = store.layout.collections.filter { !it.isInputs }.map { it.id to it.name },
            currentId = store.collectionOf(app.pkg)?.id,
            onPick = { id -> store.moveToCollection(app.pkg, id); pickerFor = null },
            onNew = { pickerFor = null; newCollectionFor = app },
            onDismiss = { pickerFor = null },
        )
    }

    newCollectionFor?.let { app ->
        TextInputDialog(
            colors, "New collection", "",
            onSave = { name ->
                val id = store.addCollection(name)
                store.moveToCollection(app.pkg, id)
                newCollectionFor = null
            },
            onDismiss = { newCollectionFor = null },
        )
    }
}
