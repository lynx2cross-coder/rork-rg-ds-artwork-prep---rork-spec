package com.rork.rgdsartworkprep.ui.layout

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen metrics every screen adapts to.
 *
 * Nothing here names a device. The layout is driven purely by the dimensions Android
 * reports, which is what lets one build serve a short landscape handheld like the
 * RG DS, a narrow portrait phone, and a tablet held either way.
 *
 * The two decisions that matter:
 *  - [isSplit] chooses side-by-side panes over a vertical stack, and needs real width.
 *  - [isShort] trims vertical padding and text lines when height is the scarce axis.
 *
 * Touch targets never drop below 44dp in any configuration.
 */
@Immutable
data class AppLayout(
    val isLandscape: Boolean,
    val widthDp: Int,
    val heightDp: Int,
    /** Shorter of the two dimensions — orientation-independent, so it identifies the device class. */
    val smallestWidthDp: Int,
) {
    /** Short viewport: stack less, split more, trim vertical padding. */
    val isShort: Boolean get() = heightDp < 480

    /**
     * Tablet-class screen: generous whichever way it is held.
     *
     * Uses the smallest width so the answer does not flip on rotation — a 10" tablet
     * is a tablet in portrait too, while a phone in landscape is not.
     */
    val isTablet: Boolean get() = smallestWidthDp >= 600

    /**
     * Side-by-side panes, which are only worth it when there is real width to split.
     *
     * Landscape keeps the original 480dp threshold, so the handheld and phone-landscape
     * experience is byte-for-byte the layout it always was. Portrait only splits on a
     * genuinely wide screen — a tablet — since reflowing to a single column is the right
     * answer on a narrow phone held upright.
     */
    val isSplit: Boolean get() = if (isLandscape) widthDp >= 480 else widthDp >= 720

    /** Fraction of the width given to the controls rail in a split layout. */
    val railFraction: Float get() = if (widthDp >= 780) 0.38f else 0.44f

    /**
     * Caps stacked content on a tablet so cards and buttons stay a readable width
     * instead of stretching the full span of the screen. Unconstrained elsewhere.
     */
    val contentMaxWidth: Dp get() = if (isTablet) 640.dp else Dp.Unspecified

    val screenPadding: Dp get() = if (isLandscape) 12.dp else 16.dp
    val sectionSpacing: Dp get() = if (isShort) 8.dp else 14.dp
    val cardPadding: Dp get() = if (isShort) 12.dp else 16.dp

    /** Primary action height — stays a comfortable target on every screen size. */
    val buttonHeight: Dp get() = if (isShort) 48.dp else 54.dp

    /**
     * Cover tile width in the artwork grid. Smaller in landscape to show more rows on a
     * short screen, larger on a tablet so a wide grid does not turn covers into stamps.
     */
    val artworkTileMinWidth: Dp get() = when {
        isTablet -> 132.dp
        isLandscape -> 96.dp
        else -> 108.dp
    }
}

val LocalAppLayout = compositionLocalOf {
    AppLayout(isLandscape = false, widthDp = 411, heightDp = 891, smallestWidthDp = 411)
}

/** Reads the current configuration into [AppLayout]; recomputes on rotation. */
@Composable
fun rememberAppLayout(): AppLayout {
    val configuration = LocalConfiguration.current
    return remember(
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.smallestScreenWidthDp,
    ) {
        AppLayout(
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            smallestWidthDp = configuration.smallestScreenWidthDp,
        )
    }
}
