package app.ecowidget.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ecowidget.data.DetailData
import app.ecowidget.data.EcowittClient
import app.ecowidget.data.Storage
import app.ecowidget.data.WeatherRepository
import app.ecowidget.widget.WidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reçoit les alarmes :
 * - actualisation : re-programme la suivante, récupère les données, redessine les widgets ;
 * - fermeture des détails du mode « Tuiles + détail » restés sans interaction depuis 30 s.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            UpdateScheduler.ACTION_UPDATE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        // D'abord la suivante : même si la suite échoue, la chaîne continue.
                        UpdateScheduler.scheduleNext(app)
                        if (UpdateScheduler.hasWidgets(app)) {
                            val err = WeatherRepository.refresh(app)
                            WidgetRefresh.all(app)
                            // Pas de réseau (veille profonde, mode avion…) : on réessaie dès qu'il revient,
                            // sans attendre le cycle suivant.
                            if (err == EcowittClient.OFFLINE_MSG) UpdateScheduler.enqueueRefresh(app)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            DetailTimeout.ACTION_CLOSE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val (closed, nextIn) = Storage.closeExpiredDetails(app, DetailData.AUTO_CLOSE_MS)
                        if (closed > 0) WidgetRefresh.all(app)
                        // Un détail a été touché entre-temps : on reprogramme pour son échéance.
                        if (nextIn != null) DetailTimeout.arm(app, nextIn)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
