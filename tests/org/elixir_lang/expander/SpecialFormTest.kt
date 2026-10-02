package org.elixir_lang.expander

import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** [specialForm] agrees with every committed leg's `special-forms.txt`, read at that leg's level. */
class SpecialFormTest {
    @Test
    fun `every leg's special forms`() {
        val legs = File(LegManifest.ROOT).listFiles { directory -> File(directory, MANIFEST).isFile }.orEmpty()

        assertEquals(10, legs.size)

        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        for (leg in legs.sortedBy { it.name }) {
            val level = ElixirLanguageLevel.of(leg.name)
            val rows = File(leg, MANIFEST).readLines().filterNot { it.startsWith("#") }.map { line ->
                val (nameArity, special) = line.split(" ")

                Triple(nameArity.substringBeforeLast('/'), nameArity.substringAfterLast('/'), special.toBoolean())
            }
            // `_/_` stands for every name and arity the table doesn't list, such as `alias/3`.
            val unlisted = rows.filter { it.first != "_" }.groupBy { it.first }
                .filterValues { byName -> byName.none { it.second == "_" } }
                .map { (name, byName) -> Triple(name, (byName.maxOf { it.second.toInt() } + 1).toString(), false) }
            val checked = rows.map { if (it.first == "_") Triple("not_a_special_form", "_", it.third) else it }

            for ((name, arityText, special) in checked + unlisted) {
                for (arity in if (arityText == "_") ANY_ARITY else listOf(arityText.toInt())) {
                    expected.add("${leg.name} $name/$arity $special")
                    actual.add("${leg.name} $name/$arity ${specialForm(name, arity, level)}")
                }
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private companion object {
        const val MANIFEST = "special-forms.txt"
        val ANY_ARITY = (0..3).toList()
    }
}
