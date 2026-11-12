package app.nota.data.reminders

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Phase 7 (PR #53): the repeat rule behind
 * [app.nota.data.db.ReminderEntity.repeatRule] — dormant since 5.1,
 * now interpreted. The stored encoding is deliberately tiny:
 *
 * - `"DAILY"` — every day.
 * - `"WEEKLY"` — the anchor trigger's weekday.
 * - `"DAYS:1,3,5"` — the listed ISO day numbers (Mon=1..Sun=7),
 *   sorted, de-duplicated.
 *
 * Each form may carry the series' intended local time as an
 * `"@HH:MM"` suffix (`"DAILY@02:30"`, `"DAYS:1,3,5@09:15"`):
 * occurrences then ring at that time on every matching day, so a
 * daylight-saving gap — whose occurrence resolves to the moment
 * the clocks land, 03:00 say, and is then persisted as the new
 * anchor — can never re-base the series onto the resolved time
 * (Codex PR #53 round 2). Encodings WITHOUT the suffix are the
 * legacy form: the time of day is derived from the anchor
 * instant, exactly as before. A malformed suffix is ignored (the
 * rule survives as its legacy form), never a reason to drop an
 * otherwise valid rule to a one-shot.
 *
 * Anything else (null, blank, unparseable) is NO repeat: the
 * reminder keeps exact one-shot semantics, so a corrupt or foreign
 * value fails safe to the pre-5.1 behaviour. The rule is
 * DEVICE-LOCAL like alertType and the tone quartet (PR #45): it is
 * never written into the backup wire format (1.x carries no repeat),
 * a sync merge carries the local row's rule over, and a restored
 * reminder comes back one-shot.
 *
 * Occurrence math is pure and zone-explicit: an occurrence carries
 * the series' local time of day — the encoding's `@HH:MM` when it
 * carries one, otherwise the anchor's — on a matching day, resolved
 * through [ReminderTriggerTimes.resolveEpochMillis] so repeats
 * inherit the same deliberate DST rules as a picked trigger (gap →
 * the moment the clocks land; overlap → the earlier offset).
 */
object ReminderRepeat {

    /** A parsed repeat rule. */
    sealed interface Rule {
        /** The ISO day numbers (Mon=1..Sun=7) the rule fires on,
         *  resolved against the anchor for [Weekly]. */
        fun daysIso(anchorTriggerAt: Long, zone: ZoneId): Set<Int>

        data object Daily : Rule {
            override fun daysIso(anchorTriggerAt: Long, zone: ZoneId): Set<Int> =
                (1..7).toSet()
        }

        data object Weekly : Rule {
            override fun daysIso(anchorTriggerAt: Long, zone: ZoneId): Set<Int> =
                setOf(
                    Instant.ofEpochMilli(anchorTriggerAt).atZone(zone)
                        .dayOfWeek.value,
                )
        }

        data class CustomDays(val days: Set<Int>) : Rule {
            override fun daysIso(anchorTriggerAt: Long, zone: ZoneId): Set<Int> =
                days
        }
    }

    private const val DAILY = "DAILY"
    private const val WEEKLY = "WEEKLY"
    private const val DAYS_PREFIX = "DAYS:"

    /** A well-formed series-time suffix: strict 24-hour HH:MM. */
    private val TIME_SUFFIX = Regex("""([01]\d|2[0-3]):([0-5]\d)""")

    /**
     * A parsed stored encoding: the [rule] plus the series'
     * intended local [time] when the encoding carries one (the
     * `@HH:MM` suffix). A null [time] is the legacy form — the
     * series' time of day comes from the anchor instant instead.
     */
    data class Series(val rule: Rule, val time: LocalTime? = null)

    /**
     * Parses the stored encoding into rule + series time; null
     * for no/unknown repeat. A malformed `@…` suffix is ignored —
     * the rule survives as its legacy (anchor-timed) form rather
     * than silently degrading to a one-shot — but a body that does
     * not parse stays unparseable, suffix or not.
     */
    fun parseSeries(encoded: String?): Series? {
        val raw = encoded?.trim()?.uppercase().orEmpty()
        if (raw.isEmpty()) return null
        val rule = parseRuleBody(raw.substringBefore('@')) ?: return null
        val time = TIME_SUFFIX.matchEntire(raw.substringAfter('@', ""))
            ?.let { match ->
                LocalTime.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                )
            }
        return Series(rule, time)
    }

    private fun parseRuleBody(raw: String): Rule? = when {
        raw == DAILY -> Rule.Daily
        raw == WEEKLY -> Rule.Weekly
        raw.startsWith(DAYS_PREFIX) -> {
            val days = raw.removePrefix(DAYS_PREFIX)
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSortedSet()
            if (days.isEmpty()) null else Rule.CustomDays(days)
        }
        else -> null
    }

    /** Parses the stored encoding's rule; null for no/unknown
     *  repeat. Any `@HH:MM` series time is not part of the rule —
     *  see [parseSeries]. */
    fun parse(encoded: String?): Rule? = parseSeries(encoded)?.rule

    /**
     * The canonical stored encoding for [rule]; null for none.
     * With [time], the series' intended local time rides along as
     * the `@HH:MM` suffix (zero-padded 24-hour); without it, the
     * legacy suffix-less form is produced.
     */
    fun encode(rule: Rule?, time: LocalTime? = null): String? {
        val body = when (rule) {
            null -> null
            Rule.Daily -> DAILY
            Rule.Weekly -> WEEKLY
            is Rule.CustomDays -> {
                val days = rule.days.filter { it in 1..7 }.sorted()
                if (days.isEmpty()) {
                    null
                } else {
                    days.joinToString(",", prefix = DAYS_PREFIX)
                }
            }
        } ?: return null
        if (time == null) return body
        return "$body@${twoDigits(time.hour)}:${twoDigits(time.minute)}"
    }

    private fun twoDigits(value: Int): String =
        value.toString().padStart(2, '0')

    /**
     * The ISO day set a stored rule fires on, resolved against the
     * anchor (only [Rule.Weekly] needs it); null for no repeat.
     */
    fun daysIso(encoded: String?, anchorTriggerAt: Long, zone: ZoneId): Set<Int>? =
        parse(encoded)?.daysIso(anchorTriggerAt, zone)

    /** The ISO day set for a parsed rule; null for no repeat. */
    fun daysIso(rule: Rule?, anchorTriggerAt: Long, zone: ZoneId): Set<Int>? =
        rule?.daysIso(anchorTriggerAt, zone)

    /**
     * The first occurrence of the series STRICTLY after
     * [afterMillis], carrying the anchor's local time of day on the
     * rule's days. The anchor itself is the series' first occurrence:
     * if it is still ahead of [afterMillis] it is returned as-is.
     * Null for no/unknown rule.
     */
    fun nextOccurrence(
        anchorTriggerAt: Long,
        rule: Rule?,
        afterMillis: Long,
        zone: ZoneId,
    ): Long? = nextOccurrence(anchorTriggerAt, rule, afterMillis, zone, null)

    /**
     * The occurrence core. [carriedTime] — the encoding's `@HH:MM`
     * when it has one — is the time EVERY occurrence rings at; the
     * anchor then supplies only the WEEKLY weekday and the date
     * seed, so a gap-resolved stored instant never re-bases the
     * series. Without a carried time (the legacy form), the time
     * of day is derived from the anchor instant, as before.
     */
    private fun nextOccurrence(
        anchorTriggerAt: Long,
        rule: Rule?,
        afterMillis: Long,
        zone: ZoneId,
        carriedTime: LocalTime?,
    ): Long? {
        if (rule == null) return null
        if (anchorTriggerAt > afterMillis) return anchorTriggerAt
        val days = rule.daysIso(anchorTriggerAt, zone)
        val occurrenceTime = carriedTime
            ?: Instant.ofEpochMilli(anchorTriggerAt).atZone(zone)
                .toLocalTime()
        val startDate = Instant.ofEpochMilli(afterMillis).atZone(zone)
            .toLocalDate()
        // Any non-empty day set matches within the next 7 days; the
        // 8th slot covers today's occurrence having just passed with
        // the next match a full week out.
        for (offset in 0..7) {
            val date = startDate.plusDays(offset.toLong())
            if (date.dayOfWeek.value in days) {
                val occurrence = ReminderTriggerTimes.resolveEpochMillis(
                    date, occurrenceTime, zone,
                )
                if (occurrence > afterMillis) return occurrence
            }
        }
        return null
    }

    /** [nextOccurrence] for a stored encoding. */
    fun nextOccurrence(
        anchorTriggerAt: Long,
        encoded: String?,
        afterMillis: Long,
        zone: ZoneId,
    ): Long? {
        val series = parseSeries(encoded) ?: return null
        return nextOccurrence(
            anchorTriggerAt, series.rule, afterMillis, zone, series.time,
        )
    }

    /**
     * The compact label for lists and menus: "Daily", "Weekly · Tue",
     * "Mon · Wed · Fri". Null for no/unknown repeat (callers show
     * nothing extra). Day names are fixed short English forms,
     * Monday-first.
     */
    fun repeatLabel(encoded: String?, anchorTriggerAt: Long, zone: ZoneId): String? =
        when (val rule = parse(encoded)) {
            null -> null
            Rule.Daily -> "Daily"
            Rule.Weekly -> {
                val day = Instant.ofEpochMilli(anchorTriggerAt).atZone(zone)
                    .dayOfWeek
                "Weekly · ${shortName(day)}"
            }
            is Rule.CustomDays -> {
                val names = rule.days
                    .filter { it in 1..7 }
                    .sorted()
                    .joinToString(" · ") { shortName(DayOfWeek.of(it)) }
                if (names.isBlank()) null else names
            }
        }

    private fun shortName(day: DayOfWeek): String =
        day.getDisplayName(TextStyle.SHORT, Locale.US)
}
