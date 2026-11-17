package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.IoTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.util.system.OS
import org.elixir_lang.expander.interpreter.Filename
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A module body's reads of the project's files by path, `File.read!/1` and `Path.wildcard/1`, hold its file's expansion
 * until what they read changes: a file's saved text, or a file created or deleted where a wildcard lists.
 */
class FileStampsHeavyTest : ConfigHeavyTestCase() {
    fun testAnEditedTemplateRerunsOnlyItsReader() {
        val root = project(A to reader("A", "a.heex"), B to reader("B", "b.heex"), "lib/a.heex" to "a\n", "lib/b.heex" to "b\n")
        val aFile = psi(root, A)
        val bFile = psi(root, B)
        val a = expansion(aFile)
        val b = expansion(bFile)

        assertEquals("\"a\n\"", value(root, a, "A"))

        save(root.findFileByRelativePath("lib/a.heex")!!, "edited\n")

        val edited = expansion(aFile)
        assertNotSame("the reader of the edited file", a, edited)
        assertEquals("\"edited\n\"", value(root, edited, "A"))
        assertSame("the reader of another file", b, expansion(bFile))
    }

    /** A document's unsaved text isn't the file's: the run reads what is saved. */
    fun testAnUnsavedEditIsntRead() {
        val root = project(A to reader("A", "a.heex"), "lib/a.heex" to "a\n")
        val aFile = psi(root, A)
        val a = expansion(aFile)
        val template = root.findFileByRelativePath("lib/a.heex")!!

        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(template)!!.setText("unsaved\n")
        }

        assertSame(a, expansion(aFile))
        assertEquals("\"a\n\"", value(root, a, "A"))
        assertEquals("a run afresh", "\"a\n\"", value(root, freshExpansion(project, aFile), "A"))
    }

    /**
     * A file `Code` follows is read through its PSI, which a save of its document doesn't move, so the save doesn't rerun
     * the module that follows it.
     */
    fun testASaveOfAFollowedFileDoesntRerunItsFollower() {
        val root = project(
            A to "defmodule A do\n  Code.require_file(\"../support/h.exs\", __DIR__)\n  @t H.h()\n  def t, do: @t\nend\n",
            "support/h.exs" to "defmodule H do\n  def h, do: 1\nend\n",
        )
        val aFile = psi(root, A)
        val document = FileDocumentManager.getInstance().getDocument(root.findFileByRelativePath("support/h.exs")!!)!!

        assertEquals("1", value(root, expansion(aFile), "A"))

        edit(project, psi(root, "support/h.exs"), "defmodule H do\n  def h, do: 2\nend\n")

        val edited = expansion(aFile)
        assertEquals("2", value(root, edited, "A"))

        WriteAction.runAndWait<Throwable> { FileDocumentManager.getInstance().saveDocument(document) }

        assertSame(edited, expansion(aFile))
    }

    fun testAFileCreatedOrDeletedRerunsOnlyTheModulesWhoseWildcardCoversIt() {
        val root = project(
            W to "defmodule W do\n  @files Path.wildcard(Path.join(__DIR__, \"po/**/*.po\"))\n  def files, do: @files\nend\n",
            A to reader("A", "a.heex"),
            "lib/a.heex" to "a\n",
            "lib/po/x.po" to "x\n",
        )
        val wFile = psi(root, W)
        val aFile = psi(root, A)
        val w = expansion(wFile)
        val a = expansion(aFile)

        assertEquals("[\"<root>/lib/po/x.po\"]", value(root, w, "W"))

        VfsTestUtil.createFile(root, "lib/elsewhere.po")

        assertSame("a file the wildcard doesn't cover", w, expansion(wFile))

        VfsTestUtil.createFile(root, "lib/po/deep/y.po")

        val created = expansion(wFile)
        assertNotSame("a file created below the wildcard", w, created)
        assertEquals("[\"<root>/lib/po/deep/y.po\", \"<root>/lib/po/x.po\"]", value(root, created, "W"))
        assertSame("a module with no wildcard", a, expansion(aFile))

        VfsTestUtil.deleteFile(root.findFileByRelativePath("lib/po/x.po")!!)

        val deleted = expansion(wFile)
        assertNotSame("a file deleted below the wildcard", created, deleted)
        assertEquals("[\"<root>/lib/po/deep/y.po\"]", value(root, deleted, "W"))
        assertSame("a module with no wildcard", a, expansion(aFile))
    }

    /** A file larger than a binary the interpreter builds isn't read, and its reader reruns once it is saved smaller. */
    fun testAFileTooLargeToHoldIsntRead() {
        val root = project(A to reader("A", "a.heex"), "lib/a.heex" to "a".repeat((1 shl 24) + 1))
        val aFile = psi(root, A)

        assertEquals("unknown", value(root, expansion(aFile), "A"))

        save(root.findFileByRelativePath("lib/a.heex")!!, "a\n")

        assertEquals("\"a\n\"", value(root, expansion(aFile), "A"))
    }

    /**
     * A file the IDE has never read holds its length as last refreshed, so one grown on disk since past what a binary
     * the interpreter builds holds isn't read either.
     */
    fun testAFileGrownOnDiskPastWhatABinaryHoldsIsntRead() {
        // Outside the project, so no index reads it.
        val disk = File(createTempDir("data"), "a.dat")
        disk.writeText("a\n")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(disk)!!
        val root = project(A to "defmodule A do\n  @t File.read!(${elixirString(disk.invariantSeparatorsPath)})\n  def t, do: @t\nend\n")
        disk.writeText("a".repeat((1 shl 24) + 1))

        assertEquals("unknown", value(root, expansion(psi(root, A)), "A").take(20))
    }

    /** A relative wildcard lists from the project, where Mix compiles, and reruns as a file is created where it lists. */
    fun testARelativeWildcardRerunsAsAFileIsCreatedWhereItLists() {
        val root = project(
            W to "defmodule W do\n  @files Path.wildcard(\"priv/gettext/*/LC_MESSAGES/*.po\")\n  def files, do: @files\nend\n",
            "priv/gettext/en/LC_MESSAGES/default.po" to "x\n",
        )
        val wFile = psi(root, W)
        val w = expansion(wFile)

        assertEquals("[\"priv/gettext/en/LC_MESSAGES/default.po\"]", value(root, w, "W"))

        VfsTestUtil.createFile(root, "priv/gettext/en/LC_MESSAGES/errors.po")

        val created = expansion(wFile)
        assertNotSame("a file created where it lists", w, created)
        assertEquals("[\"priv/gettext/en/LC_MESSAGES/default.po\", \"priv/gettext/en/LC_MESSAGES/errors.po\"]", value(root, created, "W"))
    }

    /**
     * Off Windows a name holding a `\` isn't read, as the IDE's file system reads it as a separator and would find
     * another file: Elixir reads `a\b.heex`, which the IDE would take for `a/b.heex`.
     */
    fun testANameHoldingABackslashOffWindowsIsntRead() {
        if (OS.CURRENT == OS.Windows) return
        val root = project(
            A to "defmodule A do\n  @t File.read(Path.join(__DIR__, \"a\\\\b.heex\"))\n  def t, do: @t\nend\n",
            "lib/a/b.heex" to "other\n",
        )
        File(root.path, "lib/a\\b.heex").writeText("a\n")

        assertEquals("unknown", value(root, expansion(psi(root, A)), "A"))
    }

    fun testAReadOfAMissingFileRerunsWhenItIsCreated() {
        val root = project(A to reader("A", "a.heex"))
        val aFile = psi(root, A)

        assertEquals("raises Elixir.File.Error", ended(expansion(aFile), "A"))

        VfsTestUtil.createFile(root, "lib/a.heex")

        assertEquals("\"\"", value(root, expansion(aFile), "A"))
    }

    /** A read of a missing file, or past `..`, reruns as the names it went through come or go, not as others do. */
    fun testAReadRerunsOnlyAsWhatItWentThroughMoves() {
        val root = project(
            A to "defmodule A do\n  @t {File.read(Path.join(__DIR__, \"missing.heex\")), File.read(Path.join(__DIR__, \"sub/../b.heex\"))}\n  def t, do: @t\nend\n",
            "lib/b.heex" to "b\n",
            "lib/sub/keep" to "",
        )
        val aFile = psi(root, A)
        val a = expansion(aFile)

        assertEquals("{{:error, :enoent}, {:ok, \"b\n\"}}", value(root, a, "A"))

        VfsTestUtil.createFile(root, "lib/other.heex")
        VfsTestUtil.createFile(root, "lib/sub/other.heex")

        assertSame("names created beside what it read", a, expansion(aFile))
    }

    /** A link to nothing is missing to a read through it and to `File.exists?/1`, as the kernel follows it. */
    fun testADanglingLinkIsMissing() {
        if (!IoTestUtil.isSymLinkCreationSupported) return
        val root = project(
            A to "defmodule A do\n  @t {File.exists?(Path.join(__DIR__, \"dangling\")), File.read(Path.join(__DIR__, \"dangling\")), File.read(Path.join(__DIR__, \"dangling/x\"))}\n  def t, do: @t\nend\n",
        )
        link(root, "lib/dangling", File(root.path, "nowhere").toPath())

        assertEquals("{false, {:error, :enoent}, {:error, :enoent}}", value(root, expansion(psi(root, A)), "A"))
    }

    /** A link to nothing is there to a wildcard, as `filelib` reads the link, not what it links to. */
    fun testAWildcardFindsADanglingLink() {
        if (!IoTestUtil.isSymLinkCreationSupported) return
        val root = project(
            W to "defmodule W do\n  @files {Path.wildcard(Path.join(__DIR__, \"dangling\")), Path.wildcard(Path.join(__DIR__, \"dang*\"))}\n  def files, do: @files\nend\n",
        )
        link(root, "lib/dangling", File(root.path, "nowhere").toPath())

        assertEquals("{[\"<root>/lib/dangling\"], [\"<root>/lib/dangling\"]}", value(root, expansion(psi(root, W)), "W"))
    }

    /**
     * A link given another target reruns a read whose `..` went through it, as the `..` is now another directory. Windows
     * resolves a `..` from the text, so there the link isn't gone through.
     */
    fun testALinkRetargetedRerunsAReadThroughIt() {
        if (!IoTestUtil.isSymLinkCreationSupported || OS.CURRENT == OS.Windows) return
        val root = project(
            W to "defmodule W do\n  @files Path.wildcard(Path.join(__DIR__, \"link/../*.ex\"))\n  def files, do: @files\nend\n",
            "one/x.ex" to "",
            "one/sub/k" to "",
            "two/y.ex" to "",
            "two/sub/k" to "",
        )
        link(root, "lib/link", File(root.path, "one/sub").toPath())
        val wFile = psi(root, W)
        val w = expansion(wFile)

        assertEquals("[\"<root>/lib/link/../x.ex\"]", value(root, w, "W"))

        link(root, "lib/link", File(root.path, "two/sub").toPath())

        val retargeted = expansion(wFile)
        assertNotSame("the link retargeted", w, retargeted)
        assertEquals("[\"<root>/lib/link/../y.ex\"]", value(root, retargeted, "W"))
    }

    /**
     * A file created on disk in a link's target reruns a wildcard listing through the link once the VFS refreshes the
     * link: the listing is stamped by the link's path, and the IDE's file watcher maps a change in the target to it.
     */
    fun testAFileCreatedInALinksTargetRerunsAWildcardThroughIt() {
        if (!IoTestUtil.isSymLinkCreationSupported) return
        val root = project(
            W to "defmodule W do\n  @files Path.wildcard(Path.join(__DIR__, \"link/*.ex\"))\n  def files, do: @files\nend\n",
            "one/x.ex" to "",
        )
        link(root, "lib/link", File(root.path, "one").toPath())
        val wFile = psi(root, W)
        val w = expansion(wFile)

        assertEquals("[\"<root>/lib/link/x.ex\"]", value(root, w, "W"))

        File(root.path, "one/y.ex").createNewFile()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val created = expansion(wFile)
        assertNotSame("a file created in the link's target", w, created)
        assertEquals("[\"<root>/lib/link/x.ex\", \"<root>/lib/link/y.ex\"]", value(root, created, "W"))
    }

    /**
     * A `**` below a link to a directory above it lists through the link as deep as the OS resolves a path, as OTP's
     * walk does: Linux takes 40 links in one path, so Elixir's compile finds the file below 0 to 40 of them.
     */
    fun testADoubleStarBelowALinkToItsOwnDirectoryListsAsDeepAsTheOsResolves() {
        if (!IoTestUtil.isSymLinkCreationSupported || OS.CURRENT != OS.Linux) return
        val root = project(
            W to "defmodule W do\n  @files length(Path.wildcard(Path.join(__DIR__, \"**/w.ex\")))\n  def files, do: @files\nend\n",
        )
        link(root, "lib/loop", File(root.path, "lib").toPath())

        assertEquals("41", value(root, expansion(psi(root, W)), "W"))
    }

    /** `module`, whose `@t` is the text of [file] beside it, read with `File.read!/1`. */
    private fun reader(module: String, file: String) =
        "defmodule $module do\n  @t File.read!(Path.join(__DIR__, \"$file\"))\n  def t, do: @t\nend\n"

    /** [file]'s expansion, which the holder keeps on [file]'s PSI: a test holds that PSI while it asks again. */
    private fun expansion(file: PsiFile): FileExpansion = expansion(holder, file)

    /** [module]'s `@t` or `@files`, its root's path written `<root>`. */
    private fun value(root: VirtualFile, expansion: FileExpansion, module: String): String {
        val attributes = result(expansion, module).attributes.final
        // The run's paths have a Windows drive lowercased.
        val rootPath = Filename.lowercaseDrive(root.path, OS.CURRENT)

        return rendered(attributes["t"] ?: attributes["files"]).replace(rootPath, "<root>")
    }

    /** How [module] ended: `compiled`, or what it raised. */
    private fun ended(expansion: FileExpansion, module: String): String =
        when (val ended = result(expansion, module).ended) {
            is ExpansionResult.Ended.Raised -> "raises ${ended.error.kind}"
            else -> "$ended"
        }

    /** [module]'s result in [expansion]. */
    private fun result(expansion: FileExpansion, module: String): ExpansionResult =
        ProjectModules.results(expansion).single { it.module == "Elixir.$module" }

    /** A link at [path] to [target], made on disk, or given [target] where it is there, then refreshed into the VFS. */
    private fun link(root: VirtualFile, path: String, target: Path) {
        val link = File(root.path, path).toPath()
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, target)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private companion object {
        const val A = "lib/a.ex"
        const val B = "lib/b.ex"
        const val W = "lib/w.ex"
    }
}
