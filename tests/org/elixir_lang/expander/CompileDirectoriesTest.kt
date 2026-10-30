package org.elixir_lang.expander

import com.intellij.util.system.OS
import junit.framework.TestCase

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

    private fun relative(file: String, directory: String, os: OS) = relativeTo(file, directory, os)
}
