package com.healthdecoder.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.healthdecoder.app.ui.tr

/** The app's 6 first-class destinations, reachable from the bottom nav on every one of them. */
enum class BottomNavTab { Home, Chat, Trends, Compare, Brief, Settings }

private data class BottomNavItem(val tab: BottomNavTab, val label: String, val icon: ImageVector)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(BottomNavTab.Home, "Home", Icons.Default.Home),
    BottomNavItem(BottomNavTab.Chat, "Chat", Icons.AutoMirrored.Filled.Chat),
    BottomNavItem(BottomNavTab.Trends, "Trends", Icons.AutoMirrored.Filled.ShowChart),
    BottomNavItem(BottomNavTab.Compare, "Compare", Icons.AutoMirrored.Filled.CompareArrows),
    BottomNavItem(BottomNavTab.Brief, "Brief", Icons.AutoMirrored.Filled.Article),
    BottomNavItem(BottomNavTab.Settings, "Settings", Icons.Default.Settings),
)

/**
 * Bottom navigation bar shared by all 6 first-class destinations (Home, Chat, Trends, Compare,
 * Brief, Settings). Monochrome Material icons with a single accent color (the app's existing
 * teal, `MaterialTheme.colorScheme.primary`) for the active tab — deliberately not the mockup's
 * colorful per-tab icon style, to match the rest of the app's Material 3 language. `primary`
 * already resolves to a bright teal in dark mode (see theme/Theme.kt), so the active tab stays
 * visibly distinct there too, not just barely-there gray-on-black.
 */
@Composable
fun AppBottomNavBar(
    currentTab: BottomNavTab,
    onNavigate: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    ) {
        BOTTOM_NAV_ITEMS.forEach { item ->
            NavigationBarItem(
                selected = currentTab == item.tab,
                onClick = { onNavigate(item.tab) },
                icon = { Icon(imageVector = item.icon, contentDescription = tr(item.label)) },
                label = { Text(tr(item.label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
