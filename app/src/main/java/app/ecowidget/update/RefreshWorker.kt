package app.ecowidget.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ecowidget.data.EcowittClient
import app.ecowidget.data.WeatherRepository
import app.ecowidget.widget.WidgetRefresh

/**
 * Actualisation différée : lancée par WorkManager quand un réseau est disponible.
 * Sert (1) de reprise après une alarme tombée sans réseau, (2) de tâche périodique de secours (15 min) qui
 * relance aussi la chaîne d'alarmes si le système l'a supprimée.
 */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!UpdateScheduler.hasWidgets(app)) return Result.success()
        UpdateScheduler.ensureChain(app)
        val err = WeatherRepository.refresh(app)
        WidgetRefresh.all(app)
        // Réseau revenu puis reparti : quelques nouvelles tentatives espacées, puis on laisse le cycle suivant.
        return if (err == EcowittClient.OFFLINE_MSG && runAttemptCount < 4) Result.retry() else Result.success()
    }
}
