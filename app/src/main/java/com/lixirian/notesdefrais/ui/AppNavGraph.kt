package com.lixirian.notesdefrais.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lixirian.notesdefrais.NotesDeFraisApp
import com.lixirian.notesdefrais.ui.archive.ArchiveScreen
import com.lixirian.notesdefrais.ui.edit.EditArgs
import com.lixirian.notesdefrais.ui.list.ExpenseListScreen
import com.lixirian.notesdefrais.ui.settings.SettingsScreen
import com.lixirian.notesdefrais.ui.trash.TrashScreen

object Routes {
    const val HOME = "home"
    const val ARCHIVE = "archive"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
}

@Composable
fun appContext(): NotesDeFraisApp = LocalContext.current.applicationContext as NotesDeFraisApp

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(240)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(280)) },
    ) {
        composable(Routes.HOME) {
            ListDetailHost(onSettings = { navController.navigate(Routes.SETTINGS) }) { openDetail ->
                ExpenseListScreen(
                    onAddManual = { openDetail(EditArgs()) },
                    onAddFromImage = { uri -> openDetail(EditArgs(image = uri)) },
                    onOpen = { id -> openDetail(EditArgs(expenseId = id)) },
                    onArchive = { navController.navigate(Routes.ARCHIVE) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
        }
        composable(Routes.ARCHIVE) {
            ListDetailHost(onSettings = { navController.navigate(Routes.SETTINGS) }) { openDetail ->
                ArchiveScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> openDetail(EditArgs(expenseId = id)) },
                    onTrash = { navController.navigate(Routes.TRASH) },
                )
            }
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
