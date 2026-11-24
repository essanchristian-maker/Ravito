package com.ravito.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.history.EditTransactionScreen
import com.ravito.app.ui.history.HistoryScreen
import com.ravito.app.ui.history.ReviewScreen
import com.ravito.app.ui.history.TransactionDetailScreen
import com.ravito.app.ui.lock.LockScreen
import com.ravito.app.ui.onboarding.OnboardingScreen
import com.ravito.app.ui.report.ReportScreen
import com.ravito.app.ui.settings.MerchantsScreen
import com.ravito.app.ui.settings.SettingsScreen
import com.ravito.app.ui.settings.TemplateScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("history", "Historique", Icons.AutoMirrored.Filled.List),
    Tab("report", "Rapport", Icons.Filled.Edit),
    Tab("settings", "Paramètres", Icons.Filled.Settings),
)

@Composable
fun RavitoRoot(
    locked: Boolean,
    biometricAvailable: Boolean,
    openTab: String?,
    onTabConsumed: () -> Unit,
    onBiometric: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val app = rememberApp()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    // Pendant le démarrage, le PIN vient d'être créé : pas de verrouillage avant la fin.
    var justOnboarded by remember { mutableStateOf(false) }

    when {
        !settings.onboardingDone -> WithStatusBand { OnboardingScreen(onDone = { justOnboarded = true; onUnlocked() }) }
        locked && !justOnboarded -> WithStatusBand { LockScreen(biometricAvailable, onBiometric, onUnlocked) }
        else -> MainScaffold(openTab, onTabConsumed)
    }
    LaunchedEffect(locked) { if (locked) justOnboarded = false }
}

/** Bande verte derrière la barre d'état (icônes claires) pour les écrans sans barre de titre. */
@Composable
private fun WithStatusBand(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars).navigationBarsPadding().imePadding()) { content() }
    }
}

@Composable
private fun MainScaffold(openTab: String?, onTabConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    LaunchedEffect(openTab) {
        if (openTab != null) {
            nav.goToTab(openTab)
            onTabConsumed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.goToTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "history", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("history") {
                HistoryScreen(
                    onOpenTransaction = { nav.navigate("tx/$it") },
                    onOpenReview = { nav.navigate("review") },
                    onAddManual = { nav.navigate("edit") },
                )
            }
            composable("report") { ReportScreen(onOpenReview = { nav.navigate("review") }) }
            composable("settings") {
                SettingsScreen(
                    onOpenMerchants = { nav.navigate("merchants") },
                    onOpenTemplate = { nav.navigate("template") },
                )
            }
            composable("tx/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0L
                TransactionDetailScreen(id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit?txId=$it") })
            }
            composable(
                "edit?txId={txId}&reviewId={reviewId}",
                arguments = listOf(
                    navArgument("txId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("reviewId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { e ->
                val txId = e.arguments?.getLong("txId")?.takeIf { it > 0 }
                val reviewId = e.arguments?.getLong("reviewId")?.takeIf { it > 0 }
                EditTransactionScreen(txId, reviewId, onDone = { nav.popBackStack() })
            }
            composable("review") {
                ReviewScreen(onBack = { nav.popBackStack() }, onAdd = { nav.navigate("edit?reviewId=$it") })
            }
            composable("merchants") { MerchantsScreen(onBack = { nav.popBackStack() }) }
            composable("template") { TemplateScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
