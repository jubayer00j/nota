package app.nota.feature.editor

/**
 * Phase 6 (Editor Refinement #3): autolink token logic — pure and
 * Compose-free so the whole rule set is unit-tested without a UI.
 *
 * The semantics mirror what 1.x gets from Tiptap's Link extension
 * (linkifyjs): a URL-shaped token becomes a link when a delimiter
 * is typed after it, or when a paste whose whole content is one
 * URL lands. The audit's narrowing of linkify applies:
 *
 *  - scheme URLs (http / https / ftp) whose host contains a dot
 *    or is localhost (an IPv4 literal also qualifies);
 *  - www. hosts, which need a further dot after the "www.";
 *  - bare domains whose TLD is in the curated list below
 *    (two-part TLDs such as co.uk / com.bd included);
 *  - trailing sentence punctuation (.,;:!?) and unmatched closing
 *    brackets / quotes are NOT part of the link; opening wrapper
 *    brackets / quotes in front of the token aren't either;
 *  - email addresses NEVER linkify (a documented divergence from
 *    linkify — Nota has no mailto affordance in the editor).
 *
 * The stored href is the token as typed for scheme URLs, and
 * "https://" + token for www. / bare tokens.
 */
object AutoLink {

    /** A validated URL token: its range in the source text (after
     *  wrapper/punctuation stripping) + the href to store. */
    data class UrlToken(val range: IntRange, val href: String)

    private val SCHEMES = setOf("http", "https", "ftp")

    /** Curated TLD list for bare domains (single-label). */
    private val SINGLE_TLDS = setOf(
        "com", "org", "net", "io", "co", "app", "dev", "ai", "me", "tv",
        "cc", "xyz", "info", "biz", "edu", "gov", "mil", "int", "pro",
        "site", "online", "tech", "store", "blog", "news", "live",
        "world", "today", "club", "team", "group", "one",
        "bd", "uk", "us", "de", "jp", "in", "au", "ca", "fr", "it",
        "nl", "es", "se", "no", "fi", "dk", "nz", "sg", "hk", "kr",
        "cn", "br", "mx", "za", "ng", "pk", "lk", "np", "mm", "kh",
        "vn", "th", "my", "id", "ph", "ae", "sa", "qa", "kw", "om",
        "bh", "eg", "ke", "gh", "ie", "be", "ch", "at", "pl", "cz",
        "gr", "pt", "ro", "hu", "il", "tr", "ua",
    )

    /** Two-part public suffixes (checked before the single list). */
    private val TWO_PART_TLDS = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.bd", "org.bd", "gov.bd", "ac.bd", "edu.bd", "net.bd",
        "co.in", "com.au", "co.nz", "co.za", "com.sg", "com.hk",
        "com.pk", "co.jp", "co.kr", "com.br", "com.cn", "com.tw",
    )

    private const val TRAILING_PUNCT = ".,;:!?"

    /** Wrapper characters that may precede a pasted/typed URL but
     *  are never part of it: ( [ { " ' ` */
    private const val LEADING_WRAP = "([{\"\'`"

    private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val SCHEME_NAME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

    /**
     * The URL token ending at [endExclusive] in [text], or null.
     * The candidate is the maximal run of non-whitespace characters
     * ending at [endExclusive]; wrapper brackets/quotes and
     * trailing punctuation are stripped before validation, and the
     * returned range covers only the URL itself.
     */
    fun urlTokenEndingAt(text: String, endExclusive: Int): UrlToken? {
        if (endExclusive <= 0 || endExclusive > text.length) return null
        var start = endExclusive
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = endExclusive
        // Strip leading wrapper brackets/quotes.
        while (start < end && text[start] in LEADING_WRAP) start++
        // Strip trailing punctuation: sentence punctuation always;
        // a closing bracket only while it is unmatched within the
        // remaining token (so "Rust_(programming_language)" keeps
        // its closing paren but "(example.com)" loses both).
        while (end > start) {
            val last = text[end - 1]
            val tokenSoFar = text.substring(start, end)
            val strip = when {
                last in TRAILING_PUNCT -> true
                last == '"' || last == '\'' -> true
                last == ')' ->
                    tokenSoFar.count { it == ')' } > tokenSoFar.count { it == '(' }
                last == ']' ->
                    tokenSoFar.count { it == ']' } > tokenSoFar.count { it == '[' }
                last == '}' ->
                    tokenSoFar.count { it == '}' } > tokenSoFar.count { it == '{' }
                else -> false
            }
            if (!strip) break
            end--
        }
        if (start >= end) return null
        val token = text.substring(start, end)
        val href = hrefFor(token) ?: return null
        return UrlToken(start..(end - 1), href)
    }

    /**
     * The trigger detector: given the text before and after one
     * edit, decide whether the edit linkifies a token.
     *
     *  - Delimiter trigger: exactly one space or newline was
     *    inserted and nothing was removed — the token ending right
     *    before the delimiter is the candidate.
     *  - Paste trigger: an inserted chunk of 2+ characters whose
     *    trimmed content is exactly one valid token (the token
     *    must span the whole trimmed chunk in the new text).
     *
     * Anything else (deletions, replacements, multi-char typing
     * that isn't a clean single-token chunk) never linkifies.
     */
    fun triggeredToken(previous: String, current: String): UrlToken? {
        if (previous == current) return null
        val minLen = minOf(previous.length, current.length)
        var prefix = 0
        while (prefix < minLen && previous[prefix] == current[prefix]) prefix++
        var suffix = 0
        while (suffix < minLen - prefix &&
            previous[previous.length - 1 - suffix] ==
            current[current.length - 1 - suffix]
        ) {
            suffix++
        }
        val inserted = current.substring(prefix, current.length - suffix)
        val removed = previous.length - prefix - suffix
        if (inserted.length == 1 && removed == 0 &&
            (inserted[0] == ' ' || inserted[0] == '\n')
        ) {
            // The diff can attribute the inserted delimiter one
            // position late when an identical whitespace character
            // already followed the caret (the token then ends at
            // prefix - 1). The two readings are mutually exclusive:
            // a non-empty run at prefix means the character before
            // it is not whitespace.
            return urlTokenEndingAt(current, prefix)
                ?: if (prefix > 0 && current[prefix - 1].isWhitespace()) {
                    urlTokenEndingAt(current, prefix - 1)
                } else {
                    null
                }
        }
        if (inserted.length >= 2) {
            val lead = inserted.indexOfFirst { !it.isWhitespace() }
            if (lead < 0) return null
            val trail = inserted.indexOfLast { !it.isWhitespace() }
            val chunkStart = prefix + lead
            val chunkEnd = prefix + trail + 1
            val token = urlTokenEndingAt(current, chunkEnd) ?: return null
            // The candidate must be the chunk and nothing but the
            // chunk: a token that reaches back into text that was
            // already there (the paste merely completed a word)
            // is not a pasted URL. Wrapper brackets/quotes and
            // sentence punctuation AROUND the token are fine,
            // though — urlTokenEndingAt strips them from the
            // token, and a paste like "(example.com)." is still
            // one pasted URL (Codex P2, PR #42).
            if (token.range.first < chunkStart ||
                token.range.last > chunkEnd - 1
            ) {
                return null
            }
            val before = current.substring(chunkStart, token.range.first)
            val after = current.substring(token.range.last + 1, chunkEnd)
            val wrappable = before.all { it in LEADING_WRAP } &&
                after.all { it in TRAILING_PUNCT || it in "\"')]}" }
            return if (wrappable) token else null
        }
        return null
    }

    /** Validates a stripped token; returns the href to store. */
    private fun hrefFor(token: String): String? {
        if (token.length < 4) return null
        if (token.any { it == '<' || it == '>' }) return null
        // The scheme separator only counts before any path/query/
        // fragment separator — a "://" inside a path is not a scheme.
        val firstSep = token.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) token.length else it }
        val schemeSep = token.indexOf("://")
        if (schemeSep in 1 until firstSep &&
            SCHEME_NAME.matches(token.substring(0, schemeSep))
        ) {
            val scheme = token.substring(0, schemeSep).lowercase()
            if (scheme !in SCHEMES) return null
            val rest = token.substring(schemeSep + 3)
            val hostPort = rest.substringBefore('/').substringBefore('?')
                .substringBefore('#')
            if (!isSchemeHost(hostPort)) return null
            return token
        }
        // Emails never linkify.
        if (token.contains('@')) return null
        val hostPort = token.substringBefore('/').substringBefore('?')
            .substringBefore('#')
        val host = hostWithoutPort(hostPort) ?: return null
        if (host.lowercase().startsWith("www.")) {
            // A www. host needs a further dot after the prefix.
            if (!host.substring(4).contains('.')) return null
            if (!isDomainHost(host)) return null
            return "https://$token"
        }
        if (!isDomainHost(host)) return null
        return "https://$token"
    }

    /** Host[:port] for a scheme URL: localhost, an IPv4 literal, or
     *  a dotted domain (the audit's scheme rule — a dot suffices,
     *  no TLD list). Userinfo ("user@host") is allowed here. */
    private fun isSchemeHost(hostPort: String): Boolean {
        val host = hostWithoutPort(hostPort.substringAfterLast('@')) ?: return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (IPV4.matches(host)) return true
        if (!host.contains('.')) return false
        return host.split('.').all { LABEL.matches(it) }
    }

    /** A bare/www domain: 2+ valid labels, an optional numeric
     *  port already removed, and a curated-TLD ending. */
    private fun isDomainHost(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (!labels.all { LABEL.matches(it) }) return false
        val lastTwo = labels.takeLast(2).joinToString(".").lowercase()
        if (lastTwo in TWO_PART_TLDS) return labels.size >= 3
        return labels.last().lowercase() in SINGLE_TLDS
    }

    /** Strips a ":digits" port; null when a colon carries anything
     *  else (a malformed host). */
    private fun hostWithoutPort(hostPort: String): String? {
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort
        val port = hostPort.substring(colon + 1)
        if (port.isEmpty() || !port.all { it.isDigit() }) return null
        return hostPort.substring(0, colon)
    }
}
