package org.elixir_lang.expander.interpreter

import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.DirectoryListing
import org.elixir_lang.expander.FileEntry
import org.elixir_lang.expander.cancelledAfter
import org.junit.Assert.assertThrows

/**
 * `filelib:wildcard/1` over a tree that the OS resolves a path in, where the rows `InterpreterTest` checks against Elixir
 * can't reach: Windows, where every path OTP gives the OS is resolved from its text by `GetFullPathNameW`, and a link to
 * a directory, and a pattern whose match takes OTP too long to run as a row.
 */
class FilelibTest : PlatformTestCase() {
    /**
     * On Windows a `..` after a file or a missing name is resolved from the text, as a directory's is, but a pattern with
     * no wildcard matches only where each name a `..` follows is a directory, as `do_exists/3` reads each.
     */
    fun testWindowsResolvesADotDotFromTheText() {
        assertEquals(listOf("c:/tree/a.ex/../a.ex", "c:/tree/a.ex/../sub"), wildcard("c:/tree/a.ex/../*", OS.Windows))
        assertEquals(emptyList<String>(), wildcard("c:/tree/missing/../a.ex", OS.Windows))
        assertEquals(emptyList<String>(), wildcard("c:/tree/a.ex/../a.ex", OS.Windows))
        assertEquals(listOf("c:/tree/sub/../a.ex"), wildcard("c:/tree/sub/../a.ex", OS.Windows))
        assertEquals(listOf("c:/tree/sub/../sub/../a.ex"), wildcard("c:/tree/sub/../sub/../a.ex", OS.Windows))
        assertEquals(listOf("c:/tree/../../tree/sub/../a.ex"), wildcard("c:/tree/../../tree/sub/../a.ex", OS.Windows))
    }

    /**
     * On Windows a pattern with no wildcard whose `..` follows a link to a directory declines, as `do_exists/3` reads the
     * link, which isn't a directory, and the run's files give only what it links to. A directory whose path on disk
     * differs only in its drive's case is no link.
     */
    fun testWindowsALinkBeforeALiteralDotDotDeclines() {
        // `c:/tree/link` links to `c:/other`.
        val directories = setOf("c:/", "c:/tree", "c:/other")
        val physical = mapOf("c:/tree/link" to "C:/other")
        val entry = { path: String ->
            physical[path]?.let(FileEntry::Directory)
                ?: when (path) {
                    in directories -> FileEntry.Directory(path.replaceFirst("c:", "C:"))
                    "c:/tree/t.ex" -> FileEntry.File
                    else -> FileEntry.Missing
                }
        }
        val filelib = Filelib(OS.Windows, { it }, { null }, entry, {})

        assertThrows(Filelib.Unanswered::class.java) { filelib.wildcard("c:/tree/link/../t.ex") { "." } }
        assertEquals(listOf("c:/tree/../tree/t.ex"), filelib.wildcard("c:/tree/../tree/t.ex") { "." })
    }

    /** Elsewhere a `..` after a file or a missing name is `enotdir` or `enoent`, so nothing matches. */
    fun testUnixResolvesADotDotOnlyAfterADirectory() {
        assertEquals(emptyList<String>(), wildcard("/tree/a.ex/../*", OS.Linux))
        assertEquals(emptyList<String>(), wildcard("/tree/missing/../a.ex", OS.Linux))
        assertEquals(listOf("/tree/sub/../a.ex"), wildcard("/tree/sub/../a.ex", OS.Linux))
    }

    /** Elsewhere a `..` after a link to a directory goes to the parent of the directory it links to, not the link's. */
    fun testUnixResolvesADotDotAfterALinkOnDisk() {
        // `/tree/link` links to `/other/deep`.
        val names = mapOf(
            "/tree" to listOf(DirectoryListing.Entry("link", true), DirectoryListing.Entry("t.ex", false)),
            "/other" to listOf(DirectoryListing.Entry("deep", true), DirectoryListing.Entry("o.ex", false)),
            "/other/deep" to listOf(DirectoryListing.Entry("d.ex", false)),
        )
        val physical = mapOf("/tree/link" to "/other/deep")
        val on = { path: String -> physical[path] ?: path }
        val entry = { path: String -> if (on(path) in names) FileEntry.Directory(on(path)) else FileEntry.File }
        val filelib = Filelib(OS.Linux, { it }, { names[on(it)] }, entry, {})

        assertEquals(listOf("/tree/link/../deep", "/tree/link/../o.ex"), filelib.wildcard("/tree/link/../*") { "." })
        assertEquals(listOf("/tree/link/../o.ex"), filelib.wildcard("/tree/l*/../o.ex") { "." })
    }

    /**
     * On Windows a pattern holding a part that Windows may trim, or take as a device or a file's stream, declines, as what
     * it names isn't read.
     */
    fun testWindowsAnUnreadPartDeclines() {
        for (pattern in listOf("c:/tree./*.ex", "c:/tree/nul", "c:/tree/Com1.txt/*.ex", "c:/tree/a.ex::\$DATA")) {
            assertThrows(Filelib.Unanswered::class.java) { wildcard(pattern, OS.Windows) }
        }
        assertEquals(emptyList<String>(), wildcard("/tree./*.ex", OS.Linux))
        assertEquals(emptyList<String>(), wildcard("/tree/nul", OS.Linux))
    }

    /**
     * A `**` walk lists each directory it reaches by its path, so a link to a directory above it is listed again below
     * it, as OTP lists it; past [COPY_NODES] listings it declines, having listed that many.
     */
    fun testADoubleStarWalkPastTheBudgetDeclines() {
        var listed = 0
        // Every directory to a depth of 21 holds two, more directories than the budget lists.
        val list = { path: String ->
            if (++listed > COPY_NODES) fail("listed past the budget")
            if (path.count { it == '/' } > 21) emptyList() else listOf(DirectoryListing.Entry("a", true), DirectoryListing.Entry("b", true))
        }
        val filelib = Filelib(OS.Linux, { it }, list, { FileEntry.Directory(it) }, {})

        assertThrows(Filelib.Unanswered::class.java) { filelib.wildcard("/t/**/none") { "." } }
        assertEquals(COPY_NODES, listed)
    }

    /**
     * A part of many `*`s that a long name doesn't match gives OTP's answer at once, though OTP tries every split of the
     * name between its `*`s.
     */
    fun testManyStarsThatALongNameDoesntMatchAnswerAtOnce() {
        val name = "a".repeat(60)
        val filelib = Filelib(
            OS.Linux,
            { it },
            { path -> listOf(DirectoryListing.Entry(name, false)).takeIf { path == "/t" } },
            { path -> if (path == "/t") FileEntry.Directory(path) else FileEntry.File },
            {},
        )
        val stars = "*a".repeat(12)

        assertEquals(emptyList<String>(), cancelledAfter(30) { filelib.wildcard("/t/${stars}b") { "." } })
        assertEquals(listOf("/t/$name"), cancelledAfter(30) { filelib.wildcard("/t/$stars") { "." } })
    }

    /**
     * On Windows a later part relative to a drive starts the path again, so a match can be shorter than the working
     * directory `filelib` takes off it, where its `lists:nthtail/2` fails; that declines.
     */
    fun testWindowsAMatchShorterThanTheWorkingDirectoryDeclines() {
        val entry = { path: String ->
            when (path) {
                "c:/proj", "c:/proj/lib" -> FileEntry.Directory(path)
                "c:/proj/b" -> FileEntry.File
                else -> FileEntry.Missing
            }
        }
        val absolute = { path: String ->
            when (Filename.pathtype(path, OS.Windows)) {
                Filename.PathType.ABSOLUTE -> path
                Filename.PathType.RELATIVE -> "c:/proj/$path"
                Filename.PathType.VOLUME_RELATIVE -> "c:/proj/${path.substring(2)}"
            }
        }
        val filelib = Filelib(OS.Windows, absolute, { null }, entry, {})

        assertThrows(Filelib.Unanswered::class.java) { filelib.wildcard("lib/c:b") { "c:/proj" } }
    }

    /** [pattern]'s matches in a tree of `tree/a.ex` and `tree/sub/b.ex` below the root [os] names. */
    private fun wildcard(pattern: String, os: OS): List<String> {
        val root = if (os == OS.Windows) "c:/" else "/"
        val directories = mapOf(root to listOf("tree"), "${root}tree" to listOf("a.ex", "sub"), "${root}tree/sub" to listOf("b.ex"))
        val files = setOf("${root}tree/a.ex", "${root}tree/sub/b.ex")
        val list = { path: String ->
            directories[path]?.map { DirectoryListing.Entry(it, "${path.trimEnd('/')}/$it" in directories) }
        }
        val entry = { path: String ->
            when (path) {
                in directories -> FileEntry.Directory(path)
                in files -> FileEntry.File
                else -> FileEntry.Missing
            }
        }

        return Filelib(os, { it }, list, entry, {}).wildcard(pattern) { "." }
    }
}
