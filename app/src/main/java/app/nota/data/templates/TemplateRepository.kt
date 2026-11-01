package app.nota.data.templates

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A note template (Phase 1c): a named starter title + HTML body. */
data class NoteTemplate(
    val id: String,
    val name: String,
    val title: String,
    val contentHtml: String,
    val builtIn: Boolean = false,
)

/**
 * Templates persist as a small JSON file in filesDir — deliberately NOT a
 * Room table: templates are app-level writing furniture, not notes, so
 * they stay out of the note list, FTS, sync and the backup schema. Two
 * built-ins seed the file on first use; user templates are added from the
 * editor ("Save as template") and applied from the Templates screen,
 * which creates a real note from the stored HTML.
 */
class TemplateRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, "templates.json")
    private val mutex = Mutex()
    private val _templates = MutableStateFlow<List<NoteTemplate>?>(null)
    val templates: StateFlow<List<NoteTemplate>?> = _templates

    suspend fun load() {
        mutex.withLock {
            if (_templates.value != null) return
            _templates.value = withContext(Dispatchers.IO) {
                if (file.exists()) {
                    runCatching { decode(file.readText()) }.getOrElse { builtIns() }
                } else {
                    builtIns().also { persist(it) }
                }
            }
        }
    }

    suspend fun add(name: String, title: String, contentHtml: String): NoteTemplate {
        load()
        val template = NoteTemplate(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Untitled template" },
            title = title,
            contentHtml = contentHtml,
        )
        mutex.withLock {
            val updated = (_templates.value ?: emptyList()) + template
            _templates.value = updated
            withContext(Dispatchers.IO) { persist(updated) }
        }
        return template
    }

    suspend fun delete(id: String) {
        load()
        mutex.withLock {
            val updated = (_templates.value ?: emptyList()).filterNot { it.id == id }
            _templates.value = updated
            withContext(Dispatchers.IO) { persist(updated) }
        }
    }

    private fun persist(list: List<NoteTemplate>) {
        val array = JSONArray()
        for (t in list) {
            array.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("title", t.title)
                    .put("contentHtml", t.contentHtml)
                    .put("builtIn", t.builtIn),
            )
        }
        file.writeText(array.toString())
    }

    private fun decode(text: String): List<NoteTemplate> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            NoteTemplate(
                id = o.getString("id"),
                name = o.getString("name"),
                title = o.optString("title"),
                contentHtml = o.optString("contentHtml"),
                builtIn = o.optBoolean("builtIn"),
            )
        }
    }

    private fun builtIns(): List<NoteTemplate> = listOf(
        NoteTemplate(
            id = "builtin-meeting",
            name = "Meeting notes",
            title = "Meeting — ",
            contentHtml = "<h2>Agenda</h2><p></p><h2>Notes</h2><p></p>" +
                "<h2>Decisions</h2><ul><li><p></p></li></ul>" +
                "<h2>Action items</h2>" +
                "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p></p></div></li></ul>",
            builtIn = true,
        ),
        NoteTemplate(
            id = "builtin-journal",
            name = "Daily journal",
            title = "",
            contentHtml = "<h2>Today I…</h2><p></p><h2>Grateful for</h2><p></p>" +
                "<h2>Tomorrow</h2><p></p>",
            builtIn = true,
        ),
    )
}
