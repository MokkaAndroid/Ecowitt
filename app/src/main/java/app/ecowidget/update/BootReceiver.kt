package app.ecowidget.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Les alarmes disparaissent au redémarrage : on relance la chaîne. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" ->
                UpdateScheduler.kick(context)
        }
    }
}
