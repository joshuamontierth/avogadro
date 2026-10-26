package net.montierth.avogadro.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import net.montierth.avogadro.MainActivity
import net.montierth.avogadro.R

/** A home-screen search bar: tapping it opens Avogadro with the field focused. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_FOCUS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_search)
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
