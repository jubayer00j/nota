package app.nota.data.reminders

import java.time.DayOfWeek
import java.time.Instant
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
 * Anything else (null, blank, unparseable) is NO repeat: the
 * reminder keeps exact one-shot semantics, so a corrupt or foreign
 * value fails safe to the pre-5.1 behaviour. The rule is
 * DEVICE-LOCAL like alertType and the tone quartet (PR #45): it is
 * never written into the backup wire format (1.x carries no repeat),
 * a sync merge carries the local row's rule over, and a restored
 * reminder comes back one-shot.
 *
 * Occurrence math is pure and zone-explicit: an occurrence carries
 * the anchor's LOCAL time of day on a matching day, resolved through
 * [ReminderTriggerTimes.resolveEpochMillis] so repeats inherit the
 * same deliberate DST rules as a picked trigger (gap → the moment
 * the clocks land; overlap → the earlier offset).
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

    /** Parses the stored encoding; null for no/unknown repeat. */
    fun parse(encoded: String?): Rule? {
        val raw = encoded?.trim()?.uppercase().orEmpty()
        return when {
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
    }

    /** The canonical stored encoding for [rule]; null for none. */
    fun encode(rule: Rule?): String? = when (rule) {
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
    }

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
    ): Long? {
        if (rule == null) return null
        if (anchorTriggerAt > afterMillis) return anchorTriggerAt
        val days = rule.daysIso(anchorTriggerAt, zone)
        val anchorTime = Instant.ofEpochMilli(anchorTriggerAt).atZone(zone)
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
                    date, anchorTime, zone,
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
    ): Long? = nextOccurrence(anchorTriggerAt, parse(encoded), afterMillis, zone)

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
