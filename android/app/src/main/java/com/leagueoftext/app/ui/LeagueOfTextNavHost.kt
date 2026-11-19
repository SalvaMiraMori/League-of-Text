package com.leagueoftext.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.leagueoftext.app.R
import com.leagueoftext.app.notifications.LiveNotifications
import com.leagueoftext.app.ui.esports.EsportsRoute
import com.leagueoftext.app.ui.following.FollowingRoute
import com.leagueoftext.app.ui.match.MatchDetailRoute
import com.leagueoftext.app.ui.profile.ProfileRoute
import com.leagueoftext.app.ui.theme.RiotColors

/** Demana el permís de notificacions (Android 13+) quan l'usuari segueix un partit. */
val LocalRequestNotificationPermission = staticCompositionLocalOf<() -> Unit> { {} }

private object Routes {
    const val ESPORTS = "esports"
    const val FOLLOWING = "following"
    const val PROFILE = "profile"
    const val MATCH = "match/{matchId}"
    fun match(id: String) = "match/$id"
}

private data class TopLevel(val route: String, val labelRes: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.ESPORTS, R.string.tab_esports, Icons.Filled.EmojiEvents),
    TopLevel(Routes.FOLLOWING, R.string.tab_following, Icons.Filled.Notifications),
    TopLevel(Routes.PROFILE, R.string.tab_profile, Icons.Filled.Person),
)

@Composable
fun LeagueOfTextNavHost(pendingMatchId: String?, onPendingMatchHandled: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showBottomBar = topLevel.any { item -> currentDestination?.hierarchy?.any { it.route == item.route } == true }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val requestPermission: () -> Unit = remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !LiveNotifications.canNotify(context)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // En tocar una notificació s'obre el partit corresponent.
    LaunchedEffect(pendingMatchId) {
        if (pendingMatchId != null) {
            navController.navigate(Routes.match(pendingMatchId)) { launchSingleTop = true }
            onPendingMatchHandled()
        }
    }

    CompositionLocalProvider(LocalRequestNotificationPermission provides requestPermission) {
        Scaffold(
            containerColor = RiotColors.Background,
            bottomBar = {
                if (showBottomBar) {
                    Column {
                        HorizontalDivider(color = RiotColors.Divider, thickness = 1.dp)
                        NavigationBar(containerColor = RiotColors.Background, tonalElevation = 0.dp) {
                            topLevel.forEach { item ->
                                val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(stringResource(item.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = RiotColors.TextPrimary,
                                        selectedTextColor = RiotColors.TextPrimary,
                                        unselectedIconColor = RiotColors.TextTertiary,
                                        unselectedTextColor = RiotColors.TextTertiary,
                                        indicatorColor = Color.Transparent,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.ESPORTS,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.ESPORTS) {
                    EsportsRoute(onOpenMatch = { navController.navigate(Routes.match(it)) })
                }
                composable(Routes.FOLLOWING) {
                    FollowingRoute(onOpenMatch = { navController.navigate(Routes.match(it)) })
                }
                composable(Routes.PROFILE) { ProfileRoute() }
                composable(
                    route = Routes.MATCH,
                    arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
                ) { entry ->
                    val matchId = entry.arguments?.getString("matchId").orEmpty()
                    MatchDetailRoute(matchId = matchId, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
