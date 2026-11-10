package app.nota.data.html

/**
 * Phase 6 (nested checklists): pure operations on the task-item
 * TREE behind the editor's nested checklist UI. Items are
 * addressed by PATH — the list of child indices from the top
 * level ([2, 0] is the first child of the third top-level item);
 * an item's depth index is `path.size - 1`.
 *
 * The UI depth cap is [MAX_DEPTH_INDEX]: items may live at depths
 * 0..3 (four levels). The cap gates only the GROWING operations
 * (indent / addChild) — imported trees deeper than the cap still
 * parse, render, toggle, edit and round-trip untouched.
 *
 * Every operation is a pure function returning a new tree; an
 * operation whose path does not resolve, or that the cap forbids,
 * returns the input unchanged.
 */
object TaskTree {

    /** Deepest depth index the UI will create (four levels: 0..3). */
    const val MAX_DEPTH_INDEX = 3

    /** One row of the flattened tree the editor renders. */
    data class FlatItem(val path: List<Int>, val depth: Int, val item: TaskItem)

    /** The tree flattened in reading order (parent, then children). */
    fun flatten(items: List<TaskItem>): List<FlatItem> {
        val out = mutableListOf<FlatItem>()
        fun walk(list: List<TaskItem>, prefix: List<Int>, depth: Int) {
            list.forEachIndexed { index, item ->
                val path = prefix + index
                out += FlatItem(path, depth, item)
                walk(item.children, path, depth + 1)
            }
        }
        walk(items, emptyList(), 0)
        return out
    }

    /** The item at [path], or null when the path does not resolve. */
    fun itemAt(items: List<TaskItem>, path: List<Int>): TaskItem? {
        if (path.isEmpty()) return null
        var level = items
        var current: TaskItem? = null
        for (index in path) {
            current = level.getOrNull(index) ?: return null
            level = current.children
        }
        return current
    }

    /** The item at [path] with [transform] applied; bad path = input. */
    fun updateAt(
        items: List<TaskItem>,
        path: List<Int>,
        transform: (TaskItem) -> TaskItem,
    ): List<TaskItem> {
        if (path.isEmpty()) return items
        val head = path.first()
        if (head !in items.indices) return items
        return items.mapIndexed { index, item ->
            when {
                index != head -> item
                path.size == 1 -> transform(item)
                else -> item.copy(
                    children = updateAt(item.children, path.drop(1), transform),
                )
            }
        }
    }

    /** Ticks or unticks the item at [path]. */
    fun toggle(items: List<TaskItem>, path: List<Int>, checked: Boolean): List<TaskItem> =
        updateAt(items, path) { it.copy(checked = checked) }

    /**
     * Replaces the item's label text via the editor's text-run
     * surgery ([NoteDocument.replaceTaskItemText]) — inline
     * formatting survives, the N5 rule. Any trailing own content
     * (content that followed the item's nested list) was folded
     * into the label field the user just edited, so it is cleared:
     * keeping it would print that text twice.
     */
    fun setText(items: List<TaskItem>, path: List<Int>, newText: String): List<TaskItem> =
        updateAt(items, path) {
            it.copy(
                contentHtml = NoteDocument.replaceTaskItemText(it.contentHtml, newText),
                contentAfterHtml = "",
            )
        }

    /** Removes the item at [path] and its whole subtree. */
    fun removeAt(items: List<TaskItem>, path: List<Int>): List<TaskItem> {
        if (path.isEmpty()) return items
        val head = path.first()
        if (head !in items.indices) return items
        return items.mapIndexedNotNull { index, item ->
            when {
                index != head -> item
                path.size == 1 -> null
                else -> item.copy(children = removeAt(item.children, path.drop(1)))
            }
        }
    }

    /**
     * Where a [focused] path points after the item at [removed] is
     * removed: null when the focused item was the removed item or
     * inside its subtree; shifted down by one when a preceding
     * sibling — at the top level or at any ancestor level — was the
     * removed one; unchanged otherwise. Keeps the editor's focus
     * bookkeeping aimed at the item the user actually focused
     * (Codex P2, PR #41).
     */
    fun rebasePathAfterRemoval(focused: List<Int>, removed: List<Int>): List<Int>? {
        if (focused.size >= removed.size &&
            focused.subList(0, removed.size) == removed
        ) {
            return null
        }
        val depth = removed.size - 1
        if (focused.size > depth &&
            focused.subList(0, depth) == removed.subList(0, depth) &&
            focused[depth] > removed[depth]
        ) {
            return focused.toMutableList().also { it[depth] = it[depth] - 1 }
        }
        return focused
    }

    /** Inserts [newItem] directly after the item at [path], same level. */
    fun insertAfter(
        items: List<TaskItem>,
        path: List<Int>,
        newItem: TaskItem,
    ): List<TaskItem> {
        if (path.isEmpty()) return items
        val head = path.first()
        if (head !in items.indices) return items
        return if (path.size == 1) {
            items.toMutableList().apply { add(head + 1, newItem) }
        } else {
            updateAt(items, path.dropLast(1)) { parent ->
                parent.copy(children = insertAfter(parent.children, listOf(path.last()), newItem))
            }
        }
    }

    /** Appends a blank sibling directly after the item at [path]. */
    fun addSibling(items: List<TaskItem>, path: List<Int>): List<TaskItem> =
        insertAfter(items, path, TaskItem("<p></p>", checked = false))

    /**
     * Appends a blank child to the item at [path]. Refused when the
     * child would sit deeper than [MAX_DEPTH_INDEX] (the child's
     * depth index is `path.size`).
     */
    fun addChild(items: List<TaskItem>, path: List<Int>): List<TaskItem> {
        if (path.isEmpty() || path.size > MAX_DEPTH_INDEX) return items
        if (itemAt(items, path) == null) return items
        return updateAt(items, path) {
            it.copy(children = it.children + TaskItem("<p></p>", checked = false))
        }
    }

    /** True when [indent] would do something for the item at [path]. */
    fun canIndent(items: List<TaskItem>, path: List<Int>): Boolean {
        if (path.isEmpty() || path.last() <= 0) return false
        val item = itemAt(items, path) ?: return false
        // The item's deepest descendant lands at (path.size - 1) +
        // subtreeHeight after the move (the item itself drops one
        // level); the whole subtree must stay within the cap.
        return path.size - 1 + subtreeHeight(item) <= MAX_DEPTH_INDEX
    }

    /** Levels in [item]'s subtree including itself (1 = no children). */
    private fun subtreeHeight(item: TaskItem): Int =
        1 + (item.children.maxOfOrNull { subtreeHeight(it) } ?: 0)

    /**
     * Nests the item at [path] as the LAST child of its previous
     * sibling. Refused for a first item (no previous sibling) and
     * when the move would push the item past the depth cap.
     */
    fun indent(items: List<TaskItem>, path: List<Int>): List<TaskItem> {
        if (!canIndent(items, path)) return items
        val item = itemAt(items, path) ?: return items
        val removed = removeAt(items, path)
        val previousSibling = path.dropLast(1) + (path.last() - 1)
        return updateAt(removed, previousSibling) { previous ->
            previous.copy(children = previous.children + item)
        }
    }

    /** True when the item at [path] can move one level up. */
    fun canOutdent(items: List<TaskItem>, path: List<Int>): Boolean =
        path.size >= 2 && itemAt(items, path) != null

    /**
     * Moves the item at [path] one level up, to directly AFTER its
     * former parent. Its children travel with it.
     */
    fun outdent(items: List<TaskItem>, path: List<Int>): List<TaskItem> {
        if (!canOutdent(items, path)) return items
        val item = itemAt(items, path) ?: return items
        val removed = removeAt(items, path)
        return insertAfter(removed, path.dropLast(1), item)
    }
}
