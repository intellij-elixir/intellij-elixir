package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangList
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_IN_VM_ORDER
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import java.io.File

/**
 * `Macro.expand_once/2` of `use` and each `Kernel` sigil on every leg, against what that leg's Elixir and Erlang/OTP
 * give (`sigil_output/<leg>/A.txt`, from `generate.exs` beside it): the quoted term printed as
 * [KernelSummaryOutputTest] prints it, a `Regex` as [OutputFixture] says, or the error the macro raises. A leg runs
 * its Elixir on the OTP its first line names; a directory suffix `-otp-<major>` tells legs of one Elixir apart.
 */
class SigilOutputTest : ExpanderTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testUse() = assertFamily("use")

    fun testSigilUpperS() = assertFamily("sigil_S")

    fun testSigilLowerS() = assertFamily("sigil_s")

    fun testSigilUpperC() = assertFamily("sigil_C")

    fun testSigilLowerC() = assertFamily("sigil_c")

    fun testSigilUpperR() = assertFamily("sigil_R")

    fun testSigilLowerR() = assertFamily("sigil_r")

    fun testSigilUpperD() = assertFamily("sigil_D")

    fun testSigilUpperT() = assertFamily("sigil_T")

    fun testSigilUpperN() = assertFamily("sigil_N")

    fun testSigilUpperU() = assertFamily("sigil_U")

    fun testSigilLowerW() = assertFamily("sigil_w")

    fun testSigilUpperW() = assertFamily("sigil_W")

    fun testEveryCaseIsInAFamily() =
        assertEquals(
            emptyList<String>(),
            LEGS.flatMap { leg -> cases(leg).map { it.label } }.distinct().filter { family(it) !in FAMILIES },
        )

    /** Every case of [family] on every leg, compared as one text so a run shows each case that differs. */
    private fun assertFamily(family: String) {
        val compared = LEGS.flatMap { leg ->
            cases(leg).filter { family(it.label) == family && it.expected != OutputFixture.SKIP }.map { case ->
                val header = "## $leg ${case.label}: ${case.source}\n"
                val level = level(leg)
                val expected = if (keysAreTheVMs(level)) sortedKeys(case.expected) else case.expected

                header + expected to header + actual(level, case)
            }
        }

        assertFalse("no case of $family", compared.isEmpty())
        assertEquals(compared.joinToString("\n") { it.first }, compared.joinToString("\n") { it.second })
    }

    /** What the expander gives [case] at [level], printed as the generator prints Elixir's. */
    private fun actual(level: ElixirLanguageLevel, case: OutputFixture.Case): String {
        val env = Env.empty(level, KERNEL_IMPORTS).copy(context = case.context)
        val state = ExState.empty(level).copy(read = mapOf(Variable("x", Variable.NIL) to 0, Variable("y", Variable.NIL) to 1), version = 2)
        val run = Run(level, ExpansionObserver.NONE, NO_EXPORTS, NO_STRUCTS)

        // Parsed as [level]'s Elixir parses it, whichever leg runs the test.
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)

        return when (val expanded = macroExpandOnce(lower(case.source, level), state, env, run)) {
            is MacroExpanded.Node -> {
                val term = expanded.node.toOtp()

                OutputFixture.render(term) + "\nmeta: " + OutputFixture.render(OtpErlangList(OutputFixture.meta(term).toTypedArray()))
            }
            MacroExpanded.Dir -> "DIR"
            is MacroExpanded.Stopped ->
                when (val expansion = expanded.expansion) {
                    is Expansion.Error -> {
                        val message = OutputFixture.RAISE.matchEntire(case.expected)?.groupValues?.get(1)?.let(OutputFixture::unescape)

                        if (message != null && ErrorKinds.pattern(expansion.kind).containsMatchIn(message)) {
                            case.expected
                        } else {
                            "ERROR ${expansion.kind}"
                        }
                    }
                    is Expansion.Unported -> "UNPORTED `${expansion.at.meta.origin.substring(case.source)}`"
                    is Expansion.Opaque -> "OPAQUE ${ExpanderTestCase.render(expansion.dispatch)}"
                    is Expansion.Expanded -> "EXPANDED"
                }
        }
    }

    private companion object {
        val ROOT = File("testData/org/elixir_lang/expander/sigil_output")

        val LEGS: List<String> =
            ROOT.listFiles { file -> file.isDirectory }!!
                .map { it.name }
                .sortedWith(compareBy({ ElixirLanguageLevel.of(it.substringBefore("-otp-")).elixir }, { it }))

        /** The macros the cases call, at every leg; a case calling a name a leg lacks is `SKIP` there. */
        val KERNEL_IMPORTS = KernelImports(
            functions = emptyList(),
            macros = (
                listOf(NameArity("use", 1), NameArity("use", 2)) +
                    "CDNRSTUWcrsw".map { NameArity("sigil_$it", 2) }
                ).sortedWith(compareBy({ it.name }, { it.arity })),
        )

        val FAMILIES = setOf("use") +
            "CDNRSTUWcrsw".map { "sigil_$it" }

        /** `use` or `sigil_<letter>`, the first words of a case's label. */
        fun family(label: String): String = label.split('_').take(if (label.startsWith("use_")) 1 else 2).joinToString("_")

        fun cases(leg: String): List<OutputFixture.Case> = OutputFixture.cases(File(ROOT, "$leg/A.txt"))

        private val KEYS = Regex("""keys: \[([^\]]*)]""")

        /** A `Regex`'s keys are in the atoms' index order there, which the expander can't know, so they are compared sorted. */
        fun keysAreTheVMs(level: ElixirLanguageLevel): Boolean = ESCAPED_MAP_IN_VM_ORDER.isSufficient(level)

        fun sortedKeys(text: String): String =
            KEYS.replace(text) { match -> "keys: [" + match.groupValues[1].split(", ").sorted().joinToString(", ") + "]" }

        /** The leg's Elixir, on the Erlang/OTP its fixture's first line names. */
        fun level(leg: String): ElixirLanguageLevel {
            val otp = Regex("""OTP ([\d.]+)""").find(File(ROOT, "$leg/A.txt").useLines { it.first() })!!.groupValues[1]

            return ElixirLanguageLevel.of(leg.substringBefore("-otp-"), otp)
        }
    }
}
