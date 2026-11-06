package app.nota.feature.capture

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.nota.data.capture.CaptureIntents
import app.nota.notes.R

/**
 * Phase 5.2: per-note home-screen pinning via
 * [ShortcutManager.requestPinShortcut] (API 26+; Nota's minSdk is
 * 26, so the API exists everywhere — whether the LAUNCHER honours
 * pin requests is the runtime question).
 *
 * The pinned shortcut opens the note through the 5.1 notification
 * seam ([CaptureIntents.noteOpenIntent]), so App Lock and the note's
 * own lock gate apply as usual. Creating it needs only the note's id,
 * title and lock state — never its content, never an unlock.
 *
 * Privacy: a LOCKED note pins under the generic label in BOTH the
 * short and long label slots — its title must not reach the
 * launcher in either field.
 */
object NotePinShortcut {

    const val GENERIC_LABEL = "Nota note"
    const val UNTITLED_LABEL = "Untitled note"

    /** (shortLabel, longLabel) for a pin. Pure; unit-tested. */
    fun labels(title: String, locked: Boolean): Pair<String, String> {
        if (locked) return GENERIC_LABEL to GENERIC_LABEL
        val label = title.trim().ifEmpty { UNTITLED_LABEL }
        return label to label
    }

    sealed interface Result {
        /** The pin request was handed to the launcher. */
        data object Requested : Result

        /** The launcher doesn't support pinned shortcuts — the UI
         *  says so plainly; nothing crashed, nothing was pinned. */
        data object Unsupported : Result
    }

    fun requestPin(
        context: Context,
        noteId: String,
        title: String,
        locked: Boolean,
    ): Result {
        val manager = context.getSystemService(ShortcutManager::class.java)
            ?: return Result.Unsupported
        if (!manager.isRequestPinShortcutSupported) return Result.Unsupported
        val (shortLabel, longLabel) = labels(title, locked)
        val info = ShortcutInfo.Builder(context, "note-$noteId")
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(CaptureIntents.noteOpenIntent(context, noteId))
            .build()
        manager.requestPinShortcut(info, null)
        return Result.Requested
    }
}
