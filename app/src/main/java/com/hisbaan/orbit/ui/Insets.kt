package com.hisbaan.orbit.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable

/**
 * Scaffold insets for a screen that scrolls: everything but the bottom, so the content scrolls
 * on under the nav bar instead of stopping above a bare strip. The screen pads the end of its
 * scrolling content by the nav bar itself.
 */
@Composable
fun scrollingScreenInsets(): WindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
