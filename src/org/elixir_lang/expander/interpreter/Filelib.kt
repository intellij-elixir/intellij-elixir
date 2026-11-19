package org.elixir_lang.expander.interpreter

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.system.OS
import org.elixir_lang.expander.DirectoryListing
import org.elixir_lang.expander.FileEntry

/**
 * `filelib:wildcard/1,2`, ported from OTP's `filelib.erl`, which has it the same from OTP 24 to 29: the pattern split
 * into parts, the literal parts before the first wildcard joined into the directory to list, then each part matched
 * against the names [list] gives, a `**` part against every name below. [list] is `list_dir/1` of the module the
 * wildcard is given: the names in the directory at a path, `null` where there is none. [entry] is what is at a path.
 * [readFileInfo] is the module's `read_file_info/1`, which a `..` after a wildcard reads first, and which may raise. A
 * path is resolved as the OS resolves it: [absolute] from the working directory, `null` where that isn't known, then
 * [Filename.resolved]. A name past ASCII is text of the native name encoding, which isn't read: a part counting its
 * characters declines for it ([matches]).
 */
internal class Filelib(
    private val os: OS,
    private val absolute: (String) -> String?,
    private val list: (String) -> List<DirectoryListing.Entry>?,
    private val entry: (String) -> FileEntry,
    private val readFileInfo: (String) -> Unit,
) {
    /** `{badpattern, Reason}`, which `wildcard/1,2` raises. */
    class BadPattern(val reason: String) : Exception(null, null, false, false)

    /** A wildcard the expander doesn't answer, for [reason]. */
    class Unanswered(val reason: String) : Exception(reason, null, false, false)

    /**
     * A path a wildcard reached: its [text], as the match gives it, the path the OS resolves it to, and whether it is a
     * directory, `null` where that isn't listed yet.
     */
    private class Spot(val text: String, val resolved: String, val directory: Boolean?)

    /** Each path [list] has listed so far: a `**` match lists its directory again to match what follows it. */
    private val listings = HashMap<String, List<DirectoryListing.Entry>?>()

    /**
     * The paths [pattern] matches, sorted; an empty [pattern] is [Unanswered]. A relative pattern is listed from [cwd],
     * read only for one, and each match is given without the [cwd] it was joined to, as `compile_wildcard/2` and
     * `compile_join/2` take it off.
     */
    fun wildcard(pattern: String, cwd: () -> String): List<String> {
        val parts = Filename.split(convertEscapes(pattern), os)
        val root = parts.firstOrNull() ?: throw Unanswered("filelib:wildcard of the empty pattern")
        val relative = Filename.pathtype(root, os) == Filename.PathType.RELATIVE
        val compiled = (if (relative) parts else parts.drop(1)).map { compilePart(wrapEscapes(it.map(Token::Character))) }
        var base = if (relative) prepareBase(cwd()) else root
        // How much of a match is [cwd]'s, `null` while no literal part is joined to it.
        var prefix = if (relative) null else 0

        for ((at, part) in compiled.withIndex()) {
            if (!part.all { it is Token.Character }) {
                val cwdLength = prefix ?: prepareBase(base).length
                val start = resolved(base) ?: return emptyList()

                return wildcard2(listOf(Spot(base, start, null)), compiled.drop(at), mutableListOf()).map { cut(it, cwdLength) }.sorted()
            }
            val literal = part.joinToString("") { (it as Token.Character).character.toString() }
            val joined = join(base, literal)

            prefix = prefix ?: (joined.length - literal.length)
            base = joined
        }

        return if (exists(base)) listOf(cut(base, prefix!!)) else emptyList()
    }

    /**
     * [path] without its first [prefix] characters, as `lists:nthtail/2` takes them, which fails where [path] is shorter:
     * on Windows a part relative to a drive starts the path again, so it may be.
     */
    private fun cut(path: String, prefix: Int): String =
        if (path.length < prefix) throw Unanswered("filelib:wildcard of $path, shorter than the working directory it was joined to") else path.substring(prefix)

    /**
     * `exists/2`: whether anything, a link to nothing included, is at [path]. On Windows, as `do_exists/3` reads it,
     * each name a `..` follows must be a directory, not a link to one, as the OS resolves the `..` from the text. Where
     * the directory is on disk at another path, a link the run's files give only the target of may be on the way, so it
     * is [Unanswered].
     */
    private fun exists(path: String): Boolean {
        val target = resolved(path) ?: return false
        if (entry(target) == FileEntry.Missing) return false
        if (os != OS.Windows) return true

        val parts = Filename.split(path, os)
        var kept: String? = null
        var at = 0

        while (at < parts.size) {
            val next = kept?.let { join(it, parts[at]) } ?: parts[at]
            if (parts.getOrNull(at + 1) == "..") {
                val directory = resolved(next) ?: return false
                val physical = physical(directory) ?: return false
                if (!physical.equals(directory, ignoreCase = true)) throw Unanswered("filelib:wildcard of $path, whose `..` may follow a link")
                at += 2
            } else {
                kept = next
                at++
            }
        }

        return true
    }

    private fun wildcard2(spots: List<Spot>, pattern: List<List<Token>>, result: MutableList<String>): MutableList<String> {
        for (spot in spots) wildcard3(spot, pattern, result)

        return result
    }

    private fun wildcard3(base: Spot, pattern: List<List<Token>>, result: MutableList<String>) {
        ProgressManager.checkCanceled()

        if (pattern.isEmpty()) {
            result.add(base.text)
            return
        }
        val part = pattern[0]
        val rest = pattern.subList(1, pattern.size)

        when (part) {
            DOUBLE_STAR -> listed(base)?.let { doubleStar(base, it, rest, result) }
            DOTDOT -> {
                readFileInfo(base.text)
                val parent = parentOf(base) ?: return

                wildcard2(listOf(Spot(join(base.text, ".."), parent, true)), rest, result)
            }
            else -> {
                val directory = listed(base) ?: return

                wildcard2(directory.filter { matches(part, it.name) }.map(childOf(base)), rest, result)
            }
        }
    }

    private fun doubleStar(base: Spot, directory: List<DirectoryListing.Entry>, patterns: List<List<Token>>, result: MutableList<String>) {
        val spotOf = childOf(base)

        for (child in directory) {
            ProgressManager.checkCanceled()

            val full = spotOf(child)

            listed(full)?.let { doubleStar(full, it, patterns, result) }

            when {
                patterns.isEmpty() -> result.add(full.text)
                matches(patterns.first(), child.name) -> wildcard2(listOf(full), patterns.subList(1, patterns.size), result)
            }
        }
    }

    /**
     * The directory at [path] ([list]), `null` where there is none. Past [COPY_NODES] listings the wildcard is
     * [Unanswered], as a `**` below a link to a directory above it lists the same directories again.
     */
    private fun listing(path: String): List<DirectoryListing.Entry>? {
        if (path in listings) return listings[path]
        if (listings.size >= COPY_NODES) throw Unanswered("filelib:wildcard listing more than $COPY_NODES directories")

        return list(path).also { listings[path] = it }
    }

    /** The directory at [spot], `null` where it isn't one. */
    private fun listed(spot: Spot): List<DirectoryListing.Entry>? = if (spot.directory == false) null else listing(spot.resolved)

    /** Where the OS resolves a `..` after [spot], `null` where it isn't a directory, as `do_is_dir/2` reads it. */
    private fun parentOf(spot: Spot): String? {
        val physical = (if (spot.directory == false) null else physical(spot.resolved)) ?: return null

        return Filename.parent(spot.resolved, os) { physical }
    }

    /**
     * [path] as the OS resolves it, `null` where a `.` or `..` in it follows a name that isn't a directory; one not
     * read ([Filename.Resolved.Unread]), or relative to a working directory that isn't known, is [Unanswered].
     */
    private fun resolved(path: String): String? {
        val absolute = absolute(path) ?: throw Unanswered("filelib:wildcard of $path, relative to a working directory that isn't known")

        return when (val resolved = Filename.resolved(absolute, os, ::physical)) {
            is Filename.Resolved.At -> resolved.path
            is Filename.Resolved.NotADirectory -> null
            Filename.Resolved.Unread -> throw Unanswered("filelib:wildcard of $path, a part of which the OS may take as other than its name")
        }
    }

    /** The path on disk of the directory at [path] ([entry]), `null` where there is none. */
    private fun physical(path: String): String? = entry(path).physical

    /** The [Spot] of an entry in the directory at [base], joined to it as [join] joins a name. */
    private fun childOf(base: Spot): (DirectoryListing.Entry) -> Spot {
        val text = prepareBase(base.text)
        val resolved = prepareBase(base.resolved)

        return { Spot(text + it.name, resolved + it.name, it.isDirectory) }
    }

    /** `prepare_base/1`: [path] as [join] joins a name to it, without the name. */
    private fun prepareBase(path: String): String = join(path, "x").dropLast(1)

    /** `filename:join/2` of [name1] and [name2], which is relative. */
    private fun join(name1: String, name2: String): String = Filename.join(name1, name2, os)

    /** A part of a compiled pattern. */
    private sealed interface Token {
        data class Character(val character: Char) : Token
        data class Escaped(val character: Char) : Token
        data object Question : Token
        data object Star : Token
        data object Accept : Token
        data object DoubleStar : Token
        data class OneOf(val characters: Set<Char>) : Token
        data class Alt(val alternatives: List<List<Token>>) : Token
    }

    /**
     * [matchPart] of [part] and [name]. Where [part] counts characters, with a `?` or a charset, a [name] past ASCII is
     * [Unanswered], as how many it holds is the native name encoding's; elsewhere each encoding gives an ASCII character
     * only for itself, so the match is the same in every one.
     */
    private fun matches(part: List<Token>, name: String): Boolean {
        if (!isAscii(name) && part.any(::counts)) throw Unanswered("filelib:wildcard of the name $name, past ASCII")

        return matchPart(part, name, HashSet())
    }

    /** Whether [token] matches a character by counting it: a `?`, a charset, or an alternative holding one. */
    private fun counts(token: Token): Boolean =
        token == Token.Question || token is Token.OneOf || token is Token.Alt && token.alternatives.any { it.any(::counts) }

    /**
     * `match_part/2`: whether [part] matches the whole of [name] from [at]. A `*` or `{}` tried once at a spot and
     * [failed] isn't tried again, as OTP's `do_star/2` tries every split of the name between `*`s, exponentially many;
     * every other token matches one way, on to the next of them. The answer is OTP's.
     */
    private fun matchPart(part: List<Token>, name: String, failed: MutableSet<Tried>, from: Int = 0, at: Int = 0): Boolean {
        ProgressManager.checkCanceled()

        if (from == part.size) return at == name.length
        val token = part[from]
        val tried = if (token == Token.Star || token is Token.Alt) Tried(part, from, at) else null
        if (tried != null && tried in failed) return false

        val matched = when (token) {
            Token.Question -> at < name.length && matchPart(part, name, failed, from + 1, at + 1)
            // Only ever last, and then they match the rest.
            Token.Accept, Token.DoubleStar -> true
            Token.Star -> (at..name.length).any { matchPart(part, name, failed, from + 1, it) }
            is Token.OneOf ->
                at < name.length && name[at] in token.characters && matchPart(part, name, failed, from + 1, at + 1)
            is Token.Alt -> token.alternatives.any { matchPart(it, name, failed, 0, at) }
            is Token.Character ->
                at < name.length && name[at] == token.character && matchPart(part, name, failed, from + 1, at + 1)
            is Token.Escaped -> error("an escape is compiled to its character")
        }
        if (!matched && tried != null) failed += tried

        return matched
    }

    /** A [matchPart] of [part], told apart by identity, from its token [from] at [at] in the name. */
    private class Tried(val part: List<Token>, val from: Int, val at: Int) {
        override fun equals(other: Any?): Boolean = other is Tried && other.part === part && other.from == from && other.at == at

        override fun hashCode(): Int = (System.identityHashCode(part) * 31 + from) * 31 + at
    }

    /** `compile_part/1` of a part [wrapEscapes] has wrapped. */
    private fun compilePart(part: List<Token>): List<Token> = compilePart(part, upTo = false).tokens

    private class Compiled(val tokens: List<Token>, val delimiter: Char? = null, val rest: List<Token> = emptyList())

    /**
     * `compile_part/3`: the tokens of [part], up to a `,` or `}` where [upTo], which then must come; a `{` ends it
     * with the alternation of the rest.
     */
    private fun compilePart(part: List<Token>, upTo: Boolean): Compiled {
        val result = mutableListOf<Token>()
        var at = 0

        while (true) {
            ProgressManager.checkCanceled()

            val token = part.getOrNull(at)
            val character = (token as? Token.Character)?.character

            when {
                token == null -> if (upTo) throw BadPattern("missing_delimiter") else return Compiled(result)
                upTo && (character == ',' || character == '}') -> return Compiled(result, character, part.subList(at + 1, part.size))
                character == '?' -> result += Token.Question
                character == '*' && part.characterAt(at + 1) == '*' -> {
                    result += if (at + 2 == part.size) Token.DoubleStar else Token.Star
                    at++
                }
                character == '*' -> result += if (at + 1 == part.size) Token.Accept else Token.Star
                character == '[' -> {
                    val charset = compileCharset(part, at + 1)

                    if (charset == null) {
                        result += token
                    } else {
                        result += charset.first
                        at = charset.second
                        continue
                    }
                }
                character == '{' -> {
                    val alt = compileAlt(part.subList(at + 1, part.size))

                    // `compile_alt/2` matches no clause for what an inner `{` gives.
                    if (upTo) throw Unanswered("filelib:wildcard of a `{` inside an alternative, which filelib has no clause for")

                    return Compiled(result + alt)
                }
                token is Token.Escaped -> result += Token.Character(token.character)
                else -> result += token
            }

            at++
        }
    }

    /** `compile_charset/2` from [from]: the charset and where the rest begins, `null` where no `]` closes it. */
    private fun compileCharset(part: List<Token>, from: Int): Pair<Token, Int>? {
        val characters = sortedSetOf<Char>()
        var at = from

        if (part.characterAt(at) == ']') {
            characters += ']'
            at++
        }

        while (at < part.size) {
            val token = part[at]

            if (part.characterAt(at + 1) == '-' && at + 2 < part.size) {
                val upper = part[at + 2]

                // Erlang orders an escape, a tuple, after every character, and can't count down from one.
                if (upper is Token.Escaped && (token is Token.Character || (token as Token.Escaped).character <= upper.character)) {
                    throw Unanswered("filelib:wildcard of a range from an escape, which filelib has no clause for")
                }

                if (token is Token.Character && upper is Token.Character && token.character <= upper.character) {
                    characters += token.character..upper.character
                    at += 3
                    continue
                }
            }

            when (token) {
                is Token.Character -> {
                    if (token.character == ']') return Token.OneOf(characters) to at + 1

                    characters += token.character
                }
                is Token.Escaped -> characters += token.character
                else -> error("a part is characters and escapes before it is compiled")
            }

            at++
        }

        return null
    }

    /** `compile_alt/1`: each alternative up to `,` and the last up to `}`, the rest of the part after each. */
    private fun compileAlt(pattern: List<Token>): Token.Alt {
        val alternatives = mutableListOf<List<Token>>()
        var rest = pattern

        while (true) {
            val compiled = compilePart(rest, upTo = true)

            alternatives.add(0, compiled.tokens)
            rest = compiled.rest

            if (compiled.delimiter == '}') {
                val after = compilePart(wrapEscapes(rest))

                return Token.Alt(alternatives.map { it + after })
            }
        }
    }

    private companion object {
        val DOTDOT = listOf(Token.Character('.'), Token.Character('.'))
        val DOUBLE_STAR = listOf(Token.DoubleStar)

        /** The character at [index], `null` past the end or at an escape. */
        fun List<Token>.characterAt(index: Int) = (getOrNull(index) as? Token.Character)?.character

        /** `convert_escapes/1`: `@` doubled and `\` as `@e`, so `filename:split/1` doesn't take a `\` as a separator. */
        fun convertEscapes(pattern: String): String = pattern.replace("@", "@@").replace("\\", "@e")

        /** `wrap_escapes/1`: `@@` back to `@`, and `@e` the escape of the character after it, dropped at the end. */
        fun wrapEscapes(part: List<Token>): List<Token> {
            val wrapped = mutableListOf<Token>()
            var at = 0

            while (at < part.size) {
                when {
                    part.characterAt(at) == '@' && part.characterAt(at + 1) == '@' -> {
                        wrapped += Token.Character('@')
                        at += 2
                    }
                    part.characterAt(at) == '@' && part.characterAt(at + 1) == 'e' -> {
                        when (val escaped = part.getOrNull(at + 2)) {
                            is Token.Character -> wrapped += Token.Escaped(escaped.character)
                            is Token.Escaped ->
                                throw Unanswered("filelib:wildcard of an escape escaped again, which filelib has no clause for")
                            else -> Unit
                        }
                        at += 3
                    }
                    else -> {
                        wrapped += part[at]
                        at++
                    }
                }
            }

            return wrapped
        }
    }
}
