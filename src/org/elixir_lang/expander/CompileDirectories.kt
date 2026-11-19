package org.elixir_lang.expander

import com.intellij.util.system.OS
import org.elixir_lang.expander.interpreter.Filename
import org.elixir_lang.language_level.ElixirLanguageFeature.RELATIVE_TO_EXPANDS_DOTS
import org.elixir_lang.language_level.ElixirLanguageFeature.RELATIVE_TO_ITSELF_IS_DOT
import org.elixir_lang.language_level.ElixirLanguageLevel

/** The directory Elixir compiled each file in, the working directory a kept `quote`'s file is taken relative to. */
fun interface CompileDirectories {
    /** The directory [file], a path, was compiled in, or `null` where it isn't known. */
    fun of(file: String): String?

    companion object {
        val UNKNOWN = CompileDirectories { null }
    }
}

/**
 * `Path.relative_to/2` on [level] of [path] against the directory [directory] gives, which is read only where [path] is
 * absolute, `null` where it is read and isn't known; each split as [os] splits. [path] is relative to the directory
 * when below it, `"."` where it is the directory from [RELATIVE_TO_ITSELF_IS_DOT], and otherwise itself; from
 * [RELATIVE_TO_EXPANDS_DOTS], each path's `.` and `..` are taken out first, by `expand_split/1`, or `expand_relative/3`
 * for a path that isn't absolute.
 */
internal fun relativeTo(path: String, directory: () -> String?, os: OS, level: ElixirLanguageLevel): String? {
    val expands = RELATIVE_TO_EXPANDS_DOTS.isSufficient(level)
    val segments = Filename.split(path, os)

    if (!isAbsolute(segments, os)) {
        return if (expands) joined(expandRelative(segments)) else path
    }

    val expand = { split: List<String> -> if (expands) expandSplit(split) else split }
    val split = expand(segments)
    val base = expand(Filename.split(directory() ?: return null, os))
    val within = split.size >= base.size && base.indices.all { split[it] == base[it] }

    return when {
        !within -> if (expands) joined(split) else path
        split.size > base.size -> joined(split.drop(base.size))
        RELATIVE_TO_ITSELF_IS_DOT.isSufficient(level) -> "."
        else -> path
    }
}

/** `split_absolute?/2`: whether [segments] start at a root, on Windows a `//` or drive one. */
private fun isAbsolute(segments: List<String>, os: OS): Boolean =
    segments.firstOrNull()?.let { Filename.pathtype(it, os) } == Filename.PathType.ABSOLUTE

/** `expand_split/1`: [segments] with each `.` dropped, and each `..` taking the name before it, never the root. */
private fun expandSplit(segments: List<String>): List<String> {
    val expanded = mutableListOf(segments.first())

    for (segment in segments.drop(1)) {
        when (segment) {
            ".." -> if (expanded.size > 1) expanded.removeAt(expanded.lastIndex)
            "." -> Unit
            else -> expanded += segment
        }
    }

    return expanded
}

/** `expand_relative/3`: [segments] with each `.` dropped, and each `..` taking the name before it, else kept in front. */
private fun expandRelative(segments: List<String>): List<String> {
    val expanded = mutableListOf<String>()
    var up = 0

    for (segment in segments) {
        when (segment) {
            ".." -> if (expanded.isEmpty()) up++ else expanded.removeAt(expanded.lastIndex)
            "." -> Unit
            else -> expanded += segment
        }
    }

    return if (expanded.isEmpty() && up == 0) listOf(".") else List(up) { ".." } + expanded
}

/** `Path.join/1` of [segments], as `Path.split/1` gives them. */
private fun joined(segments: List<String>): String =
    segments.drop(1).fold(segments.first()) { joined, segment -> if (joined.endsWith('/')) joined + segment else "$joined/$segment" }
