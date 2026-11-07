package app.ecowidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import app.ecowidget.data.Storage
import app.ecowidget.update.UpdateScheduler

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            // Ajout d'un widget, ou filet de sécurité système (toutes les 30 min) :
            // on relance une actualisation et on s'assure que la chaîne d'alarmes tourne.
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> UpdateScheduler.kick(context)

            AppWidgetManager.ACTION_APPWIDGET_DISABLED -> UpdateScheduler.cancel(context)

            AppWidgetManager.ACTION_APPWIDGET_DELETED -> {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) Storage.deleteWidgetConfig(context, id)
            }
        }
    }
}
