package app.nota.feature.editor

/**
 * TEMPORARY Phase 3.8 diagnosis probe — an in-memory event log of
 * the block-drag session lifecycle (gesture watch, claim, target
 * recomputation, commit/cancel). EditorBlockDragUiTest dumps it to
 * build/probe-output after every test so a CI run's OWN evidence
 * artifact shows exactly where the release path went. Never read by
 * product behaviour; removed with the diagnosis round's fix.
 */
object DragProbe {
    private val events = mutableListOf<String>()

    @Synchronized
    fun log(event: String) {
        events += event
    }

    @Synchronized
    fun clear() {
        events.clear()
    }

    @Synchronized
    fun snapshot(): String = events.joinToString("\n")
}
