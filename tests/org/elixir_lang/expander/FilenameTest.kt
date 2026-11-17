package org.elixir_lang.expander

import com.intellij.util.system.OS
import junit.framework.TestCase
import org.elixir_lang.expander.interpreter.Filename

/**
 * `filename` and `binary:split/3` of a binary, by the path syntax of the OS they run as. Every expected answer is the
 * one `filename.erl` gives with `os:type/0` fixed to `{win32, nt}` or `{unix, linux}`, on OTP 24.3.4.6 and 29.0.6, but
 * [Filename.resolved]'s on Windows, which are what `GetFullPathNameW` gives.
 */
class FilenameTest : TestCase() {
    fun testEveryOsAnswersAsDecided() {
        val decided = mapOf(
            "Windows" to "win32 nt",
            "macOS" to "unix darwin",
            "Linux" to "unix linux",
            "FreeBSD" to "unix freebsd",
            "OHOS" to null,
            "Other" to null,
        )

        for (os in OS.entries) {
            assertTrue("no answer decided for ${os.name}", decided.containsKey(os.name))
            assertEquals(os.name, decided[os.name], answer(os))
        }
    }

    fun testASplitOnUnix() {
        assertSplit(OS.Linux, "/src/lib/t.eex", "/", "src", "lib", "t.eex")
        assertSplit(OS.Linux, "a//b/", "a", "b")
        assertSplit(OS.Linux, "")
        assertSplit(OS.Linux, "/", "/")
        assertSplit(OS.Linux, "rel/x", "rel", "x")
        assertSplit(OS.Linux, "c:/a\\b", "c:", "a\\b")
        assertSplit(OS.Linux, "//server/share/a.ex", "/", "server", "share", "a.ex")
        assertSplit(OS.Linux, "C:\\a\\b.ex", "C:\\a\\b.ex")
    }

    fun testASplitOnWindows() {
        assertSplit(OS.Windows, "C:/a\\b", "c:/", "a", "b")
        assertSplit(OS.Windows, "c:/a\\b", "c:/", "a", "b")
        assertSplit(OS.Windows, "c:a", "c:", "a")
        assertSplit(OS.Windows, "C:", "c:")
        assertSplit(OS.Windows, "c:/", "c:/")
        assertSplit(OS.Windows, "//s/x", "//", "s", "x")
        assertSplit(OS.Windows, "//server/share/a.ex", "//", "server", "share", "a.ex")
        assertSplit(OS.Windows, "\\\\s\\x", "//", "s", "x")
        assertSplit(OS.Windows, "\\a\\b", "/", "a", "b")
        assertSplit(OS.Windows, "/src/a", "/", "src", "a")
        assertSplit(OS.Windows, "a//b/", "a", "b")
        assertSplit(OS.Windows, "")
        assertSplit(OS.Windows, "//", "//")
    }

    fun testADirnameOnUnix() {
        assertEquals("/src/lib", dirname(OS.Linux, "/src/lib/t.eex"))
        assertEquals("a//b", dirname(OS.Linux, "a//b/"))
        assertEquals(".", dirname(OS.Linux, ""))
        assertEquals("/", dirname(OS.Linux, "/"))
        assertEquals("rel", dirname(OS.Linux, "rel/x"))
        assertEquals("c:", dirname(OS.Linux, "c:/a\\b"))
        assertEquals("//server/share", dirname(OS.Linux, "//server/share/a.ex"))
        assertEquals(".", dirname(OS.Linux, "c.ex"))
        assertEquals("/", dirname(OS.Linux, "/c.ex"))
        assertEquals("/a/b", dirname(OS.Linux, "/a/b/"))
        assertEquals("/a//b", dirname(OS.Linux, "/a//b/c.ex"))
        assertEquals("c:/a", dirname(OS.Linux, "c:/a/c.ex"))
        assertEquals(".", dirname(OS.Linux, "C:\\a\\b.ex"))
    }

    fun testADirnameOfALongPath() {
        val names = List(20_000) { "a" }.joinToString("/")

        assertEquals("/$names", dirname(OS.Linux, "/$names/b"))
    }

    fun testADirnameOnWindows() {
        assertEquals("c:/a", dirname(OS.Windows, "c:/a\\b"))
        assertEquals("C:/a", dirname(OS.Windows, "C:/a\\b"))
        assertEquals("C:/a", dirname(OS.Windows, "C:\\a\\b.ex"))
        assertEquals("c:", dirname(OS.Windows, "c:a"))
        assertEquals("c:", dirname(OS.Windows, "c:x"))
        assertEquals("C:", dirname(OS.Windows, "C:"))
        assertEquals("c:/", dirname(OS.Windows, "c:/"))
        assertEquals("//s", dirname(OS.Windows, "//s/x"))
        assertEquals("//s", dirname(OS.Windows, "\\\\s\\x"))
        assertEquals("/a", dirname(OS.Windows, "\\a\\b"))
        assertEquals("//usr//src", dirname(OS.Windows, "\\\\usr\\\\src/kalle.erl"))
        assertEquals("/src/lib", dirname(OS.Windows, "/src/lib/t.eex"))
    }

    fun testABasenameOnUnix() {
        assertEquals("t.eex", basename(OS.Linux, "/src/lib/t.eex"))
        assertEquals("b", basename(OS.Linux, "a//b/"))
        assertEquals("", basename(OS.Linux, ""))
        assertEquals("", basename(OS.Linux, "/"))
        assertEquals("a\\b", basename(OS.Linux, "c:/a\\b"))
        assertEquals("C:\\a\\b.ex", basename(OS.Linux, "C:\\a\\b.ex"))
        assertEquals("c:", basename(OS.Linux, "c:/"))
    }

    fun testABasenameOnWindows() {
        assertEquals("b", basename(OS.Windows, "c:/a\\b"))
        assertEquals("b.ex", basename(OS.Windows, "C:\\a\\b.ex"))
        assertEquals("a", basename(OS.Windows, "c:a"))
        assertEquals("", basename(OS.Windows, "C:"))
        assertEquals("", basename(OS.Windows, "c:/"))
        assertEquals("x", basename(OS.Windows, "\\\\s\\x"))
        assertEquals("kalle.erl", basename(OS.Windows, "\\\\usr\\\\src/kalle.erl"))
    }

    /** The extension is stripped from the last name's end only, and the name's own tail is cut where it is not one. */
    fun testABasenameWithAnExtension() {
        assertEquals("c", basename(OS.Linux, "/a/b/c.ex", ".ex"))
        assertEquals("c.exs", basename(OS.Linux, "c.exs", ".ex"))
        assertEquals("x", basename(OS.Linux, "/a/x", ".long"))
        assertEquals("", basename(OS.Linux, ".ex", ".ex"))
        // `filename:basename/2` measures from the end of the whole name, so a trailing slash is cut from it.
        assertEquals("/", basename(OS.Linux, "/a/b/", ""))
        assertEquals("C:\\a\\b", basename(OS.Linux, "C:\\a\\b.ex", ".ex"))
        assertEquals("b", basename(OS.Windows, "C:\\a\\b.ex", ".ex"))
        assertEquals("b", basename(OS.Windows, "c:b.ex", ".ex"))
    }

    fun testAPathtypeOnUnix() {
        assertPathtype(OS.Linux, "/x", "absolute")
        assertPathtype(OS.Linux, "c:/x", "relative")
        assertPathtype(OS.Linux, "c:x", "relative")
        assertPathtype(OS.Linux, "//host/x", "absolute")
        assertPathtype(OS.Linux, "x", "relative")
        assertPathtype(OS.Linux, "\\x", "relative")
        assertPathtype(OS.Linux, "", "relative")
    }

    fun testAPathtypeOnWindows() {
        assertPathtype(OS.Windows, "/x", "volumerelative")
        assertPathtype(OS.Windows, "c:/x", "absolute")
        assertPathtype(OS.Windows, "c:x", "volumerelative")
        assertPathtype(OS.Windows, "//host/x", "absolute")
        assertPathtype(OS.Windows, "x", "relative")
        assertPathtype(OS.Windows, "\\x", "volumerelative")
        assertPathtype(OS.Windows, "C:\\x", "absolute")
        assertPathtype(OS.Windows, "\\\\host\\x", "absolute")
        assertPathtype(OS.Windows, "/\\host", "absolute")
        assertPathtype(OS.Windows, "c:", "volumerelative")
        assertPathtype(OS.Windows, "", "relative")
    }

    /** The drive letter `win32_splitb/1` lowercases is the only byte that changes. */
    fun testALowercasedDrive() {
        assertEquals("c:/Users/A.ex", text(Filename.lowercaseDrive(bytes("C:/Users/A.ex"))))
        assertEquals("c:", text(Filename.lowercaseDrive(bytes("c:"))))
        assertEquals("/C:/a", text(Filename.lowercaseDrive(bytes("/C:/a"))))
        assertEquals("CC:/a", text(Filename.lowercaseDrive(bytes("CC:/a"))))
        assertEquals("C", text(Filename.lowercaseDrive(bytes("C"))))
    }

    /** On Windows a `..` at a share's root stays there, as at a drive's. */
    fun testAWindowsDotDotStopsAtAShare() {
        assertEquals("//server/share", resolved("//server/share/..", OS.Windows))
        assertEquals("//server/share", resolved("//server/share/a/../..", OS.Windows))
        assertEquals("//server/share/b", resolved("//server/share/../b", OS.Windows))
        assertEquals("c:/", resolved("c:/..", OS.Windows))
    }

    /**
     * Windows may trim a part's trailing dots and spaces, takes a device's name as the device in any directory, and a
     * part holding a `:` as a file's stream, which isn't read, so such a path isn't resolved.
     */
    fun testAWindowsPartItMayReadAsOtherThanItsNameIsntResolved() {
        val paths = listOf(
            "c:/p/text.txt.", "c:/p/tree./a.ex", "c:/p/a.ex ", "c:/p/a.ex. ", "c:/p/NUL", "c:/p/con.txt", "c:/p/lpt9 .ex/a",
            "c:/p/COM\u00B9", "c:/p/lpt\u00B3.txt", "c:/p/a.ex::\$DATA", "c:/p/a.ex:s/b",
        )
        for (path in paths) {
            assertEquals(path, "unread", resolved(path, OS.Windows))
        }
        assertEquals("c:/p/a.ex", resolved("c:/p/./a.ex", OS.Windows))
        assertEquals("c:/p/null", resolved("c:/p/null", OS.Windows))
        assertEquals("/p/text.txt.", resolved("/p/text.txt.", OS.Linux))
        assertEquals("/p/a.ex ", resolved("/p/a.ex ", OS.Linux))
        assertEquals("/p/NUL", resolved("/p/NUL", OS.Linux))
        assertEquals("/p/a.ex::\$DATA", resolved("/p/a.ex::\$DATA", OS.Linux))
    }

    /** On Windows a `.` or `?` after a leading `//` names a namespace, a device's or a long path's, not a server. */
    fun testAWindowsNamespacePathIsntResolved() {
        val paths = listOf(
            "//./host/share/a.ex", "//?/UNC/host/share/a.ex",
            "\\\\.\\host\\share\\a.ex", "\\\\?\\UNC\\host\\share\\a.ex",
        )
        for (path in paths) {
            assertEquals(path, "unread", resolved(path, OS.Windows))
        }
        assertEquals("//host/share/a.ex", resolved("//host/share/a.ex", OS.Windows))
        assertEquals("//host/share/a.ex", resolved("//host/./share/a.ex", OS.Windows))
    }

    /** Windows takes any two separators in front as a share's root, though `filename:split/1` keeps one for a mixed pair. */
    fun testAWindowsRootOfMixedSeparatorsIsAShare() {
        assertEquals("//host/share/a.ex", resolved("/\\host/share/a.ex", OS.Windows))
        assertEquals("//host/share/a.ex", resolved("\\/host/share/../a.ex", OS.Windows))
        assertEquals("unread", resolved("/\\./host/share/a.ex", OS.Windows))
    }

    /** Off Windows a `\` is a byte of a name, which the OS reads as one; on Windows it is a separator. */
    fun testABackslashIsANamesByteOffWindows() {
        assertEquals("/proj/a\\b.txt", resolved("/proj/a\\b.txt", OS.Linux))
        assertEquals("/proj/a\\/b.txt", resolved("/proj/a\\/b.txt", OS.macOS))
        assertEquals("/proj/a/b.txt", resolved("/proj/a\\b.txt", OS.Windows))
    }

    fun testASplitOfABinary() {
        assertEquals(listOf("", "a", "", "b", "c"), split("/a//b/c", listOf("/"), global = true))
        assertEquals(listOf("a", "b/c"), split("a/b/c", listOf("/"), global = false))
        assertEquals(listOf("a", "b", "c"), split("a/b\\c", listOf("/", "\\"), global = true))
        assertEquals(listOf("abc"), split("abc", listOf("x"), global = true))
        assertEquals(listOf(""), split("", listOf("/"), global = true))
        assertEquals(listOf(""), split("", listOf("/"), global = false))
        assertEquals(listOf("", "a"), split("aaa", listOf("aa"), global = true))
        assertEquals(listOf("", "d"), split("abcd", listOf("ab", "abc"), global = false))
        assertEquals(listOf("", ""), split("ab", listOf("b", "ab"), global = false))
        assertEquals(listOf("a", "b"), split("a/b", listOf("/"), global = true))
    }

    /** Where [os] resolves [path], every name in it a directory at its own path. */
    private fun resolved(path: String, os: OS): String =
        when (val resolved = Filename.resolved(path, os) { it }) {
            is Filename.Resolved.At -> resolved.path
            is Filename.Resolved.NotADirectory -> "not a directory past ${resolved.prefix}"
            Filename.Resolved.Unread -> "unread"
        }

    private fun answer(os: OS) = Filename.osType(os)?.let { (family, name) -> "$family $name" }

    private fun assertSplit(os: OS, name: String, vararg parts: String) =
        assertEquals("$os $name", parts.toList(), Filename.split(bytes(name), os).map(::text))

    private fun assertPathtype(os: OS, name: String, type: String) =
        assertEquals("$os $name", type, Filename.pathtype(bytes(name), os).atom)

    private fun dirname(os: OS, name: String) = text(Filename.dirname(bytes(name), os))

    private fun basename(os: OS, name: String) = text(Filename.basename(bytes(name), os))

    private fun basename(os: OS, name: String, extension: String) =
        text(Filename.basename(bytes(name), bytes(extension), os))

    private fun split(subject: String, patterns: List<String>, global: Boolean) =
        Filename.binarySplit(bytes(subject), patterns.map(::bytes), global).map(::text)

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)
}

/** The atom `filename:pathtype/1` gives for [Filename.PathType]. */
internal val Filename.PathType.atom: String get() = name.lowercase().replace("_", "")
