package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.CALENDAR_SIGIL_STRUCT_SYNTAX
import org.elixir_lang.language_level.ElixirLanguageFeature.ISO_DATE_UNBOUNDED
import org.elixir_lang.language_level.ElixirLanguageFeature.ISO_PARSES_SIGNED_YEAR
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger
import java.time.LocalDate

/** `Kernel.sigil_D/2`. */
internal val SIGIL_D = calendarSigil("Date", { text, level -> IsoParser(text, level).date() }) { s, p -> dateFields(s, p) }

/** `Kernel.sigil_T/2`. */
internal val SIGIL_T = calendarSigil("Time", { text, level -> IsoParser(text, level).time() }) { s, p -> timeFields(s, p) }

/** `Kernel.sigil_N/2`. */
internal val SIGIL_N =
    calendarSigil("NaiveDateTime", { text, level -> IsoParser(text, level).naiveDateTime() }) { s, p -> dateFields(s, p) + timeFields(s, p) }

/** `Kernel.sigil_U/2`: a UTC offset of zero, or none after `Z`. */
internal val SIGIL_U =
    calendarSigil("DateTime", { text, level -> IsoParser(text, level).utcDateTime() }) { s, p ->
        dateFields(s, p) + timeFields(s, p) + listOf(
            "time_zone" to ElixirAst.Literal.Binary(s.meta(), "Etc/UTC".toByteArray()),
            "zone_abbr" to ElixirAst.Literal.Binary(s.meta(), "UTC".toByteArray()),
            "utc_offset" to s.integer(BigInteger.ZERO),
            "std_offset" to s.integer(BigInteger.ZERO),
        )
    }

/**
 * A calendar sigil of struct [module]: the text up to a trailing calendar name, parsed as `Calendar.ISO` parses it into
 * [fields]. Any other calendar is `Unported`, as it runs that calendar's own parser.
 */
private fun calendarSigil(
    module: String,
    parse: (ByteArray, ElixirLanguageLevel) -> Iso,
    fields: (Synthetic, Iso.Parsed) -> List<Pair<String, ElixirAst>>,
): Summary.Rewrite =
    object : Summary.Rewrite() {
        override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
            val sigil = sigil(node)?.takeIf { it.hasNoModifiers && it.pieces.size == 1 } ?: return SIGIL_NO_CLAUSE
            val text = sigil.text?.bytes ?: return Summary.Output.Unported(node)
            val iso = isoText(text) ?: return Summary.Output.Unported(node)

            return when (val parsed = parse(iso, run.level)) {
                is Iso.Failed -> Summary.Output.Raised(parsed.kind)
                Iso.Unsupported -> Summary.Output.Unported(node)
                is Iso.Parsed -> {
                    val s = Synthetic(node.meta)
                    val pairs = (listOf("calendar" to s.atom("Elixir.Calendar.ISO")) + fields(s, parsed))
                        .map { (key, value) -> s.tuple(s.atom(key), value) }
                    val struct = if (CALENDAR_SIGIL_STRUCT_SYNTAX.isSufficient(run.level)) {
                        s.call("%", listOf(s.atom("Elixir.$module"), s.call("%{}", pairs)))
                    } else {
                        s.call("%{}", listOf(s.tuple(s.atom("__struct__"), s.atom("Elixir.$module"))) + pairs)
                    }

                    Summary.Output.Built(struct)
                }
            }
        }
    }

private fun dateFields(s: Synthetic, parsed: Iso.Parsed) =
    listOf("year" to integer(s, parsed.year), "month" to integer(s, parsed.month), "day" to integer(s, parsed.day))

private fun timeFields(s: Synthetic, parsed: Iso.Parsed) =
    listOf(
        "hour" to integer(s, parsed.hour),
        "minute" to integer(s, parsed.minute),
        "second" to integer(s, parsed.second),
        "microsecond" to s.tuple(integer(s, parsed.microsecond), integer(s, parsed.precision)),
    )

private fun integer(s: Synthetic, value: Int) = s.integer(BigInteger.valueOf(value.toLong()))

/** `extract_calendar/1`: the text before a trailing calendar name, or `null` where the calendar is not `Calendar.ISO`. */
private fun isoText(text: ByteArray): ByteArray? {
    val lastSpace = text.lastIndexOf(' '.code.toByte())

    if (lastSpace < 0) return text

    val last = text.copyOfRange(lastSpace + 1, text.size)

    if (last.isEmpty() || last[0] !in 'A'.code.toByte()..'Z'.code.toByte()) return text

    return text.copyOfRange(0, lastSpace).takeIf { String(last, Charsets.ISO_8859_1) == "Calendar.ISO" }
}

/** What `Calendar.ISO` makes of a sigil's text. */
internal sealed class Iso {
    /** The text parsed. */
    class Parsed(
        val year: Int = 0,
        val month: Int = 0,
        val day: Int = 0,
        val hour: Int = 0,
        val minute: Int = 0,
        val second: Int = 0,
        val microsecond: Int = 0,
        val precision: Int = 0,
    ) : Iso()

    /** The parser refused the text, an error of [kind]. */
    class Failed(val kind: String) : Iso()

    /** A text the port does not read as `Calendar.ISO` does on every release. */
    data object Unsupported : Iso()
}

/** `Calendar.ISO`'s `parse_date/1`, `parse_time/1`, `parse_naive_datetime/1` and `parse_utc_datetime/1`. */
private class IsoParser(private val text: ByteArray, private val level: ElixirLanguageLevel) {
    /** The fractional second and offset that end a time. */
    private sealed class Tail {
        class Parsed(val microsecond: Int, val precision: Int, val offset: Int?) : Tail()

        data object Invalid : Tail()

        data object Unsupported : Tail()
    }

    fun date(): Iso {
        val (multiplier, start) = year()
        val (year, month, day) = digits(start, DATE)?.takeIf { text.size == start + DATE.length } ?: return INVALID_FORMAT

        return if (validDate(multiplier * year, month, day)) Iso.Parsed(year = multiplier * year, month = month, day = day) else INVALID_DATE
    }

    fun time(): Iso {
        val start = if (char(0) == 'T') 1 else 0
        val (hour, minute, second) = digits(start, TIME) ?: return INVALID_FORMAT

        return when (val tail = tail(start + TIME.length)) {
            Tail.Invalid -> INVALID_FORMAT
            Tail.Unsupported -> Iso.Unsupported
            is Tail.Parsed ->
                if (validTime(hour, minute, second)) {
                    Iso.Parsed(hour = hour, minute = minute, second = second, microsecond = tail.microsecond, precision = tail.precision)
                } else {
                    INVALID_TIME
                }
        }
    }

    fun naiveDateTime(): Iso = dateTime { parsed, _ -> parsed }

    fun utcDateTime(): Iso =
        dateTime { parsed, offset ->
            when (offset) {
                null -> MISSING_OFFSET
                0 -> parsed
                else -> if (offsetLeavesTheDates(parsed, offset)) DATE_OUT_OF_RANGE else NON_UTC_OFFSET
            }
        }

    /**
     * Whether applying [offset] to [parsed] moves it to a day `Calendar.ISO` has no `date_from_iso_days/1` clause for,
     * before [ISO_DATE_UNBOUNDED].
     */
    private fun offsetLeavesTheDates(parsed: Iso.Parsed, offset: Int): Boolean {
        if (ISO_DATE_UNBOUNDED.isSufficient(level)) return false

        val extraDays = Math.floorDiv(parsed.hour * 3600 + parsed.minute * 60 + parsed.second - offset, SECONDS_PER_DAY)

        if (extraDays == 0) return false

        val days = LocalDate.of(parsed.year, parsed.month, parsed.day).toEpochDay() + EPOCH_DAY_TO_ISO_DAYS + extraDays

        return days !in FIRST_ISO_DAY..LAST_ISO_DAY
    }

    /** The date, a separator and the time, then [result] of them and the offset once they are valid. */
    private fun dateTime(result: (Iso.Parsed, Int?) -> Iso): Iso {
        val (multiplier, start) = year()
        val separator = start + DATE.length
        val (year, month, day) = digits(start, DATE) ?: return INVALID_FORMAT
        val (hour, minute, second) = digits(separator + 1, TIME) ?: return INVALID_FORMAT

        if (char(separator) != ' ' && char(separator) != 'T') return INVALID_FORMAT

        return when (val tail = tail(separator + 1 + TIME.length)) {
            Tail.Invalid -> INVALID_FORMAT
            Tail.Unsupported -> Iso.Unsupported
            is Tail.Parsed ->
                when {
                    !validDate(multiplier * year, month, day) -> INVALID_DATE
                    !validTime(hour, minute, second) -> INVALID_TIME
                    else ->
                        result(
                            Iso.Parsed(multiplier * year, month, day, hour, minute, second, tail.microsecond, tail.precision),
                            tail.offset,
                        )
                }
        }
    }

    /** The sign before a year and where the year starts; a `+` is only read from [ISO_PARSES_SIGNED_YEAR]. */
    private fun year(): Pair<Int, Int> =
        when {
            char(0) == '-' -> -1 to 1
            char(0) == '+' && ISO_PARSES_SIGNED_YEAR.isSufficient(level) -> 1 to 1
            else -> 1 to 0
        }

    private fun char(index: Int): Char? = text.getOrNull(index)?.let { (it.toInt() and 0xFF).toChar() }

    /**
     * The numbers of [layout] at [start], where `d` is a digit and any other character itself, or `null` where the text
     * is not that.
     */
    private fun digits(start: Int, layout: String): List<Int>? {
        val numbers = mutableListOf<Int>()
        var current = 0

        for ((offset, expected) in layout.withIndex()) {
            val actual = char(start + offset) ?: return null

            if (expected == 'd') {
                if (actual !in '0'..'9') return null

                current = current * 10 + (actual - '0')
            } else {
                if (actual != expected) return null

                numbers.add(current)
                current = 0
            }
        }

        return numbers + current
    }

    /** `parse_microsecond/1` then `parse_offset/1` of the text from [start], which have to take all of it. */
    private fun tail(start: Int): Tail {
        var index = start
        var microsecond = 0
        var precision = 0

        if (char(index) == '.' || char(index) == ',') {
            index++

            val begin = index

            while (char(index) in '0'..'9') {
                if (precision < 6) {
                    microsecond = microsecond * 10 + (char(index)!! - '0')
                    precision++
                }

                index++
            }

            if (index == begin) return Tail.Invalid

            microsecond *= POWERS_OF_TEN[6 - precision]
        }

        val rest = String(text, index, text.size - index, Charsets.ISO_8859_1)

        when (rest) {
            "" -> return Tail.Parsed(microsecond, precision, null)
            "Z" -> return Tail.Parsed(microsecond, precision, 0)
            "-00:00" -> return Tail.Invalid
        }

        val sign = when (rest[0]) {
            '+' -> 1
            '-' -> -1
            else -> return Tail.Invalid
        }
        val (hour, minute, remainder) = when {
            rest.length >= 6 && rest[3] == ':' -> Triple(rest.substring(1, 3), rest.substring(4, 6), rest.substring(6))
            rest.length >= 5 -> Triple(rest.substring(1, 3), rest.substring(3, 5), rest.substring(5))
            rest.length >= 3 -> Triple(rest.substring(1, 3), "00", rest.substring(3))
            else -> return Tail.Invalid
        }

        // Releases before 1.19 read a chunk like `+5` as a number, which the current clauses refuse.
        if (SIGNED_DIGIT.matches(hour) || SIGNED_DIGIT.matches(minute)) return Tail.Unsupported

        if (!(hour[0] in '0'..'2' && hour[1] in '0'..'9' && minute[0] in '0'..'5' && minute[1] in '0'..'9')) return Tail.Invalid

        val hours = (hour[0] - '0') * 10 + (hour[1] - '0')
        val minutes = (minute[0] - '0') * 10 + (minute[1] - '0')

        if (hours >= 24 || remainder.isNotEmpty()) return Tail.Invalid

        return Tail.Parsed(microsecond, precision, (hours * 60 + minutes) * 60 * sign)
    }

    private fun validDate(year: Int, month: Int, day: Int): Boolean {
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        return month in 1..12 && day in 1..days
    }

    private fun validTime(hour: Int, minute: Int, second: Int): Boolean = hour in 0..23 && minute in 0..59 && second in 0..59

    private companion object {
        const val DATE = "dddd-dd-dd"
        const val TIME = "dd:dd:dd"

        val INVALID_FORMAT = Iso.Failed("iso_invalid_format")
        val INVALID_DATE = Iso.Failed("iso_invalid_date")
        val INVALID_TIME = Iso.Failed("iso_invalid_time")
        val MISSING_OFFSET = Iso.Failed("iso_missing_offset")
        val NON_UTC_OFFSET = Iso.Failed("iso_non_utc_offset")
        val DATE_OUT_OF_RANGE = Iso.Failed("iso_date_out_of_range")

        const val SECONDS_PER_DAY = 86_400

        /** Days from 0000-01-01, where `Calendar.ISO` counts from, to 1970-01-01. */
        const val EPOCH_DAY_TO_ISO_DAYS = 719_528L

        /** The days `Calendar.ISO.date_from_iso_days/1` has a clause for: -9999-01-01 to 9999-12-31. */
        const val FIRST_ISO_DAY = -3_652_059L
        const val LAST_ISO_DAY = 3_652_424L

        val SIGNED_DIGIT = Regex("""[+-]\d""")
        val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000)
    }
}
