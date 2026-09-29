package org.elixir_lang.psi

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCallSequence

/**
 * Every call in a module body, with the form [CallableDeclaration] says it declares - by [CallableDeclaration.formOf]
 * and by [CallableDeclaration.syntacticFormOf] - and the name/arity it puts in scope. The whole table is compared at
 * once so a failure shows every row that disagrees, not the first.
 */
class CallableDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testEveryModuleBodyCallAnswersTheOneQuestion() {
        myFixture.configureByFiles("forms.ex", "eex.ex", "mix_generator.ex")

        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val state = ResolveState.initial()
        val actual = module.macroChildCallSequence().joinToString("\n") { call ->
            val form = CallableDeclaration.formOf(call, state)
            val syntacticForm = CallableDeclaration.syntacticFormOf(call)
            val declarations = form?.let { CallableDeclaration.declarations(call, it, state) }.orEmpty()

            val capabilities = CallableDeclaration.capabilitiesOf(call, state)?.let { " (${render(it)})" }.orEmpty()

            ("${call.text.lineSequence().first()} -> ${form ?: "-"} | ${syntacticForm ?: "-"} " +
                declarations.joinToString(", ") { render(it) }).trimEnd() + capabilities
        }

        assertEquals(
            """
            require EEx -> - | -
            require Mix.Generator -> - | -
            def public_function(a), do: a -> CLAUSE | CLAUSE public_function/1 (evaluates runtime public overridable)
            defp private_function(a, b \\ 1), do: {a, b} -> CLAUSE | CLAUSE private_function/1..2 (evaluates runtime private overridable)
            defmacro public_macro(a), do: a -> CLAUSE | CLAUSE public_macro/1 (quotes compile-time public overridable)
            defmacrop private_macro(a), do: a -> CLAUSE | CLAUSE private_macro/1 (quotes compile-time private overridable)
            defguard is_small(a) when a < 10 -> CLAUSE | CLAUSE is_small/1 (evaluates compile-time guard-usable public overridable)
            defguardp is_large(a) when a > 10 -> CLAUSE | CLAUSE is_large/1 (evaluates compile-time guard-usable private overridable)
            @callback function_callback(integer) :: atom -> CALLBACK | CALLBACK function_callback/1 (evaluates runtime public)
            @macrocallback macro_callback(term) :: Macro.t() -> CALLBACK | CALLBACK macro_callback/1 (quotes compile-time public)
            defdelegate delegated(a, b), to: Target -> DELEGATION | DELEGATION delegated/2 (evaluates runtime public overridable)
            defexception [:message] -> EXCEPTION | EXCEPTION exception/1, message/1 (evaluates runtime public)
            EEx.function_from_string(:def, :from_string, "<%= a %>", [:a]) -> EEX_FUNCTION_FROM | - from_string/1 (evaluates runtime public overridable)
            EEx.function_from_file(:defp, :from_file, "sample.eex") -> EEX_FUNCTION_FROM | - from_file/0 (evaluates runtime private overridable)
            Mix.Generator.embed_template(:log, "Log") -> GENERATOR_EMBED | - log_template/1 (evaluates runtime private overridable)
            Mix.Generator.embed_text(:error, "Error") -> GENERATOR_EMBED | - error_text/0 (evaluates runtime private overridable)
            EEx.function_from_string(:def, :"quoted_from_string", "") -> EEX_FUNCTION_FROM | - quoted_from_string/0 (evaluates runtime public overridable)
            EEx.function_from_string(:def, :"#{:interpolated}_from_string", "") -> EEX_FUNCTION_FROM | - (evaluates runtime public overridable)
            Mix.Generator.embed_text(:"quoted", "Quoted") -> GENERATOR_EMBED | - quoted_text/0 (evaluates runtime private overridable)
            import Enum -> - | -
            alias Target, as: T -> - | -
            use Target -> - | -
            for x <- [1], do: x -> - | -
            if true, do: :ok -> - | -
            quote do: 1 -> - | -
            @moduledoc false -> - | -
            """.trimIndent(),
            actual
        )
    }

    /** Elixir rejects an atom over 255 characters, so a quoted one that long names nothing. */
    fun testAQuotedAtomTooLongForElixirDeclaresNothing() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.copyFileToProject("mix_generator.ex")
        val tooLong = "a".repeat(256)
        myFixture.configureByText(
            "too_long.ex",
            """
            defmodule TooLong do
              require EEx
              require Mix.Generator

              EEx.function_from_string(:def, :"$tooLong", "")
              Mix.Generator.embed_text(:"$tooLong", "x")
            end
            """.trimIndent()
        )

        val state = ResolveState.initial()
        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val actual = module.macroChildCallSequence()
            .filter { it.functionName() == "function_from_string" || it.functionName() == "embed_text" }
            .joinToString("\n") { call ->
                val form = CallableDeclaration.formOf(call, state)
                "${call.functionName()} -> $form ${form?.let { CallableDeclaration.declarations(call, it, state) }}"
            }

        assertEquals("function_from_string -> EEX_FUNCTION_FROM []\nembed_text -> GENERATOR_EMBED []", actual)
    }

    /** `def unquote(:"a\"b")()` defines `a"b`: the atom's name is read as Elixir reads it, escapes and all. */
    fun testAnUnquotedNameIsReadAsElixirReadsTheAtom() {
        myFixture.configureByText(
            "unquoted_name.ex",
            """
            defmodule UnquotedName do
              def unquote(:"a\"b")(), do: 1
            end
            """.trimIndent()
        )

        val state = ResolveState.initial()
        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val actual = module.macroChildCallSequence().joinToString("\n") { call ->
            CallableDeclaration.declarations(call, CallableDeclaration.Form.CLAUSE, state).joinToString { render(it) }
        }

        assertEquals("a\"b/0", actual)
    }

    /** A protocol's bodyless `def` is a public runtime function; an EEx kind that is not a literal says no visibility. */
    fun testAKindIsReadFromWhatTheCallSays() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "kinds.ex",
            """
            defprotocol Sized do
              def size(data)
            end

            defmodule Templates do
              require EEx

              for kind <- [:def, :defp] do
                EEx.function_from_string(kind, :render, "")
              end
            end
            """.trimIndent()
        )

        val state = ResolveState.initial()
        val actual = listOf("def size", "EEx.function_from_string").joinToString("\n") { prefix ->
            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { it.text.startsWith(prefix) }

            "$prefix -> ${CallableDeclaration.capabilitiesOf(call, state)?.let { render(it) }}"
        }

        assertEquals(
            "def size -> evaluates runtime public overridable\nEEx.function_from_string -> evaluates runtime ? overridable",
            actual
        )
    }

    /**
     * `headBindingFormOf ⊆ syntacticFormOf ⊆ formOf`, [CallableDeclaration.definerOf] answers exactly the clauses, and [CallableDeclaration.syntacticFormOf] never answers a form
     * that needed a reference resolved. Every call in the fixtures, not just the module body's, and every violation
     * is collected rather than asserted so one bad row does not hide the rest.
     */
    fun testTheThreeEntryPointsNest() {
        myFixture.configureByFiles("forms.ex", "eex.ex", "mix_generator.ex")

        val state = ResolveState.initial()
        val violations = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).mapNotNull { call ->
            val headBindingForm = CallableDeclaration.headBindingFormOf(call)
            val syntacticForm = CallableDeclaration.syntacticFormOf(call)
            val form = CallableDeclaration.formOf(call, state)
            val source = call.text.lineSequence().first()

            when {
                headBindingForm != null && syntacticForm != headBindingForm ->
                    "$source: headBindingFormOf $headBindingForm, syntacticFormOf ${syntacticForm ?: "-"}"
                syntacticForm != null && form != syntacticForm ->
                    "$source: syntacticFormOf $syntacticForm, formOf ${form ?: "-"}"
                (CallableDeclaration.definerOf(call) != null) != (form == CallableDeclaration.Form.CLAUSE) ->
                    "$source: definerOf ${CallableDeclaration.definerOf(call) ?: "-"}, formOf ${form ?: "-"}"
                syntacticForm?.requiresResolution == true ->
                    "$source: syntacticFormOf answered $syntacticForm, which formOf recognises only by resolving"
                else -> CallableDeclaration.Form.entries
                    .firstOrNull { CallableDeclaration.isForm(call, it, state) != (form == it) }
                    ?.let { "$source: isForm($it) disagrees with formOf ${form ?: "-"}" }
            }
        }

        assertEquals("", violations.joinToString("\n"))
    }

    private fun render(capabilities: CallableDeclaration.Capabilities): String =
        listOfNotNull(
            if (capabilities.quotesArguments) "quotes" else "evaluates",
            if (capabilities.compileTime) "compile-time" else "runtime",
            "guard-usable".takeIf { capabilities.usableInGuards },
            capabilities.visibility?.name?.lowercase() ?: "?",
            "overridable".takeIf { capabilities.overridable }
        ).joinToString(" ")

    private fun render(declaration: CallableDeclaration.Declaration): String {
        val interval = declaration.arityInterval ?: return "${declaration.name}/?"
        val maximum = interval.maximum

        return when (maximum) {
            interval.minimum -> "${declaration.name}/${interval.minimum}"
            null -> "${declaration.name}/${interval.minimum}.."
            else -> "${declaration.name}/${interval.minimum}..$maximum"
        }
    }
}

/** Whether [CallableDeclaration.formOf] must resolve a reference to recognise the form; exhaustive, so a new form must say. */
private val CallableDeclaration.Form.requiresResolution: Boolean
    get() = when (this) {
        CallableDeclaration.Form.CLAUSE,
        CallableDeclaration.Form.DELEGATION,
        CallableDeclaration.Form.CALLBACK,
        CallableDeclaration.Form.EXCEPTION -> false
        CallableDeclaration.Form.EEX_FUNCTION_FROM,
        CallableDeclaration.Form.GENERATOR_EMBED -> true
    }
