package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** [findImportByNameArity] over a fixed env. */
class DispatchTest {
    private val env = Env.empty(ElixirLanguageLevel.of("1.20.4"), KernelImports(emptyList(), emptyList())).copy(
        functions = listOf(imports("Elixir.List", "first/1 last/1"), imports("lists", "last/1 reverse/1")),
        macros = listOf(imports("Elixir.Integer", "is_odd/1")),
    )

    @Test
    fun `a function`() =
        assertEquals(ImportMatch.Function("Elixir.List"), findImportByNameArity("first", 1, emptyList(), env))

    @Test
    fun `a macro`() =
        assertEquals(ImportMatch.Macro("Elixir.Integer"), findImportByNameArity("is_odd", 1, emptyList(), env))

    @Test
    fun `two imports of one name and arity`() =
        assertEquals(
            ImportMatch.Ambiguous(listOf("Elixir.List", "lists")),
            findImportByNameArity("last", 1, emptyList(), env),
        )

    @Test
    fun `a function and a macro`() =
        assertEquals(
            ImportMatch.Ambiguous(listOf("Elixir.List", "Elixir.Kernel")),
            findImportByNameArity("first", 1, listOf(imports("Elixir.Kernel", "first/1")), env),
        )

    @Test
    fun `the extra macros come before the env's`() =
        assertEquals(
            ImportMatch.Ambiguous(listOf("Elixir.Kernel", "Elixir.Integer")),
            findImportByNameArity("is_odd", 1, listOf(imports("Elixir.Kernel", "is_odd/1")), env),
        )

    @Test
    fun `another arity`() = assertEquals(ImportMatch.None, findImportByNameArity("first", 2, emptyList(), env))

    @Test
    fun `no import`() = assertEquals(ImportMatch.None, findImportByNameArity("nope", 0, emptyList(), env))

    private fun imports(module: String, nameArities: String) =
        Env.Imports(
            module,
            nameArities.split(" ").map { NameArity(it.substringBefore('/'), it.substringAfter('/').toInt()) },
        )
}
