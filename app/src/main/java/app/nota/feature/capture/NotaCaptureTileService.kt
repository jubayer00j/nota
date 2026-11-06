package app.nota.feature.capture

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nota.data.capture.CaptureIntents
import app.nota.data.capture.CaptureSource
import app.nota.notes.R

/**
 * Quick Settings tile: the lock-screen-reachable capture door — a
 * port of 1.x's NomaCaptureTileService. One tap fires the same
 * new-note door as the launcher shortcut; MainActivity stages it and
 * the shell delivers it after unlock, so if App Lock is on the user
 * simply meets the lock screen first — the tile never bypasses it.
 *
 * On API 34+ startActivityAndCollapse requires the PendingIntent
 * form; below that the intent form is used (1.x handles the split
 * identically).
 */
class NotaCaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        // It's an action, not a toggle: always shown as available.
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = CaptureIntents.doorIntent(
            this,
            CaptureIntents.DOOR_NEW_NOTE,
            CaptureSource.TILE,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
