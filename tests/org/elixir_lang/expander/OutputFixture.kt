package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * What the `*_output` fixtures hold and how both sides of a comparison print a quoted term: the generator's `render/1`
 * and `meta/1`, with `line` and `column` dropped from every node's metadata and the counter written `:N`.
 */
internal object OutputFixture {
    /** A fixture case's [source], with `⏎` in the header standing for a newline. */
    class Case(val label: String, val source: String, val context: Env.Context, val expected: String)

    const val SKIP = "SKIP"

    private val HEADER = Regex("""^## (\w+)(?: \((match|guard)\))?: (.*)$""")

    /** An exception's message, which `THROW` gives the same way. */
    val RAISE = Regex("""^(?:RAISE|THROW) \S+ "(.*)"$""", RegexOption.DOT_MATCHES_ALL)

    private val IDENTIFIER = Regex("""\A[A-Za-z_][A-Za-z0-9_@]*[?!]?\z""")

    fun cases(file: File): List<Case> {
        val lines = file.readLines()
        val cases = mutableListOf<Case>()
        var index = 0

        while (index < lines.size) {
            val header = HEADER.matchEntire(lines[index++]) ?: continue
            val body = mutableListOf<String>()

            while (index < lines.size && !lines[index].startsWith("## ")) body.add(lines[index++])

            val (label, context, source) = header.destructured
            val envContext = when (context) {
                "match" -> Env.Context.MATCH
                "guard" -> Env.Context.GUARD
                else -> Env.Context.NONE
            }

            cases.add(Case(label, source.replace('⏎', '\n'), envContext, body.joinToString("\n")))
        }

        return cases
    }

    /** `render/1` of the generator. */
    fun render(term: OtpErlangObject): String =
        when (term) {
            is OtpErlangAtom -> term.atomValue().let { if (IDENTIFIER.matches(it)) ":$it" else ":" + binary(it.toByteArray()) }
            is OtpErlangLong -> term.bigIntegerValue().toString()
            is OtpErlangDouble -> term.doubleValue().toString().replace('E', 'e')
            is OtpErlangBinary -> binary(term.binaryValue())
            is OtpErlangString -> term.stringValue().codePoints().toArray().joinToString(", ", "[", "]")
            is OtpErlangList ->
                term.elements().joinToString(", ", "[", "") { render(it) } +
                    (term.lastTail?.let { " | " + render(it) } ?: "") + "]"
            is OtpErlangTuple -> {
                val meta = term.elements().getOrNull(1)

                if (term.arity() == 3 && meta is OtpErlangList && isKeywords(meta)) {
                    val kept = render(kept(meta))

                    regex(term)?.let { "{${render(term.elementAt(0))}, $kept, $it}" }
                        ?: ("{" + render(term.elementAt(0)) + ", $kept, " + render(term.elementAt(2)) + "}")
                } else {
                    term.elements().joinToString(", ", "{", "}") { render(it) }
                }
            }
            else -> throw AssertionError("no rendering for $term")
        }

    /**
     * A `Regex` struct as the generator prints it: its source and options, the sorted keys, and what shape its
     * compiled pattern has, because PCRE's bytes are not the port's to build. A compiled part the expander leaves a
     * placeholder (`__cursor__`) matches whatever the compiler gives there.
     */
    private fun regex(map: OtpErlangTuple): String? {
        val entries = (map.elementAt(2) as? OtpErlangList)?.elements()?.map { it as? OtpErlangTuple } ?: return null

        if ((map.elementAt(0) as? OtpErlangAtom)?.atomValue() != "%{}" || entries.any { it == null || it.arity() != 2 }) return null

        val pairs = entries.map { (it!!.elementAt(0) as? OtpErlangAtom)?.atomValue() to it.elementAt(1) }.toMap()

        if (pairs["__struct__"] != OtpErlangAtom("Elixir.Regex")) return null

        return "REGEX{source: ${render(pairs.getValue("source"))}, opts: ${render(pairs.getValue("opts"))}, " +
            "pattern: ${pattern(pairs.getValue("re_pattern"))}, " +
            "keys: ${render(OtpErlangList(pairs.keys.filterNotNull().map(::OtpErlangAtom).toTypedArray()))}}"
    }

    /**
     * A compiled pattern as the generator's `pattern/1` prints it: the five-element `re_pattern` tuple, or the call of
     * `Regex.__import_pattern__/1` on a five-element `re_exported_pattern` tuple. A placeholder standing for the whole
     * of either prints as itself, so it does not match.
     */
    private fun pattern(term: OtpErlangObject): String {
        val tuple = term as? OtpErlangTuple ?: return render(term)
        val arguments = (tuple.elementAt(2) as? OtpErlangList)?.elements()
        val first = (arguments?.firstOrNull() as? OtpErlangAtom)?.atomValue()
        val callee = tuple.elementAt(0) as? OtpErlangTuple
        val exported = (arguments?.singleOrNull() as? OtpErlangTuple)?.let { (it.elementAt(2) as? OtpErlangList)?.elements() }

        return when {
            (tuple.elementAt(0) as? OtpErlangAtom)?.atomValue() == "{}" && first == "re_pattern" && arguments.size == 5 -> ":escaped"
            isImportPattern(callee) && exported?.size == 5 && exported[0] == OtpErlangAtom("re_exported_pattern") ->
                "{:import, ${render(kept(tuple.elementAt(1) as OtpErlangList))}, ${render(exported[2])}, ${render(exported[3])}}"
            else -> render(term)
        }
    }

    /** [callee], `{:., _, [Regex, :__import_pattern__]}`. */
    private fun isImportPattern(callee: OtpErlangTuple?): Boolean {
        val dot = (callee?.elementAt(2) as? OtpErlangList)?.elements()

        return (callee?.elementAt(0) as? OtpErlangAtom)?.atomValue() == "." && dot?.size == 2 && dot[1] == OtpErlangAtom("__import_pattern__")
    }

    private fun kept(meta: OtpErlangList): OtpErlangList =
        OtpErlangList(
            meta.elements().map { it as OtpErlangTuple }.filter { key(it) !in POSITION }.map {
                if (key(it) == "counter") OtpErlangTuple(arrayOf(it.elementAt(0), N)) else it
            }.toTypedArray(),
        )

    private fun binary(bytes: ByteArray): String =
        bytes.joinToString("", "\"", "\"") { byte ->
            when (val code = byte.toInt() and 0xFF) {
                '"'.code -> "\\\""
                '\\'.code -> "\\\\"
                in 0x20..0x7E -> code.toChar().toString()
                else -> "\\x" + code.toString(16).uppercase().padStart(2, '0')
            }
        }

    /** [text], a binary as [render] prints it, back to its text. */
    fun unescape(text: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0

        while (index < text.length) {
            val char = text[index++]

            if (char != '\\') {
                bytes.write(char.code)
            } else when (val escaped = text[index++]) {
                'x' -> bytes.write(text.substring(index, index + 2).toInt(16)).also { index += 2 }
                else -> bytes.write(escaped.code)
            }
        }

        return bytes.toString(Charsets.UTF_8)
    }

    /** `meta/1` of the generator: every node's metadata but its position, shortened, sorted and deduplicated. */
    fun meta(term: OtpErlangObject): List<OtpErlangTuple> {
        val found = LinkedHashSet<OtpErlangTuple>()

        fun walk(term: OtpErlangObject) {
            when {
                term is OtpErlangTuple && term.arity() == 3 -> {
                    (term.elementAt(1) as? OtpErlangList)?.elements()?.forEach { entry ->
                        val key = key(entry as OtpErlangTuple)

                        if (key !in POSITION) found.add(OtpErlangTuple(arrayOf(entry.elementAt(0), short(key, entry.elementAt(1)))))
                    }
                    walk(term.elementAt(0))
                    term.elementAt(2).takeIf { it is OtpErlangList || it is OtpErlangString }?.let(::walk)
                }
                term is OtpErlangTuple && term.arity() == 2 -> term.elements().forEach(::walk)
                term is OtpErlangList -> term.elements().forEach(::walk)
            }
        }

        walk(term)

        return found.sortedWith(::compare)
    }

    private fun short(key: String, value: OtpErlangObject): OtpErlangObject =
        when {
            key == "counter" -> N
            value is OtpErlangAtom || value is OtpErlangLong -> value
            value is OtpErlangTuple && value.arity() == 2 && value.elements().all { it is OtpErlangAtom } -> value
            value is OtpErlangList || value is OtpErlangString -> OtpErlangAtom("list")
            else -> OtpErlangAtom("other")
        }

    /** Erlang's term order, over the terms [short] gives: numbers, then atoms, then tuples. */
    private fun compare(left: OtpErlangObject, right: OtpErlangObject): Int {
        val rank = rank(left).compareTo(rank(right))

        return when {
            rank != 0 -> rank
            left is OtpErlangLong -> left.bigIntegerValue().compareTo((right as OtpErlangLong).bigIntegerValue())
            left is OtpErlangAtom -> left.atomValue().compareTo((right as OtpErlangAtom).atomValue())
            else -> {
                val (l, r) = left as OtpErlangTuple to right as OtpErlangTuple

                l.arity().compareTo(r.arity()).takeIf { it != 0 }
                    ?: l.elements().zip(r.elements()).map { (a, b) -> compare(a, b) }.firstOrNull { it != 0 }
                    ?: 0
            }
        }
    }

    private fun rank(term: OtpErlangObject): Int =
        when (term) {
            is OtpErlangLong -> 0
            is OtpErlangAtom -> 1
            is OtpErlangTuple -> 2
            else -> throw AssertionError("no order for $term")
        }

    private fun isKeywords(list: OtpErlangList) =
        list.lastTail == null &&
            list.elements().all { it is OtpErlangTuple && it.arity() == 2 && it.elementAt(0) is OtpErlangAtom }

    private fun key(entry: OtpErlangTuple) = (entry.elementAt(0) as OtpErlangAtom).atomValue()

    private val POSITION = setOf("line", "column")

    private val N = OtpErlangAtom("N")
}
