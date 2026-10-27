package org.elixir_lang.expander

/** The directory Elixir compiled each file in, the working directory a kept `quote`'s file is taken relative to. */
fun interface CompileDirectories {
    /** The directory [file], a path, was compiled in, or `null` where it isn't known. */
    fun of(file: String): String?

    /**
     * `Path.relative_to_cwd/1` of [file] where Elixir compiled it: relative to its directory ([of]) when below it,
     * otherwise [file] itself; `null` where the directory isn't known.
     */
    fun relativeToCwd(file: String): String? = of(file)?.let { relativeTo(file, it) }

    companion object {
        val UNKNOWN = CompileDirectories { null }
    }
}

/** `Path.relative_to/2`: [path] relative to [directory] when below it, otherwise [path] itself. */
private fun relativeTo(path: String, directory: String): String {
    val segments = splitPath(path)
    val base = splitPath(directory)

    return if (segments.size > base.size && segments.subList(0, base.size) == base) {
        segments.drop(base.size).joinToString("/")
    } else {
        path
    }
}

/** `Path.split/1`: an absolute path's root, then each name. */
private fun splitPath(path: String): List<String> =
    listOfNotNull("/".takeIf { path.startsWith("/") }) + path.split("/").filter(String::isNotEmpty)
