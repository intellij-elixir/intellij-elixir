package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.Module
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.call.CanonicallyNamedImpl
import org.elixir_lang.psi.impl.hasKeywordKey
import org.elixir_lang.psi.impl.indexName
import java.util.concurrent.Callable

/** Half-typed code that the readers of names and import options answer as they would an unreadable value. */
class BrokenCodeEntryTest : PlatformTestCase() {
    fun testAnAtomWithABrokenInterpolationHasNoIndexName() = assertEach(BROKEN_INTERPOLATIONS.map { "f(:$it" }) {
        val atom = PsiTreeUtil.findChildOfType(myFixture.file, ElixirAtom::class.java)!!

        listOfNotNull(
            atom.indexName()?.let { "indexName() = $it" },
            atom.quote().toString().takeUnless { CURSOR in it }?.let { "quote() = $it" },
        )
    }

    fun testAKeyTooLongForAnAtomIsNoOtherKey() = assertEach(listOf("f(\"${"a".repeat(256)}\": 1)")) {
        val pair = PsiTreeUtil.findChildOfType(myFixture.file, QuotableKeywordPair::class.java)!!

        listOfNotNull("hasKeywordKey(\"do\") = true".takeIf { pair.hasKeywordKey("do") })
    }

    fun testAModuleNamedByAnAliasTooLongForAnAtomIsNamedByNoValue() =
        assertEach(listOf("defmodule A${"a".repeat(255)}, do: :ok")) {
            val call = PsiTreeUtil.findChildOfType(myFixture.file, Call::class.java)!!
            val name = org.elixir_lang.psi.Module.name(call)
            val keys = CanonicallyNamedImpl.canonicalNameSet(SyntacticCall.of(call)).filter { Module.atom(it) != null }

            listOfNotNull(
                "Module.name = $name".takeUnless { name == Module.NO_VALUE },
                "index keys $keys".takeIf { keys.isNotEmpty() },
            )
        }

    fun testABrokenOnlyValueImportsNothing() = assertEach(BROKEN_VALUES.map { "import M, only: $it" }) {
        val filter = filter()

        listOfNotNull(
            "Filter.of = Invalid(${(filter as? Import.Filter.Invalid)?.error})".takeIf { filter is Import.Filter.Invalid },
            filter.imports(EXPORTS).takeUnless { it.functions.isEmpty() && it.macros.isEmpty() }?.let { "imports $it" },
            optionValue("only").takeUnless { CURSOR in it }?.let { "quote() = $it" },
        )
    }

    fun testABrokenExceptValueSelectsEverything() = assertEach(BROKEN_VALUES.map { "import M, except: $it" }) {
        val filter = filter()

        listOfNotNull(
            "Filter.of = Invalid(${(filter as? Import.Filter.Invalid)?.error})".takeIf { filter is Import.Filter.Invalid },
            filter.imports(EXPORTS).takeUnless { it == EXPORTS }?.let { "imports $it" },
            optionValue("except").takeUnless { CURSOR in it }?.let { "quote() = $it" },
        )
    }

    fun testAnOnlyListKeyTooLongForAnAtomIsSkipped() =
        assertEach(listOf("import M, only: [\"${"a".repeat(256)}\": 1, f: 1]")) {
            listOfNotNull(filter().imports(EXPORTS).takeUnless { it == ONLY_F }?.let { "imports $it" })
        }

    fun testAnExceptListKeyTooLongForAnAtomIsSkipped() =
        assertEach(listOf("import M, except: [\"${"a".repeat(256)}\": 1, f: 1]")) {
            listOfNotNull(filter().imports(EXPORTS).takeUnless { it == EXCEPT_F }?.let { "imports $it" })
        }

    private fun filter(): Import.Filter {
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { it.functionName() == "import" }

        return Import.Filter.of(call, ElixirLanguageLevelResolver.languageLevelFor(call), null)
    }

    private fun optionValue(key: String): String =
        PsiTreeUtil.findChildrenOfType(myFixture.file, QuotableKeywordPair::class.java)
            .first { it.keywordKey.text == key }
            .keywordValue
            .quote()
            .toString()

    /** Each of [sources] answered without throwing, and with no complaint from [complaints]. */
    private fun assertEach(sources: List<String>, complaints: () -> List<String>) {
        val failures = sources.flatMap { source ->
            myFixture.configureByText("broken.ex", source)
            val result = ReadAction.nonBlocking(Callable { runCatching(complaints) }).executeSynchronously()

            result.fold({ it }, { listOf("throws $it at ${it.stackTrace.firstOrNull()}") })
                .map { "`${source.replace("\n", "\\n")}`: $it" }
        }

        assertEmpty(failures.joinToString("\n"), failures)
    }

    private companion object {
        const val CURSOR = "__cursor__"

        val BROKEN_INTERPOLATIONS = listOf("\"#{", "\"#{1 2", "\"#{)}\"", "\"#{,}\"", "\"#{end}\"", "\"#{do}\"")

        /** Each ends the file: a broken interpolation, an unterminated heredoc, and operators missing an operand. */
        val BROKEN_VALUES = BROKEN_INTERPOLATIONS + listOf(
            "\"\"\"\nabc",
            "x in", "x in )", "x in %", "x in %Foo", "x in %Foo{", "x in &", "x in @", "x not in %Foo", "1 + %Foo",
            "-", "-%", "-%Foo", "-%Foo{", "@%Foo", "!%Foo", "&%Foo", "-)", "not %Foo", "^%Foo",
        )

        val EXPORTS = Import.Imports(setOf(NameArity("f", 1)), setOf(NameArity("m", 1)))
        val ONLY_F = Import.Imports(setOf(NameArity("f", 1)), emptySet())
        val EXCEPT_F = Import.Imports(emptySet(), setOf(NameArity("m", 1)))
    }
}
