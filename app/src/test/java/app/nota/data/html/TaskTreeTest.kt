package app.nota.data.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (nested checklists): the pure tree operations behind
 * the editor's nested checklist UI — path addressing, the depth
 * cap on growing operations, and subtree semantics.
 */
class TaskTreeTest {

    private fun item(
        text: String,
        checked: Boolean = false,
        children: List<TaskItem> = emptyList(),
        after: String = "",
    ) = TaskItem("<p>$text</p>", checked, children, after)

    private val tree = listOf(
        item(
            "A",
            children = listOf(
                item("A1"),
                item("A2", checked = true),
            ),
        ),
        item("B"),
        item(
            "C",
            children = listOf(item("C1")),
        ),
    )

    private fun texts(items: List<TaskItem>): List<String> =
        TaskTree.flatten(items).map {
            it.item.contentHtml.removeSurrounding("<p>", "</p>")
        }

    @Test
    fun flatten_walksInReadingOrderWithPathsAndDepths() {
        val flat = TaskTree.flatten(tree)
        assertEquals(
            listOf("A", "A1", "A2", "B", "C", "C1"),
            flat.map { it.item.contentHtml.removeSurrounding("<p>", "</p>") },
        )
        assertEquals(
            listOf(0, 1, 1, 0, 0, 1),
            flat.map { it.depth },
        )
        assertEquals(listOf(0, 1), flat[2].path)
        assertEquals(listOf(2, 0), flat[5].path)
    }

    @Test
    fun itemAt_resolvesAndRejects() {
        assertEquals("<p>A2</p>", TaskTree.itemAt(tree, listOf(0, 1))?.contentHtml)
        assertEquals(null, TaskTree.itemAt(tree, listOf(0, 5)))
        assertEquals(null, TaskTree.itemAt(tree, listOf(9)))
        assertEquals(null, TaskTree.itemAt(tree, emptyList()))
    }

    @Test
    fun toggle_flipsOnlyTheAddressedItem() {
        val out = TaskTree.toggle(tree, listOf(0, 1), checked = false)
        assertFalse(TaskTree.itemAt(out, listOf(0, 1))!!.checked)
        assertFalse(TaskTree.itemAt(out, listOf(0, 0))!!.checked)
        assertEquals(tree[1], out[1])
        // A bad path returns the input unchanged.
        assertEquals(tree, TaskTree.toggle(tree, listOf(7), checked = true))
    }

    @Test
    fun setText_keepsFormattingAndLeavesTrailingContentAlone() {
        // Codex P2 (PR #41): the label field edits ONLY the item's
        // own leading content — trailing content keeps its place
        // after the children and its own formatting, with its own
        // write path (setAfterText).
        val rich = listOf(
            TaskItem(
                "<p><strong>Old</strong></p>",
                checked = false,
                children = listOf(item("Kid")),
                contentAfterHtml = "<p>Tail</p>",
            ),
        )
        val out = TaskTree.setText(rich, listOf(0), "New")
        val edited = out[0]
        assertEquals("<p><strong>New</strong></p>", edited.contentHtml)
        assertEquals("<p>Tail</p>", edited.contentAfterHtml)
        assertEquals(1, edited.children.size)

        val out2 = TaskTree.setAfterText(rich, listOf(0), "New tail")
        assertEquals("<p>New tail</p>", out2[0].contentAfterHtml)
        assertEquals("<p><strong>Old</strong></p>", out2[0].contentHtml)
    }

    @Test
    fun removeAt_deletesTheWholeSubtree() {
        val out = TaskTree.removeAt(tree, listOf(0))
        assertEquals(listOf("B", "C", "C1"), texts(out))
        val out2 = TaskTree.removeAt(tree, listOf(2, 0))
        assertEquals(listOf("A", "A1", "A2", "B", "C"), texts(out2))
        assertTrue(TaskTree.itemAt(out2, listOf(2))!!.children.isEmpty())
    }

    @Test
    fun addSibling_insertsDirectlyAfterAtTheSameLevel() {
        val out = TaskTree.addSibling(tree, listOf(0, 0))
        assertEquals(
            listOf("A", "A1", "", "A2", "B", "C", "C1"),
            texts(out),
        )
        val top = TaskTree.addSibling(tree, listOf(1))
        assertEquals(listOf("A", "A1", "A2", "B", "", "C", "C1"), texts(top))
    }

    @Test
    fun addChild_appendsAndRespectsTheDepthCap() {
        val out = TaskTree.addChild(tree, listOf(1))
        assertEquals(1, TaskTree.itemAt(out, listOf(1))!!.children.size)

        // Depths 0..3 are creatable: a child of the depth-2 item
        // lands at depth 3; a child of the depth-3 item is refused.
        val deep = listOf(
            item("L0", children = listOf(
                item("L1", children = listOf(
                    item("L2", children = listOf(item("L3"))),
                )),
            )),
        )
        val grown = TaskTree.addChild(deep, listOf(0, 0, 0))
        assertEquals(2, TaskTree.itemAt(grown, listOf(0, 0, 0))!!.children.size)
        assertEquals(deep, TaskTree.addChild(deep, listOf(0, 0, 0, 0)))
    }

    @Test
    fun indent_nestsUnderPreviousSibling() {
        val out = TaskTree.indent(tree, listOf(1))
        assertEquals(listOf("A", "A1", "A2", "B", "C", "C1"), texts(out))
        assertEquals(
            listOf("A1", "A2", "B"),
            TaskTree.itemAt(out, listOf(0))!!.children.map {
                it.contentHtml.removeSurrounding("<p>", "</p>")
            },
        )
        assertEquals(2, out.size) // top level is now A, C
    }

    @Test
    fun indent_refusesFirstItemAndCapOverflow() {
        assertEquals(tree, TaskTree.indent(tree, listOf(0)))
        assertEquals(tree, TaskTree.indent(tree, listOf(0, 0)))
        // A depth-3 item cannot sink to depth 4.
        val deep = listOf(
            item("L0", children = listOf(
                item("L1", children = listOf(
                    item("L2", children = listOf(item("L3a"), item("L3b"))),
                )),
            )),
        )
        assertFalse(TaskTree.canIndent(deep, listOf(0, 0, 0, 1)))
        assertEquals(deep, TaskTree.indent(deep, listOf(0, 0, 0, 1)))
    }

    @Test
    fun indent_refusesWhenSubtreeWouldPassTheCap() {
        // Bao review (PR #41): the cap applies to the item's deepest
        // descendant, not just the item — a depth-2 parent whose
        // child already sits at depth 3 cannot sink, while a depth-2
        // leaf next to it still can.
        val tree2 = listOf(
            item("A", children = listOf(
                item("A1", children = listOf(
                    item("A1a"),
                    item("A1b", children = listOf(item("B1"))),
                    item("A1c"),
                )),
            )),
        )
        assertFalse(TaskTree.canIndent(tree2, listOf(0, 0, 1)))
        assertEquals(tree2, TaskTree.indent(tree2, listOf(0, 0, 1)))
        assertTrue(TaskTree.canIndent(tree2, listOf(0, 0, 2)))
    }

    @Test
    fun rebasePathAfterRemoval_clearsShiftsOrKeeps() {
        // Codex P2 (PR #41): focus bookkeeping after a removal.
        // The removed item itself, and anything inside its subtree.
        assertEquals(null, TaskTree.rebasePathAfterRemoval(listOf(1), listOf(1)))
        assertEquals(null, TaskTree.rebasePathAfterRemoval(listOf(1, 0), listOf(1)))
        assertEquals(null, TaskTree.rebasePathAfterRemoval(listOf(0, 1), listOf(0, 1)))
        // A later sibling shifts down, at the top level and nested.
        assertEquals(listOf(1), TaskTree.rebasePathAfterRemoval(listOf(2), listOf(0)))
        assertEquals(
            listOf(0, 1),
            TaskTree.rebasePathAfterRemoval(listOf(0, 2), listOf(0, 0)),
        )
        assertEquals(
            listOf(0, 0),
            TaskTree.rebasePathAfterRemoval(listOf(1, 0), listOf(0)),
        )
        // An earlier sibling, an ancestor, and other subtrees stay.
        assertEquals(listOf(0), TaskTree.rebasePathAfterRemoval(listOf(0), listOf(1)))
        assertEquals(listOf(0), TaskTree.rebasePathAfterRemoval(listOf(0), listOf(0, 0)))
        assertEquals(
            listOf(0, 0),
            TaskTree.rebasePathAfterRemoval(listOf(0, 0), listOf(1)),
        )
    }

    @Test
    fun outdent_movesItemAfterItsParent_subtreeTravels() {
        val out = TaskTree.outdent(tree, listOf(0, 0))
        assertEquals(listOf("A", "A2", "A1", "B", "C", "C1"), texts(out))
        assertEquals(listOf(1), TaskTree.flatten(out)[2].path)

        val withKids = listOf(
            item("P", children = listOf(item("K", children = listOf(item("G"))))),
            item("Q"),
        )
        val lifted = TaskTree.outdent(withKids, listOf(0, 0))
        assertEquals(listOf("P", "K", "G", "Q"), texts(lifted))
        assertEquals(1, TaskTree.itemAt(lifted, listOf(1))!!.children.size)
        // Top-level items cannot outdent.
        assertEquals(tree, TaskTree.outdent(tree, listOf(1)))
    }
}
