package app.nota.data.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (nested checklists): nested task lists are a real TREE
 * in the model now, and unedited data must round-trip
 * BYTE-IDENTICALLY. The shapes pinned here:
 *
 * 1. Nota's canonical shape (what Nota's serialiser and 1.x's
 *    stored form both produce): the nested
 *    <ul data-type="taskList"> sits inside the parent <li>'s <div>,
 *    after the item's own <p> — byte identity.
 * 2. The 1.x raw Tiptap shape (label/input checkbox scaffolding,
 *    data-checked before data-type): the scaffolding is stripped
 *    on parse, exactly as for flat items, and the list serialises
 *    to the canonical nested shape — segment-stable from there.
 * 3. Two levels of nesting (grandchildren), canonical — byte
 *    identity at every level.
 * 4. A nested list that is NOT the item's last element (own
 *    content on both sides): its position is preserved, never
 *    relocated to the end.
 * 5. The bare dialect (no <div>, no data-type on the <ul>,
 *    classified structurally by its items): children split out the
 *    same way; serialisation canonicalises like the flat dialect.
 * 6. A nested NON-task list inside a task item is not a checklist:
 *    it stays in the item's own HTML, byte-identical.
 */
class NoteDocumentNestedTest {

    private val canonicalNested =
        "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Parent</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>Child done</p></div></li>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Child open</p></div></li>" +
            "</ul></div></li>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Sibling</p></div></li></ul>"

    @Test
    fun canonicalNestedList_splitsIntoTree() {
        val list = NoteDocument.parse(canonicalNested).single() as TaskListSegment
        assertEquals(2, list.items.size)
        val parent = list.items[0]
        assertEquals("<p>Parent</p>", parent.contentHtml)
        assertEquals("", parent.contentAfterHtml)
        assertEquals(2, parent.children.size)
        assertTrue(parent.children[0].checked)
        assertEquals("<p>Child done</p>", parent.children[0].contentHtml)
        assertTrue(!parent.children[1].checked)
        assertEquals("<p>Sibling</p>", list.items[1].contentHtml)
        assertTrue(list.items[1].children.isEmpty())
    }

    @Test
    fun canonicalNestedList_roundTripsByteIdentical() {
        val segments = NoteDocument.parse(canonicalNested)
        assertEquals(canonicalNested, NoteDocument.serialize(segments))
        assertEquals(
            segments,
            NoteDocument.parse(NoteDocument.serialize(segments)),
        )
    }

    @Test
    fun rawTiptapNestedList_canonicalisesAndStabilises() {
        val raw = "<ul data-type=\"taskList\">" +
            "<li data-checked=\"false\" data-type=\"taskItem\">" +
            "<label><input type=\"checkbox\"><span></span></label>" +
            "<div><p>Parent</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-checked=\"true\" data-type=\"taskItem\">" +
            "<label><input type=\"checkbox\" checked=\"checked\"><span></span></label>" +
            "<div><p>Child done</p></div></li>" +
            "</ul></div></li></ul>"
        val segments = NoteDocument.parse(raw)
        val list = segments.single() as TaskListSegment
        assertEquals(1, list.items[0].children.size)
        assertTrue(list.items[0].children[0].checked)
        val canonical = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Parent</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>Child done</p></div></li>" +
            "</ul></div></li></ul>"
        assertEquals(canonical, NoteDocument.serialize(segments))
        assertEquals(segments, NoteDocument.parse(canonical))
    }

    @Test
    fun twoLevelsDeep_roundTripsByteIdentical() {
        val deep = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>L0</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>L1</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>L2</p></div></li>" +
            "</ul></div></li></ul></div></li></ul>"
        val segments = NoteDocument.parse(deep)
        val list = segments.single() as TaskListSegment
        val grandchild = list.items[0].children[0].children[0]
        assertEquals("<p>L2</p>", grandchild.contentHtml)
        assertTrue(grandchild.checked)
        assertEquals(deep, NoteDocument.serialize(segments))
    }

    @Test
    fun nestedListMidContent_keepsItsPosition() {
        val mid = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Before</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Kid</p></div></li>" +
            "</ul><p>After</p></div></li></ul>"
        val segments = NoteDocument.parse(mid)
        val item = (segments.single() as TaskListSegment).items.single()
        assertEquals("<p>Before</p>", item.contentHtml)
        assertEquals("<p>After</p>", item.contentAfterHtml)
        assertEquals(1, item.children.size)
        // Serialisation puts the nested list back BETWEEN the two
        // paragraphs — byte-identical, not relocated to the end.
        assertEquals(mid, NoteDocument.serialize(segments))
    }

    @Test
    fun removingLastChild_keepsTrailingContent() {
        // Codex P1 (PR #41): once the only child is removed, the
        // item is childless but still carries its trailing content
        // in contentAfterHtml — serialisation must not drop it.
        val mid = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Before</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Kid</p></div></li>" +
            "</ul><p>After</p></div></li></ul>"
        val list = NoteDocument.parse(mid).single() as TaskListSegment
        val pruned = TaskTree.removeAt(list.items, listOf(0, 0))
        assertEquals(
            "<ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\">" +
                "<div><p>Before</p><p>After</p></div></li></ul>",
            NoteDocument.serialize(listOf(TaskListSegment(pruned))),
        )
    }

    @Test
    fun bareDialectNestedList_splitsAndCanonicalises() {
        val bare = "<ul>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><p>Parent</p>" +
            "<ul><li data-type=\"taskItem\" data-checked=\"true\"><p>Kid</p></li></ul>" +
            "</li></ul>"
        val segments = NoteDocument.parse(bare)
        val list = segments.filterIsInstance<TaskListSegment>().single()
        assertEquals(1, list.items[0].children.size)
        assertTrue(list.items[0].children[0].checked)
        val once = NoteDocument.serialize(segments)
        assertEquals(segments, NoteDocument.parse(once))
        assertEquals(once, NoteDocument.serialize(NoteDocument.parse(once)))
    }

    @Test
    fun nestedPlainListInsideTaskItem_staysOwnContent() {
        val html = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Parent</p>" +
            "<ul><li>bullet</li></ul></div></li></ul>"
        val segments = NoteDocument.parse(html)
        val item = (segments.single() as TaskListSegment).items.single()
        assertTrue(item.children.isEmpty())
        assertEquals(html, NoteDocument.serialize(segments))
    }

    @Test
    fun plainTextProjectionIncludesChildrenInOrder() {
        val text = NoteDocument.plainText(NoteDocument.parse(canonicalNested))
        assertTrue(text.contains("Parent"))
        assertTrue(text.contains("Child done"))
        assertTrue(text.contains("Child open"))
        assertTrue(text.contains("Sibling"))
        assertTrue(
            text.indexOf("Parent") < text.indexOf("Child done") &&
                text.indexOf("Child open") < text.indexOf("Sibling"),
        )
    }

    @Test
    fun plainTextProjectionPutsTrailingContentAfterChildren() {
        // Codex P2 (PR #41): the projection follows the stored
        // reading order — own content, children, THEN the trailing
        // content that followed the nested list.
        val mid = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Before</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Kid</p></div></li>" +
            "</ul><p>After</p></div></li></ul>"
        val text = NoteDocument.plainText(NoteDocument.parse(mid))
        assertTrue(
            text.indexOf("Before") < text.indexOf("Kid") &&
                text.indexOf("Kid") < text.indexOf("After"),
        )
    }
}
