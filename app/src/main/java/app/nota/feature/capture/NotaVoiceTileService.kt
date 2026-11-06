package app.nota.feature.capture

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nota.R
import app.nota.data.capture.CaptureIntents
import app.nota.data.capture.CaptureSource

/**
 * Phase 5.4: the "Voice note" quick-settings tile — the second of
 * the two voice doors 5.2 deferred to this phase. Same contract as
 * the 5.2 capture tile: the tile RECORDS NOTHING; it stages the
 * voice door through the capture seam and the shell presents the
 * listening sheet once the app is open and unlocked (a locked app
 * shows nothing; the staged record waits/retires exactly like
 * every other capture).
 */
class NotaVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = getString(R.string.tile_voice_label)
            tile.icon = android.graphics.drawable.Icon.createWithResource(
                this,
                R.mipmap.ic_launcher,
            )
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = CaptureIntents.doorIntent(
            this,
            CaptureIntents.DOOR_VOICE_NOTE,
            CaptureSource.TILE,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                DOOR_VOICE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        /** Distinct from the capture tile's code so the two pending
         *  intents never overwrite each other. */
        const val DOOR_VOICE_REQUEST_CODE = 5204
    }
}
