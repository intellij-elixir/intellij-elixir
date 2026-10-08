package org.elixir_lang.reference.resolver.atom.resolvable

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirAtom

/**
 * An atom with an interpolation resolves to the modules whose names match the atom Elixir builds: its `\xHH` escapes are
 * bytes of a UTF-8 name, and bytes that are not valid UTF-8 make no atom at all.
 */
class InterpolatedAtomPatternTest : PlatformTestCase() {
    fun testHexadecimalEscapesAreTheBytesOfAUtf8Name() {
        assertEquals(
            setOf("defmodule :\"éfoo\" do\nend"),
            resolve(
                """
                defmodule :"éfoo" do
                end

                defmodule :"Ã©foo" do
                end

                defmodule User do
                  def f(x), do: :"\xC3\xA9#{x}"
                end
                """
            )
        )
    }

    fun testHexadecimalEscapeBetweenInterpolationsIsTheBytesOfAUtf8Name() {
        assertEquals(
            setOf("defmodule :\"aébc\" do\nend"),
            resolve(
                """
                defmodule :"aébc" do
                end

                defmodule :"aÃ©bc" do
                end

                defmodule User do
                  def f(x, y), do: :"a#{x}\xC3\xA9#{y}c"
                end
                """
            )
        )
    }

    fun testBytesThatAreNotUtf8ResolveToNothing() {
        assertEquals(
            emptySet<String>(),
            resolve(
                """
                defmodule :"ÿfoo" do
                end

                defmodule User do
                  def f(x), do: :"\xFF#{x}"
                end
                """
            )
        )
    }

    fun testAnEscapeBelowTheByteRangeIsACodePoint() {
        assertEquals(
            setOf("defmodule :Abc do\nend"),
            resolve(
                """
                defmodule :Abc do
                end

                defmodule :abc do
                end

                defmodule User do
                  def f(x), do: :"\x41#{x}"
                end
                """
            )
        )
    }

    fun testAUnicodeEscapeIsACodePoint() {
        assertEquals(
            setOf("defmodule :\"éfoo\" do\nend"),
            resolve(
                """
                defmodule :"éfoo" do
                end

                defmodule User do
                  def f(x), do: :"\u00e9#{x}"
                end
                """
            )
        )
    }

    fun testAPlainInterpolationStillMatchesEveryName() {
        assertEquals(
            setOf("defmodule :one_target do\nend", "defmodule :two_target do\nend"),
            resolve(
                """
                defmodule :one_target do
                end

                defmodule :two_target do
                end

                defmodule :other do
                end

                defmodule User do
                  def f(x), do: :"#{x}_target"
                end
                """
            )
        )
    }

    private fun resolve(source: String): Set<String> {
        myFixture.configureByText("interpolated.ex", source.trimIndent())
        val atom = PsiTreeUtil
            .findChildrenOfType(myFixture.file, ElixirAtom::class.java)
            .single { it.text.contains("#{") }

        return (atom.reference as PsiPolyVariantReference).multiResolve(false).map { it.element!!.text }.toSet()
    }
}
