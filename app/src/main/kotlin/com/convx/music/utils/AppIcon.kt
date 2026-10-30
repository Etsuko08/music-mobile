/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.convx.music.R
import com.convx.music.WelcomeActivity

/**
 * Launcher icon aliases; one is enabled at a time and the selection is mirrored in DataStore.
 * [previewRes] is the foreground layer shown over [R.color.ic_launcher_tile] in the picker.
 * [accentColor] is the distinct vibrant color of the split-disc mark for this variant.
 */
enum class AppIcon(
    val id: String,
    val alias: String,
    @DrawableRes val previewRes: Int,
    @StringRes val labelRes: Int,
    val accentColor: Color,
) {
    WHITE("white", ".LauncherWhite", R.mipmap.ic_launcher_white_fg, R.string.app_icon_white, Color(0xFFF2EEE9)),
    AZURE("azure", ".LauncherAzure", R.mipmap.ic_launcher_azure_fg, R.string.app_icon_azure, Color(0xFF007AFF)),
    SKY("sky", ".LauncherSky", R.mipmap.ic_launcher_sky_fg, R.string.app_icon_sky, Color(0xFF36BCF8)),
    TEAL("teal", ".LauncherTeal", R.mipmap.ic_launcher_teal_fg, R.string.app_icon_teal, Color(0xFF12B7A6)),
    SLATE("slate", ".LauncherSlate", R.mipmap.ic_launcher_slate_fg, R.string.app_icon_slate, Color(0xFF93A2B6)),
    SILVER("silver", ".LauncherSilver", R.mipmap.ic_launcher_silver_fg, R.string.app_icon_silver, Color(0xFFE2E7EF)),
    PERIWINKLE(
        "periwinkle",
        ".LauncherPeriwinkle",
        R.mipmap.ic_launcher_periwinkle_fg,
        R.string.app_icon_periwinkle,
        Color(0xFF7F8BF7),
    ),
    VIOLET("violet", ".LauncherViolet", R.mipmap.ic_launcher_violet_fg, R.string.app_icon_violet, Color(0xFFA753F6)),
    MIDNIGHT(
        "midnight",
        ".LauncherMidnight",
        R.mipmap.ic_launcher_midnight_fg,
        R.string.app_icon_midnight,
        Color(0xFF3B81F4),
    ),
    MAUVE("mauve", ".LauncherMauve", R.mipmap.ic_launcher_mauve_fg, R.string.app_icon_mauve, Color(0xFFC083FC)),
    ROSE("rose", ".LauncherRose", R.mipmap.ic_launcher_rose_fg, R.string.app_icon_rose, Color(0xFFF33E5E)),
    COPPER("copper", ".LauncherCopper", R.mipmap.ic_launcher_copper_fg, R.string.app_icon_copper, Color(0xFFF39E0B)),
    RUST("rust", ".LauncherRust", R.mipmap.ic_launcher_rust_fg, R.string.app_icon_rust, Color(0xFFE8580B)),
    ;

    companion object {
        val DEFAULT = WHITE

        fun fromId(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Enables the new alias before disabling the old one, keeping a launcher entry active. */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                componentFor(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (otherIcon in entries) {
                if (otherIcon == icon) continue
                pm.setComponentEnabledSetting(
                    componentFor(context, otherIcon),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        /** Aliases resolve against the namespace, which can differ from applicationId in debug builds. */
        private fun componentFor(context: Context, icon: AppIcon): ComponentName {
            val namespace = WelcomeActivity::class.java.name.substringBeforeLast('.')
            return ComponentName(context.packageName, namespace + icon.alias)
        }
    }
}
