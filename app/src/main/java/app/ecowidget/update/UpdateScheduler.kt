package app.ecowidget.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import app.ecowidget.data.Storage
import app.ecowidget.widget.WeatherWidgetReceiver

/**
 * Actualisation à intervalle court (2 min par défaut) sans service permanent :
 * chaque alarme déclenche UpdateReceiver, qui re-programme la suivante (chaîne d'alarmes).
 *
 * - Avec la permission « Alarmes et rappels » : alarme exacte (setExactAndAllowWhileIdle).
 * - Sans : alarme approximative, moins régulière.
 * Android peut espacer les alarmes en veille profonde (Doze) : ~9 à 15 min dans ce cas.
 *
 * Deux filets de sécurité (WorkManager, avec la contrainte « réseau disponible ») :
 * - une actualisation reprogrammée dès que le réseau revient quand l'alarme a échoué faute de réseau ;
 * - une tâche périodique (15 min) qui relance la chaîne d'alarmes si le système l'a supprimée.
 */
object UpdateScheduler {
    const val ACTION_UPDATE = "app.ecowidget.action.UPDATE"
    private const val REQUEST_CODE = 4201
    private const val WORK_ONCE = "ecowidget-refresh-once"
    private const val WORK_PERIODIC = "ecowidget-refresh-periodic"

    /** Une alarme en retard de plus que ça est considérée comme perdue. */
    private const val OVERDUE_MS = 10 * 60_000L

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun hasWidgets(ctx: Context): Boolean {
        val ids = AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, WeatherWidgetReceiver::class.java))
        return ids.isNotEmpty()
    }

    /** Programme la prochaine actualisation (ou annule s'il n'y a plus de widget). */
    fun scheduleNext(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(app)
        if (!hasWidgets(app)) {
            am.cancel(pi)
            cancelWork(app)
            return
        }
        val delayMs = Storage.intervalMinutes(app) * 60_000L
        Storage.setNextDueAt(app, System.currentTimeMillis() + delayMs)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        if (canScheduleExact(app)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
        armSafetyNet(app)
    }

    private fun networkOnly(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Actualisation dès qu'un réseau est disponible (tout de suite s'il l'est déjà). Une seule à la fois. */
    fun enqueueRefresh(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(networkOnly()).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.KEEP, req)
    }

    /** Tâche périodique (15 min, minimum de WorkManager) : actualise et relance la chaîne d'alarmes si elle est rompue. */
    private fun armSafetyNet(app: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(networkOnly()).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun cancelWork(app: Context) {
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(WORK_ONCE)
        wm.cancelUniqueWork(WORK_PERIODIC)
    }

    /** Relance la chaîne d'alarmes si la prochaine échéance est largement dépassée (alarme supprimée par le système). */
    fun ensureChain(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasWidgets(app)) return
        val due = Storage.nextDueAt(app)
        if (due == 0L || System.currentTimeMillis() > due + OVERDUE_MS) scheduleNext(app)
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(pendingIntent(app))
        cancelWork(app)
    }

    /** Lance tout de suite une actualisation + (re)programme la chaîne d'alarmes. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        app.sendBroadcast(updateIntent(app))
    }

    private fun updateIntent(app: Context): Intent =
        Intent(app, UpdateReceiver::class.java).setAction(ACTION_UPDATE)

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        updateIntent(app),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
