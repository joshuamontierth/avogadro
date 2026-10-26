package net.montierth.avogadro.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri

object Links {
    /** Opens a result in a Custom Tab tinted like the page (falls back to any browser). */
    fun open(context: Context, url: String, p: Palette) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        val felt = p.felt.toArgb()
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(if (p.dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder().setToolbarColor(felt).setNavigationBarColor(felt).build(),
            )
            .build()
        try {
            intent.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                // Nothing can open it; stay put.
            }
        }
    }
}
