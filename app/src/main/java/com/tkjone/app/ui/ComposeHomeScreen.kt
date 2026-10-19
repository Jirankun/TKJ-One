package com.tkjone.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.tkjone.app.R

private val BrandBlue = Color(0xFF0A84FF)
private val CanvasColor = Color(0xFF1C1C1E)
private val NavigationColor = Color(0xFF2C2C2E)
private val MutedColor = Color(0xFF6B6B70)
private val NavigationDivider = Color(0xFF3A3A3C)

@Composable
fun ComposeHomeScreen(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    contentView: (Int) -> View
) {
    var activeTab by remember { mutableIntStateOf(selectedTab) }
    LaunchedEffect(selectedTab) { activeTab = selectedTab }
    MaterialTheme(colors = darkColors(primary = BrandBlue, background = CanvasColor, surface = NavigationColor)) {
        Scaffold(
            backgroundColor = CanvasColor,
            bottomBar = {
                Column {
                    Divider(
                        modifier = Modifier.fillMaxWidth().height(1.dp),
                        color = NavigationDivider
                    )
                    BottomNavigation(backgroundColor = NavigationColor, contentColor = BrandBlue) {
                        val galleryColor by animateColorAsState(if (activeTab == 0) BrandBlue else MutedColor, label = "gallery-tab-color")
                        val profileColor by animateColorAsState(if (activeTab == 1) BrandBlue else MutedColor, label = "profile-tab-color")
                        BottomNavigationItem(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0; onTabSelected(0) },
                            icon = { androidx.compose.foundation.Image(painterResource(R.drawable.photo), contentDescription = null, colorFilter = ColorFilter.tint(galleryColor)) },
                            label = { Text(stringResource(R.string.nav_gallery), color = galleryColor) },
                            selectedContentColor = BrandBlue,
                            unselectedContentColor = MutedColor
                        )
                        BottomNavigationItem(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1; onTabSelected(1) },
                            icon = { androidx.compose.material.Icon(Icons.Filled.Person, contentDescription = null, tint = profileColor) },
                            label = { Text(stringResource(R.string.nav_profile), color = profileColor) },
                            selectedContentColor = BrandBlue,
                            unselectedContentColor = MutedColor
                        )
                    }
                }
            }
        ) { paddingValues ->
            Surface(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
                color = CanvasColor
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> FrameLayout(context) },
                    update = { host ->
                        if (host.tag != activeTab) {
                            host.removeAllViews()
                            val view = contentView(activeTab)
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.alpha = 0f
                            view.translationY = 12f
                            host.addView(view, FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            ))
                            view.animate().alpha(1f).translationY(0f).setDuration(220).start()
                            host.tag = activeTab
                        }
                    }
                )
            }
        }
    }
}
