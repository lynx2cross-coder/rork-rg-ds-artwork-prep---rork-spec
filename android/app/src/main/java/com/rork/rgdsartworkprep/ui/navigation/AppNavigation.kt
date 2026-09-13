package com.rork.rgdsartworkprep.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.layout.rememberAppLayout
import com.rork.rgdsartworkprep.ui.screens.ArtworkScreen
import com.rork.rgdsartworkprep.ui.screens.DiagnosticsScreen
import com.rork.rgdsartworkprep.ui.screens.HomeScreen
import com.rork.rgdsartworkprep.ui.screens.LibraryScanScreen
import com.rork.rgdsartworkprep.ui.screens.PrepareScreen
import com.rork.rgdsartworkprep.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PREPARE = "prepare"
    const val LIBRARY = "library"
    const val ARTWORK = "artwork"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val layout = rememberAppLayout()

    CompositionLocalProvider(LocalAppLayout provides layout) {
        AppNavHost(navController)
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPrepare = { navController.navigate(Routes.PREPARE) },
                onScanLibrary = { navController.navigate(Routes.LIBRARY) },
                onArtwork = { navController.navigate(Routes.ARTWORK) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PREPARE) {
            PrepareScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onPickFromLibrary = { navController.navigate(Routes.LIBRARY) },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScanScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onStarted = {
                    navController.navigate(Routes.PREPARE) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(Routes.ARTWORK) {
            ArtworkScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}
