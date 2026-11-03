package app.nota.feature.editor

/** THROWAWAY diagnostic probe — removed with the diagnostic. */
object DragProbe {
    val events = mutableListOf<String>()

    fun log(event: String) {
        synchronized(events) { events += event }
    }

    fun snapshot(): List<String> = synchronized(events) { events.toList() }

    fun clear() {
        synchronized(events) { events.clear() }
    }
}
