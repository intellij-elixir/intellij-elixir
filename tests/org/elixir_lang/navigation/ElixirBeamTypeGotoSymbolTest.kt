package org.elixir_lang.navigation

import com.intellij.navigation.NavigationItem
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.TypeDefinition
import java.io.File

/** Go To Symbol over the types of an Elixir-compiled `.beam`, which are read from its debug info. */
class ElixirBeamTypeGotoSymbolTest : BeamLibraryTestCase() {
    override val ebinDirectory: File
        get() = File("testData/org/elixir_lang/model/psi/module/ebin").absoluteFile

    private fun itemsNamed(name: String): Array<NavigationItem> = gotoSymbolItemsNamed(myFixture.project, name)

    fun testGoToSymbolOffersAnElixirModulesTypeDefinition() {
        myFixture.configureByText("caller.ex", "defmodule Caller do\nend\n")

        val items = itemsNamed("binding")

        assertTrue(
            "Go To Symbol should offer `Code`'s `@type binding`, got: ${items.map { "${it.name} ${it.javaClass.simpleName}" }}",
            items.any { it is TypeDefinition },
        )
    }
}
