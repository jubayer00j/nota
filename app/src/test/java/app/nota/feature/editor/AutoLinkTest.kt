package app.nota.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6 (Editor Refinement #3): the autolink token rules —
 * linkify semantics narrowed by the chapter audit (no emails,
 * curated TLD list for bare domains, https:// prefix for
 * www./bare tokens, href as typed for scheme tokens).
 */
class AutoLinkTest {

    private fun token(text: String, end: Int = text.length) =
        AutoLink.urlTokenEndingAt(text, end)

    // ------------------------------------------------ token shapes

    @Test
    fun schemeUrls_keepTypedHref() {
        assertEquals(
            "https://example.com/a?b=c",
            token("https://example.com/a?b=c")?.href,
        )
        assertEquals("http://example.com", token("http://example.com")?.href)
        assertEquals("ftp://files.example.com/x", token("ftp://files.example.com/x")?.href)
        // An unknown scheme is not a URL token at all.
        assertNull(token("javascript://example.com"))
        assertNull(token("ssh://example.com"))
    }

    @Test
    fun schemeUrls_needDottedHostOrLocalhost() {
        assertNull(token("https://example"))
        assertEquals(
            "https://example.com",
            token("prefix https://example.com")?.href,
        )
        assertEquals("http://localhost:8080/x", token("http://localhost:8080/x")?.href)
        assertEquals("http://127.0.0.1:8080", token("http://127.0.0.1:8080")?.href)
        // Bare localhost has no TLD — not a bare domain.
        assertNull(token("localhost:8080"))
    }

    @Test
    fun wwwHosts_getHttpsPrefix_andNeedAFurtherDot() {
        val www = token("see www.example.com end", 19)
        assertEquals("https://www.example.com", www?.href)
        assertEquals(4..18, www?.range)
        assertNull(token("www.example"))
        assertNull(token("www.com"))
    }

    @Test
    fun bareDomains_curatedTldAcceptAndReject() {
        assertEquals("https://example.com", token("example.com")?.href)
        assertEquals("https://example.org", token("example.org")?.href)
        assertEquals("https://sub.example.io/path", token("sub.example.io/path")?.href)
        assertEquals("https://example.com.bd", token("example.com.bd")?.href)
        assertEquals("https://example.co.uk", token("example.co.uk")?.href)
        // Unlisted endings are not domains Nota linkifies.
        assertNull(token("file.txt"))
        assertNull(token("hello.qqq"))
        assertNull(token("notes.local"))
        // A lone label is never a domain.
        assertNull(token("example"))
        assertNull(token("com"))
    }

    @Test
    fun bareDomains_portsPathsQueriesSurvive() {
        assertEquals(
            "https://example.com:8080/a/b?x=1#frag",
            token("example.com:8080/a/b?x=1#frag")?.href,
        )
        // A trailing sentence dot is stripped even after a path.
        assertEquals("https://example.com/a", token("example.com/a.")?.href)
    }

    @Test
    fun trailingPunctuation_isStripped() {
        val dotted = token("see example.com.")
        assertEquals("https://example.com", dotted?.href)
        // The range excludes the stripped dot: "see " is 4 chars,
        // the domain is 11 — 4..14, not 4..15.
        assertEquals(4..14, dotted?.range)
        assertEquals("https://example.com", token("example.com,")?.href)
        assertEquals("https://example.com", token("example.com!")?.href)
        assertEquals("https://example.com", token("example.com?")?.href)
        assertEquals("https://example.com/a", token("example.com/a:")?.href)
    }

    @Test
    fun wrappingBracketsAndQuotes_areStripped() {
        val paren = token("(example.com)")
        assertEquals("https://example.com", paren?.href)
        assertEquals(1..11, paren?.range)
        assertEquals("https://example.com", token("\"example.com\"")?.href)
        assertEquals("https://example.com", token("[example.com]")?.href)
        // A MATCHED closing bracket inside the URL stays.
        assertEquals(
            "https://en.wikipedia.org/wiki/Rust_(programming_language)",
            token("https://en.wikipedia.org/wiki/Rust_(programming_language)")?.href,
        )
        // An UNMATCHED closer after it does not.
        assertEquals(
            "https://example.com",
            token("(see https://example.com)")?.href,
        )
    }

    @Test
    fun maximalRunRule_midWordTokenIsTheToken() {
        // The maximal run of non-whitespace IS the token: text
        // glued to a domain becomes part of the host.
        assertEquals("https://xexample.com", token("xexample.com")?.href)
        // And a token never reaches back past whitespace.
        val mid = token("go to example.com now", 17)
        assertEquals(6..16, mid?.range)
        assertEquals("https://example.com", mid?.href)
    }

    @Test
    fun emails_neverLinkify() {
        assertNull(token("user@example.com"))
        assertNull(token("write to user@example.com"))
        assertNull(token("www.user@example.com"))
    }

    @Test
    fun emptyAndShortInputs() {
        assertNull(AutoLink.urlTokenEndingAt("", 0))
        assertNull(AutoLink.urlTokenEndingAt("abc", 0))
        assertNull(AutoLink.urlTokenEndingAt("abc", 5))
        assertNull(token("a.b"))
        assertNull(token("   "))
        assertNull(token("see ", 4))
    }

    // ------------------------------------------------ triggers

    @Test
    fun delimiterTrigger_typedSpaceOrNewline() {
        val space = AutoLink.triggeredToken("see example.com", "see example.com ")
        assertEquals("https://example.com", space?.href)
        assertEquals(4..14, space?.range)
        val newline = AutoLink.triggeredToken("example.com", "example.com\n")
        assertEquals("https://example.com", newline?.href)
        // A space after a non-URL word does nothing.
        assertNull(AutoLink.triggeredToken("hello world", "hello world "))
        // A space REPLACING a selection is not a typed delimiter.
        assertNull(AutoLink.triggeredToken("example.comX", "example.com "))
        // Deletions never trigger.
        assertNull(AutoLink.triggeredToken("example.com ", "example.com"))
        // Identical-whitespace ambiguity: a space typed after the
        // token while another space already followed it.
        val shifted = AutoLink.triggeredToken(
            "example.com and",
            "example.com  and",
        )
        assertEquals("https://example.com", shifted?.href)
    }

    @Test
    fun pasteTrigger_wholeChunkIsOneToken() {
        val pasted = AutoLink.triggeredToken("", "example.com")
        assertEquals("https://example.com", pasted?.href)
        assertEquals(0..10, pasted?.range)
        // Surrounding whitespace in the chunk is fine.
        val padded = AutoLink.triggeredToken("see ", "see  example.com ")
        assertEquals("https://example.com", padded?.href)
        // A chunk with more than the URL is not a URL paste.
        assertNull(AutoLink.triggeredToken("", "see example.com"))
        assertNull(AutoLink.triggeredToken("", "example.com and.org"))
        // A paste that merely completes an existing word is not a
        // pasted URL either (the token reaches into old text).
        assertNull(AutoLink.triggeredToken("see example.co", "see example.com"))
        // Typing a letter right after a complete URL never
        // linkifies — only the delimiter after it does.
        assertNull(AutoLink.triggeredToken("see example.com", "see example.comx"))
    }
}
