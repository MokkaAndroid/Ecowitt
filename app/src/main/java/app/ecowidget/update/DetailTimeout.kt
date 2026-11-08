package app.ecowidget.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.ecowidget.data.DetailData

/**
 * Retour automatique aux tuiles (mode « Tuiles + détail ») : une alarme unique, re-programmée à chaque interaction,
 * demande à [UpdateReceiver] de refermer les détails restés sans appui depuis 30 s.
 */
object DetailTimeout {
    const val ACTION_CLOSE = "app.ecowidget.action.CLOSE_DETAILS"
    private const val REQUEST_CODE = 4202

    fun arm(ctx: Context, inMs: Long = DetailData.AUTO_CLOSE_MS) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        // Petite marge : au déclenchement, le détail doit avoir dépassé le délai.
        val at = SystemClock.elapsedRealtime() + inMs + 500L
        val pi = pendingIntent(app)
        if (UpdateScheduler.canScheduleExact(app)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, UpdateReceiver::class.java).setAction(ACTION_CLOSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
