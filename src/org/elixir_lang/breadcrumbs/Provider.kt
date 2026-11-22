package org.elixir_lang.breadcrumbs

import com.intellij.lang.Language
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.ex_unit.Describe
import org.elixir_lang.psi.ex_unit.Test
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpressions

private val LANGUAGES = arrayOf<Language>(ElixirLanguage)
private const val DEFIMPL = "defimpl"

/**
 * Supplies the structural declarations (module / protocol / implementation / function / macro, plus the ExUnit
 * `describe` / `test` blocks) used by two coupled platform editor features:
 *
 * * **Breadcrumbs** - the path shown along the bottom of the editor for the element at the caret.
 * * **Sticky Lines** - the enclosing declarations pinned to the top of the editor while scrolling (toggled at
 *   Settings | Editor | General | Sticky Lines, on by default). The platform derives sticky lines from the
 *   registered [BreadcrumbsProvider] by walking the PSI ancestry of each line and pinning every element for
 *   which `acceptStickyElement` returns `true`; there is no separate sticky-lines extension point.
 *
 * Sticky lines render the underlying source line verbatim, so [getElementInfo] only affects breadcrumb text.
 *
 * The sticky-lines hook (`acceptStickyElement`) is still `@ApiStatus.Experimental`, so this provider relies on
 * its default behaviour (delegating to [acceptElement]) rather than overriding it - the same hook the bundled
 * Kotlin/Python/YAML providers build on.
 */
internal class Provider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = LANGUAGES

    // Recent Locations opened from the Switcher asks for both on the EDT without a read lock.
    override fun acceptElement(element: PsiElement): Boolean =
        ReadAction.computeBlocking<Boolean, RuntimeException> { element is Call && isStructural(element) }

    override fun getElementInfo(element: PsiElement): String =
        ReadAction.computeBlocking<String, RuntimeException> {
            val call = element as? Call ?: return@computeBlocking element.text.substringBefore('\n')

            when {
                Module.`is`(call) || Protocol.`is`(call) -> Module.name(call)
                Implementation.`is`(call) -> Implementation.name(call) ?: DEFIMPL
                CallDefinitionClause.`is`(call) -> callDefinitionInfo(call)
                Describe.hasNameArity(call) -> exUnitInfo("describe", call)
                Test.hasNameArity(call) -> exUnitInfo("test", call)
                else -> null
            } ?: call.text.substringBefore('\n')
        }
}

/**
 * The declaration kinds that read as "classes and functions" in an Elixir file: the modular containers
 * (`defmodule`, `defprotocol`, `defimpl`), the call definitions nested in them (`def`, `defp`, `defmacro`,
 * `defmacrop`, `defguard`, `defguardp`), and the ExUnit `describe` / `test` blocks that organise test files.
 * These are exactly the elements worth pinning as sticky lines, so breadcrumbs and sticky lines share the same
 * set (the default `acceptStickyElement` delegates to `acceptElement`).
 *
 * `describe` / `test` are matched by name + arity alone (no `use ExUnit.Case` resolution), matching how
 * [org.elixir_lang.exunit.ExUnitLineMarkerProvider] recognises them: the check stays cheap enough for the
 * per-line sticky-lines pass, works even before ExUnit is indexed, and a stray same-arity `describe` / `test`
 * macro pins harmlessly.
 */
@RequiresReadLock
private fun isStructural(call: Call): Boolean =
        Module.`is`(call) ||
                Protocol.`is`(call) ||
                Implementation.`is`(call) ||
                CallDefinitionClause.`is`(call) ||
                Describe.hasNameArity(call) ||
                Test.hasNameArity(call)

@RequiresReadLock
private fun callDefinitionInfo(call: Call): String {
    val nameArityInterval = CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
        ?: return CallDefinitionClause.nameIdentifier(call)?.text ?: call.text.substringBefore('\n')
    val arityInterval = nameArityInterval.arityInterval
    val maximum = arityInterval.maximum
    val arity = if (maximum == null || maximum == arityInterval.minimum) {
        arityInterval.minimum.toString()
    } else {
        "${arityInterval.minimum}..$maximum"
    }

    return "${nameArityInterval.name}/$arity"
}

/**
 * Mirrors the structure-view presentation (`describe "..."` / `test "..."`) by appending the block's first argument
 * (its description string).
 */
@RequiresReadLock
private fun exUnitInfo(macro: String, call: Call): String =
        call.finalArguments()?.stripAccessExpressions()?.firstOrNull()?.text
            ?.let { "$macro $it" }
            ?: macro
