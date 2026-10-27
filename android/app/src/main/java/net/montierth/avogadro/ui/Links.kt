package net.montierth.avogadro.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import net.montierth.avogadro.R

/**
 * Pages open in a Custom Tab dressed as part of the app: the felt toolbar, a back arrow, the app's
 * slide in and out, share in the toolbar and "Copy link" in the menu. Links an installed app owns
 * (YouTube, maps) open in that app instead.
 */
object Links {
    private var connection: CustomTabsServiceConnection? = null
    private var session: CustomTabsSession? = null

    /** A page opened from here is in front: the app is stopped but has not been left. */
    @Volatile var tabOpen = false

    /** Wakes the browser while the app is in front, so a page opens without its start-up pause. */
    fun connect(context: Context) {
        if (connection != null) return
        val browser = CustomTabsClient.getPackageName(context, null) ?: return
        val c = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0)
                session = client.newSession(null)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                session = null
            }
        }
        if (runCatching { CustomTabsClient.bindCustomTabsService(context, browser, c) }.getOrDefault(false)) connection = c
    }

    fun disconnect(context: Context) {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        session = null
    }

    fun open(context: Context, url: String, p: Palette) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        val felt = p.felt.toArgb()
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(felt)
            .setNavigationBarColor(felt)
            .setNavigationBarDividerColor(felt)
            .build()
        val builder = session?.let { CustomTabsIntent.Builder(it) } ?: CustomTabsIntent.Builder()
        builder
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setColorScheme(if (p.dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setDefaultColorSchemeParams(colors)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .addMenuItem("Copy link", copyLink(context))
            .setSendToExternalDefaultHandlerEnabled(true)
            .setStartAnimations(context, R.anim.page_enter, R.anim.app_exit)
            .setExitAnimations(context, R.anim.app_enter, R.anim.page_exit)
        context.getDrawable(R.drawable.ic_back)?.mutate()?.let { arrow ->
            arrow.setTint(p.vapor.toArgb())
            builder.setCloseButtonIcon(arrow.toBitmap())
        }
        tabOpen = true
        try {
            builder.build().launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            tabOpen = false
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                // Nothing can open it; stay put.
            }
        }
    }

    /** The menu's "Copy link": the browser fills in the page's current address. */
    private fun copyLink(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, Intent(context, CopyLinkReceiver::class.java), flags)
    }
}

/** Copies the address a Custom Tab's "Copy link" sends (non-exported; only our pending intent reaches it). */
class CopyLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.dataString ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", url))
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }
}
