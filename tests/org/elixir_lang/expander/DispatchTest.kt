package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Meta
import org.junit.Assert.assertEquals
import org.junit.Test

/** [findImportByNameArity] and [findImports] over a fixed env. */
class DispatchTest {
    private val env = Env.empty(LEVEL, KernelImports(emptyList(), emptyList())).copy(
        functions = listOf(imports("Elixir.List", "first/1 last/1"), imports("lists", "last/1 reverse/1")),
        macros = listOf(imports("Elixir.Integer", "is_odd/1")),
    )

    @Test
    fun `a function`() =
        assertEquals(
            ImportMatch.Function("Elixir.List"),
            findImportByNameArity(NO_META, "first", 1, emptyList(), env, LEVEL),
        )

    @Test
    fun `a macro`() =
        assertEquals(
            ImportMatch.Macro("Elixir.Integer"),
            findImportByNameArity(NO_META, "is_odd", 1, emptyList(), env, LEVEL),
        )

    @Test
    fun `two imports of one name and arity`() =
        assertEquals(
            ImportMatch.Ambiguous(listOf("Elixir.List", "lists")),
            findImportByNameArity(NO_META, "last", 1, emptyList(), env, LEVEL),
        )

    @Test
    fun `a function and a macro`() =
        assertEquals(
            ImportMatch.Ambiguous(listOf("Elixir.List", "Elixir.Kernel")),
            findImportByNameArity(NO_META, "first", 1, listOf(imports("Elixir.Kernel", "first/1")), env, LEVEL),
        )

    @Test
    fun `the extra macros come before the env's`() =
        assertEquals(
            ImportMatch.Ambiguous(listOf("Elixir.Kernel", "Elixir.Integer")),
            findImportByNameArity(NO_META, "is_odd", 1, listOf(imports("Elixir.Kernel", "is_odd/1")), env, LEVEL),
        )

    @Test
    fun `another arity`() =
        assertEquals(
            ImportMatch.None,
            findImportByNameArity(NO_META, "first", 2, emptyList(), env, LEVEL),
        )

    @Test
    fun `no import`() =
        assertEquals(
            ImportMatch.None,
            findImportByNameArity(NO_META, "nope", 0, emptyList(), env, LEVEL),
        )

    @Test
    fun `the arities a name is imported at`() =
        assertEquals(NameImports.Found(listOf(1 to "Elixir.List")), findImports("first", env))

    @Test
    fun `the arities of a function and a macro of one name, by arity`() =
        assertEquals(
            NameImports.Found(listOf(1 to "Elixir.N", 2 to "Elixir.M", 3 to "Elixir.M")),
            findImports(
                "f",
                env.copy(functions = listOf(imports("Elixir.M", "f/2 f/3")), macros = listOf(imports("Elixir.N", "f/1"))),
            ),
        )

    @Test
    fun `a name two functions import at one arity`() =
        assertEquals(NameImports.Ambiguous(1, listOf("lists", "Elixir.List")), findImports("last", env))

    @Test
    fun `a name a function and a macro import at one arity`() =
        assertEquals(
            NameImports.Ambiguous(1, listOf("Elixir.N", "Elixir.M")),
            findImports(
                "f",
                env.copy(functions = listOf(imports("Elixir.M", "f/1")), macros = listOf(imports("Elixir.N", "f/1"))),
            ),
        )

    @Test
    fun `a name no import brings in`() = assertEquals(NameImports.Found(emptyList()), findImports("nope", env))

    private fun imports(module: String, nameArities: String) =
        Env.Imports(
            module,
            nameArities.split(" ").map { NameArity(it.substringBefore('/'), it.substringAfter('/').toInt()) },
        )

    private companion object {
        val LEVEL = ElixirLanguageLevel.of("1.20.4")
        val NO_META = Meta(TextRange.EMPTY_RANGE, Meta.Position(1, 1), Meta.Position(1, 1))
    }
}
