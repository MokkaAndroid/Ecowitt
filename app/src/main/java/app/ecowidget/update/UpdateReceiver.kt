package app.ecowidget.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ecowidget.data.WeatherRepository
import app.ecowidget.widget.WidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Reçoit l'alarme : re-programme la suivante, récupère les données, redessine les widgets. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateScheduler.ACTION_UPDATE) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // D'abord la suivante : même si la suite échoue, la chaîne continue.
                UpdateScheduler.scheduleNext(app)
                if (UpdateScheduler.hasWidgets(app)) {
                    WeatherRepository.refresh(app)
                    WidgetRefresh.all(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
