package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #46 fix round (Codex round 1): the generation discipline of
 * the semantic index's in-memory cache slot. A cold load reads the
 * index file outside any lock; a reconcile storing a NEWER index
 * while that read is in flight must not be overwritten when the
 * stale load lands — otherwise searches keep ranking against old
 * vectors until the next write.
 */
class SemanticIndexCacheSlotTest {

    private fun index(signature: String) =
        StoredSemanticIndex(signature, 2, emptyMap())

    @Test
    fun missThenFillInstallsAndHits() {
        val slot = SemanticIndexCacheSlot()
        val miss = slot.get("acct")
        assertFalse(miss.hit)

        val loaded = index("v1")
        val used = slot.fill("acct", loaded, miss.generation)
        assertSame(loaded, used)

        val hit = slot.get("acct")
        assertTrue(hit.hit)
        assertSame(loaded, hit.index)
    }

    @Test
    fun aCachedNullIndexIsAHitNotAMiss() {
        val slot = SemanticIndexCacheSlot()
        slot.store("acct", null)
        val lookup = slot.get("acct")
        assertTrue(lookup.hit)
        assertNull(lookup.index)
    }

    @Test
    fun coldLoadDoesNotClobberAFresherStore() {
        val slot = SemanticIndexCacheSlot()
        // The cold load begins (miss, generation captured)…
        val miss = slot.get("acct")
        assertFalse(miss.hit)
        // …a reconcile stores a newer index while it reads disk…
        val fresh = index("v2")
        slot.store("acct", fresh)
        // …and the stale load lands: it must NOT be installed, and
        // the caller must be handed the fresher entry instead.
        val stale = index("v1")
        val used = slot.fill("acct", stale, miss.generation)
        assertSame(fresh, used)
        assertSame(fresh, slot.get("acct").index)
    }

    @Test
    fun fillAfterAnotherAccountsStoreReturnsLoadedUncached() {
        val slot = SemanticIndexCacheSlot()
        val miss = slot.get("acct-a")
        assertFalse(miss.hit)
        // A store for a DIFFERENT account intervenes (account
        // switch): the loaded value is still the caller's answer,
        // but it must not evict the other account's entry.
        val other = index("other")
        slot.store("acct-b", other)

        val loaded = index("v1")
        val used = slot.fill("acct-a", loaded, miss.generation)
        assertSame(loaded, used)
        assertFalse(slot.get("acct-a").hit)
        assertSame(other, slot.get("acct-b").index)
    }

    @Test
    fun storeReplacesTheEntryAndBumpsTheGeneration() {
        val slot = SemanticIndexCacheSlot()
        val first = index("v1")
        slot.store("acct", first)
        val before = slot.get("acct")
        assertEquals(first, before.index)

        val second = index("v2")
        slot.store("acct", second)
        val after = slot.get("acct")
        assertSame(second, after.index)
        assertTrue(after.generation > before.generation)
    }
}
