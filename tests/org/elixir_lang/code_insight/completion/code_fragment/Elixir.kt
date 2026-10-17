package org.elixir_lang.code_insight.completion.code_fragment

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.ElixirAst
import kotlin.time.Duration.Companion.seconds

/** What Elixir answers for one case: `Code.string_to_quoted`'s [parsed] term, and the answers at each point. */
class Asked(val parsed: OtpErlangObject, val points: List<Point>) {
    /**
     * The point `n` grapheme clusters into the case, its caret at [offset] in UTF-16 units.
     *
     * @property surround `null` where it was not asked: at the start of the case or of a line
     * @property literal whether Elixir's tokenizer stops the text before the caret inside an open string, charlist,
     *   quoted atom or function name, heredoc or sigil
     * @property atomStart whether an atom token of the whole case starts at a `:` just before the caret
     */
    class Point(
        val n: Int,
        val offset: Int,
        val cursor: OtpErlangObject,
        val container: OtpErlangObject,
        val surround: OtpErlangObject?,
        val literal: OtpErlangObject,
        val atomStart: OtpErlangObject,
    ) {
        fun decode(): Answer =
            Answer(
                n,
                offset,
                decodeCursor(cursor),
                decodeContainer(container),
                surround?.let(::decodeSurround),
                decodeBoolean(literal),
                decodeBoolean(atomStart),
            )
    }
}

/** A point's answers, decoded. */
class Answer(
    val n: Int,
    val offset: Int,
    val cursor: CursorContext,
    val container: Container,
    val surround: SurroundAnswer?,
    val literal: Boolean,
    val atomStart: Boolean,
)

private fun decodeBoolean(term: OtpErlangObject): Boolean =
    when (term) {
        OtpErlangAtom("true") -> true
        OtpErlangAtom("false") -> false
        else -> throw IllegalArgumentException("not a boolean: $term")
    }

/** `container_cursor_to_quoted/2`'s answer. */
sealed class Container {
    class Quoted(val ast: ElixirAst) : Container()

    data object Error : Container() {
        override fun toString() = "{:error, _}"
    }
}

private fun decodeContainer(term: OtpErlangObject): Container {
    val tuple = term as? OtpErlangTuple ?: refuseContainer(term)
    if (tuple.arity() != 2) refuseContainer(term)

    return when (tuple.elementAt(0)) {
        OtpErlangAtom("ok") -> Container.Quoted(ElixirAst.fromOtp(tuple.elementAt(1), TextRange.EMPTY_RANGE) ?: refuseContainer(term))
        OtpErlangAtom("error") -> Container.Error
        else -> refuseContainer(term)
    }
}

private fun refuseContainer(term: OtpErlangObject): Nothing =
    throw IllegalArgumentException("not a container_cursor_to_quoted answer: $term")

/**
 * Asks Elixir, through one compile on the quoter's node, for `Code.string_to_quoted` of [text] and, at every
 * grapheme-cluster boundary, for `cursor_context`, `container_cursor_to_quoted`, `surround_context`, whether the text
 * before the caret ends inside a literal, and whether an atom starts at the `:` before the caret. Elixir walks its own
 * clusters, so its boundaries are the points.
 */
fun ask(text: String): Asked {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val compiled = Quoter.compile(source(bytes), COMPILE_TIMEOUT)

    check(compiled.status == OtpErlangAtom("ok")) { "the probe compile failed: ${compiled.status}" }

    val utf16 = utf16Offsets(text, bytes.size)
    val parsed = compiled.messages.first()
    val points = compiled.messages.drop(1).map { message ->
        val elements = (message as OtpErlangTuple).elements()

        Asked.Point(
            (elements[0] as OtpErlangLong).intValue(),
            utf16[(elements[1] as OtpErlangLong).intValue()],
            elements[2],
            elements[3],
            elements[4].takeUnless { it == OtpErlangAtom("nil") },
            elements[5],
            elements[6],
        )
    }

    return Asked(parsed, points)
}

/**
 * The column is counted in clusters on the line before the caret's character, as `surround_context` counts it, and an
 * atom token's in code points, as the tokenizer counts it. An interpolation nests its tokens, so atoms are found at
 * any depth. The text is a binary of its bytes, so nothing in it needs escaping.
 */
private fun source(bytes: ByteArray): String =
    """
    text = <<${bytes.joinToString(", ") { (it.toInt() and 0xFF).toString() }}>>
    IntellijElixir.Quoter.Probe.send(__ENV__, Code.string_to_quoted(text))
    graphemes = String.graphemes(text)

    atom_starts = fn
      {kind, {line, column, _}, value}, starts, walk when kind in [:atom, :atom_quoted, :atom_unsafe] ->
        walk.(value, MapSet.put(starts, {line, column}), walk)

      term, starts, walk when is_tuple(term) ->
        walk.(Tuple.to_list(term), starts, walk)

      term, starts, walk when is_list(term) ->
        Enum.reduce(term, starts, &walk.(&1, &2, walk))

      _, starts, _ ->
        starts
    end

    {:ok, _, _, _, tokens, _} = :elixir_tokenizer.tokenize(String.to_charlist(text), 1, 1, [])
    atom_starts = atom_starts.(tokens, MapSet.new(), atom_starts)

    literal? = fn prefix ->
      case :elixir_tokenizer.tokenize(String.to_charlist(prefix), 1, 1, []) do
        {:error, {_, [message, extension], _}, _, _, _} ->
          String.contains?(IO.chardata_to_string(message), "missing terminator: ") and
            String.starts_with?(
              IO.chardata_to_string(extension),
              [" (for string ", " (for atom ", " (for function name ", " (for heredoc ", " (for sigil "]
            )

        {:error, {_, message, _}, _, _, _} when is_list(message) ->
          String.starts_with?(
            IO.chardata_to_string(message),
            "heredoc allows only whitespace characters followed by a new line after opening "
          )

        _ ->
          false
      end
    end

    for n <- 0..length(graphemes) do
      prefix = graphemes |> Enum.take(n) |> Enum.join()
      prefix_lines = String.split(prefix, "\n")

      surround =
        if n >= 1 and Enum.at(graphemes, n - 1) != "\n" do
          lines = graphemes |> Enum.take(n - 1) |> Enum.join() |> String.split("\n")
          Code.Fragment.surround_context(text, {length(lines), String.length(List.last(lines)) + 1})
        end

      IntellijElixir.Quoter.Probe.send(
        __ENV__,
        {
          n,
          byte_size(prefix),
          Code.Fragment.cursor_context(prefix),
          Code.Fragment.container_cursor_to_quoted(prefix),
          surround,
          literal?.(prefix),
          String.ends_with?(prefix, ":") and
            MapSet.member?(atom_starts, {length(prefix_lines), length(String.to_charlist(List.last(prefix_lines)))})
        }
      )
    end
    """.trimIndent()

/** For each UTF-8 byte offset of [text] that starts a code point, the UTF-16 offset there. */
private fun utf16Offsets(text: String, byteCount: Int): IntArray {
    val offsets = IntArray(byteCount + 1) { -1 }
    var bytes = 0
    var index = 0

    while (index < text.length) {
        offsets[bytes] = index
        val codePoint = text.codePointAt(index)
        bytes += String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        index += Character.charCount(codePoint)
    }
    offsets[bytes] = index

    return offsets
}

private val COMPILE_TIMEOUT = 60.seconds
