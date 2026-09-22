package org.elixir_lang.psi.impl

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * `ModuleWalker.isChild` (so `Schema.isChild`) is name/arity/scope-based, not shape-based -
 * `NameArityRangeWalker.hasArity` only checks `resolvedFinalArity() in range` - so it can be `true` for a
 * `schema/2` call whose second argument is not a literal `do:`/do-block, even though `hasDoBlockOrKeyword`
 * is `false` for that same call. `ProcessDeclarationsImpl.processDeclarations(call: Call, ...)` has no
 * dedicated arm for that case (a code-review finding on #4123/#4161), so it must fall through correctly:
 * a variable bound inside such a call's own arguments (the one thing worth finding from an entrance sitting
 * there) still has to resolve via the `processor is Variable` arm a few lines down. Confirmed by mutation:
 * restoring the dropped arm produces the identical `multiResolve` result.
 */
class SchemaWithoutDoBlockTest : PlatformTestCase() {
    fun testVariableInsideAnArityTwoSchemaCallWithoutADoBlockStillResolves() {
        myFixture.configureByText(
            "user.ex",
            """
            defmodule User do
              use Ecto.Schema

              schema("users", x = :ok)

              def go, do: x
            end
            """.trimIndent()
        )

        val entrance = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.text == "x" && it.parent.text != "x = :ok" }

        val reference = entrance.reference
        assertNotNull("`x` should carry a reference to resolve", reference)
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)

        val validResults = (reference as PsiPolyVariantReference).multiResolve(false).filter { it.isValidResult }

        assertTrue(
            "`x` bound in `schema(\"users\", x = :ok)`'s own arguments must still resolve even though " +
                "`schema/2` has no literal do-block here - resolved: ${validResults.map { it.element?.text }}",
            validResults.any { it.element?.text == "x" }
        )
    }
}
