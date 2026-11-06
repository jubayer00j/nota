package app.nota.feature.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Phase 5.3: asks the system to re-render every placed Nota widget —
 * 1.x's refreshWidgets discipline: going through the
 * ACTION_APPWIDGET_UPDATE broadcast keeps the provider's own
 * lifecycle in charge of the render, exactly as if the launcher had
 * requested it. Called by the Application-level observer whenever
 * the active account's notes or reminders change, when the account
 * switches, and when the App Lock posture changes. Failure-proof by
 * contract: a refresh problem must never break whatever the user
 * was doing, so every failure is swallowed — the widget simply
 * keeps its previous state until the next trigger or the periodic
 * update.
 */
object WidgetRefresher {

    fun requestUpdate(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val provider = ComponentName(appContext, NotaWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isEmpty()) return
            val intent = Intent(appContext, NotaWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            appContext.sendBroadcast(intent)
        }
    }
}
