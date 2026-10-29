package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Visible
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.scope.CallDefinitionClause
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Callback

/** Records a [Visible] for each callable the walk reaches, keyed as completion offers it. */
class Variants internal constructor() : CallDefinitionClause() {
    private val visibleByPsiElementName: MutableMap<Pair<PsiElement, String>, Visible> = mutableMapOf()

    /**
     * Called on every [Call] where [org.elixir_lang.structure_view.element.CallDefinitionClause. is] is
     * `true` when checking tree with [.execute]
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    override fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean {
        val entranceCallDefinitionClause = state.get(ENTRANCE_CALL_DEFINITION_CLAUSE)

        if ((entranceCallDefinitionClause == null || !element.isEquivalentTo(entranceCallDefinitionClause)) && element is Named) {
            // A clause declares one name, which an `unquote` head spells otherwise than its lookup.
            element.name?.let { name -> recordVisible(Form.CLAUSE, element, state) { element to name } }
        }

        return true
    }

    /**
     * Records each declaration [form] gives [declaring] under the key [key] gives for its spelling: the element and the
     * name completion offers it by.
     */
    private fun recordVisible(form: Form, declaring: Call, state: ResolveState, key: (String) -> Pair<PsiElement, String>) {
        for ((text, declaration) in Declarations.of(form, declaring, state)) {
            visibleByPsiElementName.computeIfAbsent(key(text)) { (_, lookupName) ->
                Visible(lookupName, declaration?.name, declaration, declaring, Import.importedArities(declaring, state))
            }
        }
    }

    override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean {
        // BEAM-decompiled call definitions are never the entrance clause (which is always source),
        // so the entrance guard from executeOnCallDefinitionClause does not apply here.
        if (element.isExported()) {
            // MaybeExported documents exportedName() as null only when isExported() is false.
            element.exportedName()?.let { name ->
                visibleByPsiElementName.computeIfAbsent(element to name) {
                    val declaration = element.declaration()

                    Visible(name, declaration.name, declaration, element, null)
                }
            }
        }

        return true
    }

    override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        Callback.headCall(element)
                ?.let { it as? Named }
                ?.let { head -> recordVisible(Form.CALLBACK, element, state) { head to it } }

        return true
    }

    override fun executeOnDelegation(element: Call, state: ResolveState): Boolean {
        element.finalArguments()?.takeIf { it.size == 2 }?.let { arguments ->
            val head = arguments[0]

            if (CallDefinitionHead.nameArityInterval(head, state) != null) {
                recordVisible(Form.DELEGATION, element, state) { head to it }
            }
        }

        return true
    }

    override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean {
        recordVisible(Form.EEX_FUNCTION_FROM, element, state) { element to it }

        return true
    }

    override fun executeOnException(element: Call, state: ResolveState): Boolean {
        recordVisible(Form.EXCEPTION, element, state) { element to it }

        return true
    }

    override fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean {
        recordVisible(Form.GENERATOR_EMBED, element, state) { element to it }

        return true
    }

    /**
     * Whether to continue searching after each Module's children have been searched.
     *
     * @return `true` to keep searching up the PSI tree; `false` to stop searching.
     */
    override fun keepProcessing(): Boolean = true

    /** What was recorded, in the order it was first found. */
    internal fun visible(): List<Visible> = visibleByPsiElementName.values.toList()

    internal companion object {
        val ENTRANCE_CALL_DEFINITION_CLAUSE = Key<Call>("ENTRANCE_CALL_DEFINITION_CLAUSE")
    }
}
