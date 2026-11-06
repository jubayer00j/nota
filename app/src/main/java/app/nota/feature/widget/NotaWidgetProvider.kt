package app.nota.feature.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.nota.notes.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Phase 5.3: the Glance home-screen widget — one resizable
 * RemoteViews placement in pine & paper: today's date, the daily
 * note's title + a short excerpt (or a friendly empty/locked/hidden
 * state), the next upcoming reminder when one exists, and New note /
 * Today quick-capture buttons.
 *
 * Rendering technology: classic RemoteViews, NOT Jetpack Glance.
 * Glance's latest stable (1.1.1) predates the Compose runtime that
 * BOM 2026.09.00 ships by several major versions and implements
 * Compose-runtime applier interfaces whose binary compatibility has
 * broken across exactly those versions — in a CI-only pipeline that
 * failure would surface as a home-screen crash at runtime, not a
 * compile error, and it cannot be verified clean here. RemoteViews
 * is also precisely what Noma 1.x's widget renders with, needs no
 * new dependency, and keeps the APK flat. The state mapping is a
 * pure seam ([WidgetStateMapper]) either way.
 *
 * Failure discipline (1.x's): the widget never renders an error or
 * a blank. Any failure while loading — Koin not started, a database
 * hiccup — falls back to the HIDDEN generic state, which is also
 * the privacy-safe direction.
 */
class NotaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        render(context, appWidgetManager, intArrayOf(appWidgetId))
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    private fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = loadState(appContext)
                val views = buildViews(appContext, state)
                appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun loadState(context: Context): WidgetState =
        runCatching {
            val koin = GlobalContext.get()
            koin.get<WidgetDataSource>().load()
        }.getOrElse {
            // Fail closed + never blank: the HIDDEN generic state.
            WidgetStateMapper.map(
                today = java.time.LocalDate.now(),
                appLockHidden = true,
                todayNote = null,
                nextReminder = null,
            )
        }

    private fun buildViews(context: Context, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.nota_widget)
        views.setTextViewText(R.id.widget_date, state.dateLabel)
        views.setTextViewText(R.id.widget_today_title, state.todayTitle ?: "")
        if (state.todayExcerpt != null) {
            views.setViewVisibility(R.id.widget_today_excerpt, View.VISIBLE)
            views.setTextViewText(R.id.widget_today_excerpt, state.todayExcerpt)
        } else {
            views.setViewVisibility(R.id.widget_today_excerpt, View.GONE)
        }
        if (state.reminderLine != null) {
            views.setViewVisibility(R.id.widget_reminder, View.VISIBLE)
            views.setTextViewText(R.id.widget_reminder, state.reminderLine)
        } else {
            views.setViewVisibility(R.id.widget_reminder, View.GONE)
        }
        // Today area: a readable note opens through the
        // notification-tap seam; every other state (empty, locked,
        // hidden) taps through to the today door — staged behind
        // App Lock like every other door, so a hidden or locked
        // note's id never rides a widget intent.
        val todayPending = if (state.todayKind == WidgetTodayKind.CONTENT &&
            state.todayNoteId != null
        ) {
            WidgetIntents.todayNotePendingIntent(context, state.todayNoteId)
        } else {
            WidgetIntents.todayDoorPendingIntent(context)
        }
        views.setOnClickPendingIntent(R.id.widget_today_area, todayPending)
        views.setOnClickPendingIntent(
            R.id.widget_btn_new,
            WidgetIntents.newNotePendingIntent(context),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_today,
            WidgetIntents.todayDoorPendingIntent(context),
        )
        return views
    }
}
