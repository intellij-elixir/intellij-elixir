package org.elixir_lang.expander

import com.intellij.util.system.OS
import junit.framework.TestCase
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `Path.relative_to_cwd/1` of a file where Elixir compiled it, whose names `Path.split/1` gives by the path syntax of the
 * OS the SDK runs as.
 */
class CompileDirectoriesTest : TestCase() {
    fun testAFileBelowItsDirectoryIsRelativeToIt() {
        assertEquals("lib/a.ex", relative("/proj/lib/a.ex", "/proj", OS.Linux))
        assertEquals("a.ex", relative("/proj/a.ex", "/proj/", OS.Linux))
    }

    fun testAFileOutsideItsDirectoryStaysWhole() {
        assertEquals("/proj/lib/a.ex", relative("/proj/lib/a.ex", "/pro", OS.Linux))
        assertEquals("/other/a.ex", relative("/other/a.ex", "/proj", OS.Linux))
    }

    /** `win32_splitb/1` lowercases the drive letter of each path it splits, so the case of either is no difference. */
    fun testAWindowsDriveLetterIsComparedLowercased() {
        assertEquals("lib/a.ex", relative("c:/proj/lib/a.ex", "C:/proj", OS.Windows))
        assertEquals("lib/a.ex", relative("C:/proj/lib/a.ex", "c:/proj", OS.Windows))
        assertEquals("lib/a.ex", relative("c:/proj/lib/a.ex", "c:/proj", OS.Windows))
        assertEquals("d:/proj/lib/a.ex", relative("d:/proj/lib/a.ex", "C:/proj", OS.Windows))
    }

    /** Off Windows `c:` is a name like any other, so the case of its letter is a difference. */
    fun testAnotherOsComparesADriveLetterAsAName() {
        assertEquals("c:/proj/lib/a.ex", relative("c:/proj/lib/a.ex", "C:/proj", OS.Linux))
        assertEquals("c:/proj/lib/a.ex", relative("c:/proj/lib/a.ex", "C:/proj", OS.macOS))
    }

    /** Windows reads a backslash as a separator, and unix a name's byte. */
    fun testABackslashSeparatesNamesOnWindowsOnly() {
        assertEquals("lib/a.ex", relative("c:/proj\\lib/a.ex", "c:/proj", OS.Windows))
        assertEquals("c:/proj\\lib/a.ex", relative("c:/proj\\lib/a.ex", "c:/proj", OS.Linux))
    }

    /** A directory relative to itself is `.` from 1.12, and itself before. */
    fun testADirectoryRelativeToItselfIsDotFrom112() {
        assertEquals(".", relative("/proj", "/proj", OS.Linux))
        assertEquals("/proj", relative("/proj", "/proj", OS.Linux, ElixirLanguageLevel.of("1.11.4")))
    }

    /** Before 1.16 a path is compared as it is split, `.` and `..` with the rest; values Elixir 1.15.8's on Linux. */
    fun testBefore116APathIsComparedAsSplit() =
        assertRelative(
            "1.15.8",
            "/c/x/../y.ex" to "x/../y.ex",
            "/x/../y.ex" to "/x/../y.ex",
            "/c/./y.ex" to "./y.ex",
            "/c/../../y.ex" to "../../y.ex",
            "//h/s/y.ex" to "//h/s/y.ex",
            "a/./b.ex" to "a/./b.ex",
            "a/.." to "a/..",
        )

    /**
     * From 1.16 each path's `.` and `..` are taken out first, and a relative path is given back so; values Elixir
     * 1.16.3's on Linux.
     */
    fun testFrom116APathsDotsAreTakenOutFirst() =
        assertRelative(
            "1.16.3",
            "/c/x/../y.ex" to "y.ex",
            "/x/../y.ex" to "/y.ex",
            "/c/./y.ex" to "y.ex",
            "/c/." to ".",
            "/c/../../y.ex" to "/y.ex",
            "//h/s/y.ex" to "/h/s/y.ex",
            "/d/y.ex" to "/d/y.ex",
            "a/./b.ex" to "a/b.ex",
            "a/../b.ex" to "b.ex",
            "../b.ex" to "../b.ex",
            "a/.." to ".",
        )

    /**
     * On Windows a path outside the directory is given back as split and joined from 1.16, its drive letter lowercased,
     * and as given before.
     */
    fun testOnWindowsAPathOutsideItsDirectoryIsJoinedFrom116() {
        assertEquals("C:/other/a.ex", relative("C:/other/a.ex", "c:/proj", OS.Windows, ElixirLanguageLevel.of("1.15.8")))
        assertEquals("c:/other/a.ex", relative("C:/other/a.ex", "c:/proj", OS.Windows, ElixirLanguageLevel.of("1.16.3")))
    }

    /**
     * From 1.16 a path is absolute by its split (`split_absolute?/2`): on Windows a `//` or drive root. A root of two
     * different separators splits to `/`, so the path is relative and its `..` take the root too.
     */
    fun testFrom116OnWindowsARootOfMixedSeparatorsIsRelative() {
        val level = ElixirLanguageLevel.of("1.16.3")
        assertEquals("a.ex", relative("/\\server/share/../../../a.ex", "c:/proj", OS.Windows, level))
        assertEquals("a.ex", relative("\\/server/share/../../../a.ex", "c:/proj", OS.Windows, level))
        assertEquals("//a.ex", relative("//server/share/../../../a.ex", "c:/proj", OS.Windows, level))
    }

    /** Each of [cases], a path and what `Path.relative_to/2` gives of it against `/c` on Linux on Elixir [version]. */
    private fun assertRelative(version: String, vararg cases: Pair<String, String>) {
        for ((path, relative) in cases) assertEquals(path, relative, relative(path, "/c", OS.Linux, ElixirLanguageLevel.of(version)))
    }

    private fun relative(file: String, directory: String, os: OS, level: ElixirLanguageLevel = ElixirLanguageLevel.of("1.12.0")) =
        relativeTo(file, { directory }, os, level)
}
