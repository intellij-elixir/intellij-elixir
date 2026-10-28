package org.elixir_lang.expander

import com.intellij.util.system.OS
import org.elixir_lang.expander.interpreter.Filename

/** The directory Elixir compiled each file in, the working directory a kept `quote`'s file is taken relative to. */
fun interface CompileDirectories {
    /** The directory [file], a path, was compiled in, or `null` where it isn't known. */
    fun of(file: String): String?

    companion object {
        val UNKNOWN = CompileDirectories { null }
    }
}

/** `Path.relative_to/2`: [path] relative to [directory] when below it, otherwise [path] itself, each split as [os] splits. */
internal fun relativeTo(path: String, directory: String, os: OS): String {
    val segments = Filename.split(path.toByteArray(), os)
    val base = Filename.split(directory.toByteArray(), os)
    val below = segments.size > base.size && base.indices.all { segments[it].contentEquals(base[it]) }

    return if (below) segments.drop(base.size).joinToString("/") { String(it) } else path
}
