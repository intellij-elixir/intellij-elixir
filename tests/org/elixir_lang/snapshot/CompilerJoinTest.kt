package org.elixir_lang.snapshot

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.snapshot.CompilerFacts.Fact
import org.elixir_lang.snapshot.CompilerFacts.INPUTS
import org.elixir_lang.snapshot.CompilerFacts.Position
import org.elixir_lang.snapshot.CompilerFacts.ROOT
import org.elixir_lang.snapshot.CompilerFacts.committed
import java.io.File

/**
 * Fails when what the plugin answers at the committed compiler facts differs from the join committed for the leg,
 * when a join case gets another outcome, or when a committed fact's position is not one the plugin places at an
 * offset in its file for that version.
 */
class CompilerJoinTest : SnapshotTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    fun testCallableDeclaration() = assertJoinMatchesGolden("callable_declaration")

    fun testColumns() = assertJoinMatchesGolden("columns")

    fun testInputs() = assertJoinMatchesGolden("inputs")

    fun testALocalCallIsMatched() =
        assertJoins(
            listOf("ref matched ref a.ex:3:14 local_function A f 1"),
            "ref a.ex:3:14 local_function A f 1",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: f(1)
                end
            """
        )

    fun testARemoteCallIsMatchedAtItsDotOrItsName() =
        assertJoins(
            listOf(
                "ref matched ref a.ex:3:15 remote_function B f 1",
                "ref matched ref a.ex:3:16 remote_function B f 1",
            ),
            "ref a.ex:3:15 remote_function B f 1\nref a.ex:3:16 remote_function B f 1",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: B.f(1)
                end
            """,
            B,
        )

    fun testCandidatesInAnotherModuleAreOther() =
        assertJoins(
            listOf("ref other ref a.ex:3:16 remote_function C f 1"),
            "ref a.ex:3:16 remote_function C f 1",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: B.f(1)
                end
            """,
            B,
        )

    fun testNoCandidateIsNothing() =
        assertJoins(
            listOf("ref nothing ref a.ex:2:19 remote_function Nope f 1"),
            "ref a.ex:2:19 remote_function Nope f 1",
            "a.ex" to """
                defmodule A do
                  def g, do: Nope.f(1)
                end
            """
        )

    fun testACaptureOfANameAndArityIsNoUse() =
        assertJoins(
            listOf(
                "ref no use ref a.ex:3:14 local_function A run 1",
                "ref no use ref a.ex:3:15 local_function A run 1",
            ),
            "ref a.ex:3:14 local_function A run 1\nref a.ex:3:15 local_function A run 1",
            "a.ex" to """
                defmodule A do
                  def run(x), do: x
                  def g, do: &run/1
                end
            """
        )

    fun testACallInsideACaptureIsMatched() =
        assertJoins(
            listOf("ref matched ref a.ex:2:19 remote_function Map put 3"),
            "ref a.ex:2:19 remote_function Map put 3",
            "a.ex" to """
                defmodule A do
                  def g, do: &Map.put(&1, :k, :v)
                end
            """
        )

    fun testACandidateOfAnotherArityIsArity() =
        assertJoins(
            listOf("ref arity ref a.ex:2:16 remote_function B f 2"),
            "ref a.ex:2:16 remote_function B f 2",
            "a.ex" to """
                defmodule A do
                  def g, do: B.f(1, 2)
                end
            """,
            B,
        )

    fun testImportedQuotedArityGroupIsMatchedOnce() =
        assertJoins(
            listOf("ref matched ref a.ex:2:24 imported_quoted Kernel def 1"),
            "ref a.ex:2:24 imported_quoted Kernel def 1\nref a.ex:2:24 imported_quoted Kernel def 2",
            "a.ex" to """
                defmodule A do
                  def g, do: quote(do: def(f(x), do: x))
                end
            """
        )

    fun testAnInlinedGroupWithOnlyAnotherArityIsArityOnce() =
        assertJoins(
            listOf("ref arity ref a.ex:2:17 imported_function Kernel elem 1"),
            "ref a.ex:2:17 imported_function Kernel elem 1\nref a.ex:2:17 remote_function :erlang elem 1",
            "a.ex" to """
                defmodule A do
                  def g(t), do: elem(t)
                end
            """
        )

    fun testAnImportedCallAndItsInlinedCallAreMatchedOnce() {
        val (root, _) = copyInputs("snapshot/inputs")
        val facts = File("$ROOT/1.11.4/inputs.facts").readLines().filter { "\tmfa_atom.ex:3:38\t" in it }

        assertEquals(
            listOf(
                "ref\tmfa_atom.ex:3:38\timported_function\tKernel\tapply\t3",
                "ref\tmfa_atom.ex:3:38\tremote_function\t:erlang\tapply\t3",
            ),
            facts
        )
        assertEquals(
            listOf("ref matched ${facts.first().replace('\t', ' ')}"),
            joined(root, "1.11.4", facts, setOf("ref"))
        )
    }

    fun testAnUnknownArityCandidateIsUnknown() =
        assertJoins(
            listOf("ref unknown ref a.ex:4:14 local_function A f 1"),
            "ref a.ex:4:14 local_function A f 1",
            "a.ex" to """
                defmodule A do
                  require EEx
                  EEx.function_from_string(:def, :f, "", @args)
                  def g, do: f(1)
                end
            """
        )

    fun testAnAliasIsMatched() =
        assertJoins(
            listOf("alias matched alias a.ex:2:14 A.B"),
            "alias a.ex:2:14 A.B",
            "a.ex" to """
                defmodule C do
                  def g, do: A.B
                end
            """,
            "b.ex" to """
                defmodule A.B do
                end
            """,
            kinds = setOf("alias"),
        )

    fun testADeclaringModuleNameIsMatched() =
        assertJoins(
            listOf("alias matched alias a.ex:1:11 A"),
            "alias a.ex:1:11 A",
            "a.ex" to """
                defmodule A do
                end
            """,
            kinds = setOf("alias"),
        )

    fun testADeclaringModuleNameOfAnotherModuleIsOther() =
        assertJoins(
            listOf("alias other alias a.ex:1:11 Z"),
            "alias a.ex:1:11 Z",
            "a.ex" to """
                defmodule A do
                end
            """,
            kinds = setOf("alias"),
        )

    fun testADefIsMatchedOrMissing() =
        assertJoins(
            listOf(
                "def matched def A def f 1 2",
                "def nothing def A def missing 0 2",
                "declaration matched declaration A def f 1 a.ex:2",
            ),
            "def A def f 1 2\ndef A def missing 0 2",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                end
            """,
            kinds = setOf("def", "declaration"),
        )

    fun testADefOfAnotherDefinerOrArityIsUnmatchedBothWays() =
        assertJoins(
            listOf(
                "def definer def A def h 0 2",
                "def arity def A def k 3 3",
                "declaration nothing declaration A defp h 0 a.ex:2",
                "declaration nothing declaration A def k 0 a.ex:3",
            ),
            "def A def h 0 2\ndef A def k 3 3",
            "a.ex" to """
                defmodule A do
                  defp h, do: 1
                  def k, do: 1
                end
            """,
            kinds = setOf("def", "declaration"),
        )

    fun testAnUndefinedCallWithNoAdmittedCandidateIsMatched() =
        assertJoins(
            listOf("ref matched ref a.ex:3:14 local_function A f 2"),
            "diag a.ex:3 undefined_function f 2\nref a.ex:3:14 local_function A f 2",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: f(1, 2)
                end
            """
        )

    fun testAnUndefinedCallWithAnAdmittedCandidateIsResolved() =
        assertJoins(
            listOf("ref resolved ref a.ex:3:14 local_function A f 1"),
            "diag a.ex:3 undefined_function f 1\nref a.ex:3:14 local_function A f 1",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: f(1)
                end
            """
        )

    fun testAnUndefinedLocalCallLeavesARemoteCallOfItsNameOnItsLine() =
        assertJoins(
            listOf(
                "ref matched ref a.ex:2:15 local_function A f 1",
                "ref matched ref a.ex:2:23 remote_function B f 1",
            ),
            "diag a.ex:2 undefined_function f 1\n" +
                "ref a.ex:2:15 local_function A f 1\n" +
                "ref a.ex:2:23 remote_function B f 1",
            "a.ex" to """
                defmodule A do
                  def g, do: {f(1), B.f(1)}
                end
            """,
            "b.ex" to """
                defmodule B do
                  def f(x), do: x
                end
            """
        )

    /** Elixir 1.11.4 counts no heredoc indentation, so the call is at column 1 with the indentation before it. */
    fun testACallFirstOnAnIndentedHeredocInterpolationLineIsMatched() =
        assertJoins(
            listOf("ref matched ref a.ex:5:1 local_function H g 1"),
            "ref a.ex:5:1 local_function H g 1",
            "a.ex" to listOf(
                "defmodule H do",
                "  def f(x) do",
                "    \"\"\"",
                "    #{",
                "    g(x)}",
                "    \"\"\"",
                "  end",
                "",
                "  def g(x), do: x",
                "end",
            ).joinToString("\n"),
            elixirVersion = "1.11.4",
        )

    /** The emoji's second UTF-16 unit has the `+`'s column too, since no column starts inside a code point. */
    fun testAnOperatorRightAfterAnAstralCharacterIsMatched() =
        assertJoins(
            listOf("ref matched ref a.ex:2:19 imported_function Kernel + 2"),
            "ref a.ex:2:19 imported_function Kernel + 2",
            "a.ex" to """
                defmodule E do
                  def g(x), do: ?${"😀"}+x
                end
            """
        )

    fun testJoiningTwiceIsByteIdentical() {
        val (root, _) = copyInputs("snapshot/inputs")
        val elixirVersion = environment("ELIXIR_VERSION")
        val facts = File("$ROOT/$elixirVersion/inputs.facts").readLines()
        val first = goldenLines(root, elixirVersion, facts)

        assertTrue("The join wrote no line", first.isNotEmpty())
        assertEquals(first, goldenLines(root, elixirVersion, facts))
    }

    fun testAStandardLibraryCallFindsItsModule() {
        val (_, files) = copyInputs("snapshot/compiler/columns")

        val modules = ReadAction.computeBlocking<List<String?>, Throwable> {
            val call = PsiTreeUtil.findChildrenOfType(files.single(), Call::class.java)
                .first { it.functionName() == "length" }
            val use = Use.of(call)!!

            sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, false).map { it.declaringModuleName() }
        }

        assertTrue("String.length/1 finds no candidate in String: $modules", "String" in modules)
    }

    fun testEveryCommittedPositionIsInItsFile() {
        val offsets = mutableMapOf<Pair<File, String>, Map<Pair<Int, Int>, Int>>()
        val misplaced = committed().flatMap { (version, facts) ->
            val input = File(INPUTS.getValue(facts.nameWithoutExtension))

            facts.readLines().withIndex().mapNotNull { (index, line) ->
                val position = position(CompilerFacts.parse(line)) ?: return@mapNotNull null
                val file = File(input, position.path)
                val placed = file.isFile &&
                    offsets.getOrPut(file to version.name) { offsets(file, version.name) }
                        .containsKey(position.line to position.column)

                if (placed) null else "$facts:${index + 1}: $line"
            }
        }

        assertTrue("Facts at positions outside their files:\n${misplaced.joinToString("\n")}", misplaced.isEmpty())
    }

    private fun assertJoinMatchesGolden(input: String) {
        val elixirVersion = environment("ELIXIR_VERSION")
        val (root, _) = copyInputs(INPUTS.getValue(input).removePrefix("$testDataPath/"))
        val facts = File("$ROOT/$elixirVersion/$input.facts").readLines()
        val regenerate =
            LegManifest.regenerateCommand(CompilerJoinTest::class, elixirVersion, environment("ERLANG_VERSION"))
        val text = goldenLines(root, elixirVersion, facts).joinToString("\n", postfix = "\n")

        LegManifest.assertMatchesFile(ROOT, elixirVersion, "$input.join.txt", text, regenerate)
    }

    private fun goldenLines(root: VirtualFile, elixirVersion: String, facts: List<String>): List<String> =
        ReadAction.computeBlocking<List<String>, Throwable> {
            CompilerJoin.lines(CompilerJoin(project, root, elixirVersion).join(facts))
        }

    /**
     * Fails unless joining [facts] over [sources] gives [expected] for the facts of [kinds], each a kind, an outcome
     * and a line. Facts and lines are written with spaces for tabs, so no name in them may hold a space.
     */
    private fun assertJoins(
        expected: List<String>,
        facts: String,
        vararg sources: Pair<String, String>,
        kinds: Set<String> = setOf("ref"),
        elixirVersion: String = environment("ELIXIR_VERSION"),
    ) {
        val files = sources.map { (path, text) -> myFixture.addFileToProject(path, text.trimIndent()) }
        val root = files.first().virtualFile.parent
        val tabbed = facts.lines().map { it.replace(' ', '\t') }

        assertEquals(expected, joined(root, elixirVersion, tabbed, kinds))
    }

    private fun joined(
        root: VirtualFile,
        elixirVersion: String,
        facts: List<String>,
        kinds: Set<String>,
    ): List<String> =
        ReadAction.computeBlocking<List<CompilerJoin.Joined>, Throwable> {
            CompilerJoin(project, root, elixirVersion).join(facts)
        }
            .filter { it.kind in kinds }
            .map { "${it.kind} ${it.outcome.text} ${it.line.replace('\t', ' ')}" }

    private fun position(fact: Fact?): Position? =
        when (fact) {
            is Fact.Ref -> fact.position
            is Fact.Alias -> fact.position
            else -> null
        }

    /** The first offset in [file] at each (line, column) [elixirVersion]'s tokenizer counts. */
    private fun offsets(file: File, elixirVersion: String): Map<Pair<Int, Int>, Int> {
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(file.name, ElixirLanguage, file.readText()) as ElixirFile

        return ReadAction.computeBlocking<Map<Pair<Int, Int>, Int>, Throwable> {
            CompilerJoin.offsets(psiFile, elixirVersion)
        }
    }

    companion object {
        private val B = "b.ex" to """
            defmodule B do
              def f(x), do: x
            end
        """
    }
}
