package org.elixir_lang.beam.decompiler

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.Decompiler
import org.elixir_lang.snapshot.CompilerFacts
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The external term format writes a list of integers 0 to 255 as a string, so a quoted form whose arguments are all
 * small integers reaches the printer as a string, not a list. Each test decompiles a module compiled from source and
 * compares its function's body with Elixir's `Macro.to_string` of the same quoted form.
 */
class SmallIntegerListsTest : PlatformTestCase() {
    override fun getTestDataPath(): String = DIRECTORY

    fun testFnClauseWithAnIntegerArgument() = assertBody("FnClause")

    fun testFnClauseWithSeveralIntegerArguments() = assertBody("FnClauseArguments")

    fun testCaseClauseWithAnIntegerPattern() = assertBody("CaseClause", "body(x)", "case(x) do\n  1 ->\n    :a\nend")

    fun testRemoteCallWithIntegerArguments() = assertBody("RemoteCall")

    fun testLocalCallWithIntegerArguments() = assertBody("LocalCall")

    fun testBinaryOperatorWithIntegerOperands() = assertBody("BinaryOperator")

    fun testListLiteralOfIntegers() = assertBody("ListLiteral")

    fun testNestedListLiteralsOfIntegers() = assertBody("NestedListLiteral")

    fun testImproperListOfIntegers() = assertBody("ImproperList")

    fun testBlockEndingInAnInteger() = assertBody("Block", expected = "(\n  Integer.pow(2, 3)\n  4\n)")

    fun testBlockOfIntegers() = assertBody("IntegerBlock", expected = "(\n  1\n  2\n)")

    fun testTupleOfIntegers() = assertBody("Tuple")

    fun testMapOfIntegers() = assertBody("Map")

    private fun decompiled(shape: String): String {
        VfsRootAccess.allowRootAccess(testRootDisposable, compiled.beams.path)

        val beam = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(File(compiled.beams, "Elixir.SmallIntegerLists.$shape.beam"))
        assertNotNull("Could not find the compiled $shape in VFS", beam)

        return Decompiler().decompile(beam!!).toString()
    }

    /** [expected] is the body when the decompiler prints a form its own way, not as `Macro.to_string` does. */
    private fun assertBody(shape: String, head: String = "body()", expected: String? = null) {
        val body = expected ?: File(compiled.oracle, "SmallIntegerLists.$shape.body").readText()
        val decompiled = decompiled(shape)

        assertTrue(
            "Expected the decompiled $shape to have the body\n$body\nbut it is\n$decompiled",
            decompiled.contains("  def $head do\n${body.prependIndent("    ")}\n  end")
        )
    }

    private class Compiled(val beams: File, val oracle: File)

    private companion object {
        const val DIRECTORY = "testData/org/elixir_lang/beam/decompiler/small_integer_lists"

        val compiled: Compiled by lazy {
            // The platform deletes a temp directory after the test that created it, and this is shared by all.
            val output = File("build/tmp/small-integer-lists").absoluteFile
            FileUtil.delete(output)
            FileUtil.createDirectory(output)
            val log = File(output, "compile.log")
            val process = ProcessBuilder(
                CompilerFacts.elixir().path,
                "$DIRECTORY/compile.exs",
                "$DIRECTORY/SmallIntegerLists.ex",
                output.path
            ).redirectErrorStream(true).redirectOutput(log).start()

            check(process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0) {
                "compile.exs failed:\n${log.readText()}"
            }

            Compiled(File(output, "ebin"), File(output, "oracle"))
        }
    }
}
