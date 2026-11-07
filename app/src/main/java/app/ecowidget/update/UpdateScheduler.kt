package app.ecowidget.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import app.ecowidget.data.Storage
import app.ecowidget.widget.WeatherWidgetReceiver

/**
 * Actualisation à intervalle court (2 min par défaut) sans service permanent :
 * chaque alarme déclenche UpdateReceiver, qui re-programme la suivante (chaîne d'alarmes).
 *
 * - Avec la permission « Alarmes et rappels » : alarme exacte (setExactAndAllowWhileIdle).
 * - Sans : alarme approximative, moins régulière.
 * Android peut espacer les alarmes en veille profonde (Doze) : ~9 à 15 min dans ce cas.
 */
object UpdateScheduler {
    const val ACTION_UPDATE = "app.ecowidget.action.UPDATE"
    private const val REQUEST_CODE = 4201

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
            return
        }
        val triggerAt = SystemClock.elapsedRealtime() + Storage.intervalMinutes(app) * 60_000L
        if (canScheduleExact(app)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(pendingIntent(app))
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
