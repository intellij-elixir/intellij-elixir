package org.elixir_lang.model.psi.variable

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.UnqualifiedBracketOperation
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.operation.Match
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.scope.variable.MultiResolve
import org.elixir_lang.psi.scope.NameMatch

@Suppress("UnstableApiUsage")
class VariableReference(
    private val element: PsiElement,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = element

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> = resolveSymbols(element)

    companion object {
        @RequiresReadLock
        fun resolveSymbols(element: PsiElement): Collection<VariableSymbol> {
            val name = VariableSymbol.variableName(element) ?: return emptyList()
            val entrances = sequenceOf(
                (element as? Call) ?: (element as? UnqualifiedBracketOperation) ?: (element.parent as? Call),
                generateSequence(element) { it.parent }.filterIsInstance<Match>().firstOrNull()
            ).filterNotNull().distinct()

            for (entrance in entrances) {
                ProgressManager.checkCanceled()
                val symbols = MultiResolve.resolveResultList(name, false, entrance)
                    .filter { it.isValidResult }
                    .map { it.element }
                    .mapNotNull { resolved -> declarationSymbol(resolved) }
                    .distinct()
                if (symbols.isNotEmpty()) return symbols
            }

            val parameterFallback = functionHeadParameterSymbols(element, name)
            if (parameterFallback.isNotEmpty()) return parameterFallback

            val matchFallback = pinMatchRightSideSymbols(element, name)
            if (matchFallback.isNotEmpty()) return matchFallback

            return emptyList()
        }

        @RequiresReadLock
        private fun declarationSymbol(resolved: PsiElement): VariableSymbol? =
            VariableSymbol.fromDeclaration(resolved)
                ?: generateSequence(resolved) { it.parent }
                    .filterIsInstance<Call>()
                    .mapNotNull { call -> VariableSymbol.fromDeclaration(call) }
                    .firstOrNull()

        @RequiresReadLock
        private fun functionHeadParameterSymbols(element: PsiElement, name: String): List<VariableSymbol> {
            val definitionClause = generateSequence(element) { it.parent }
                .filterIsInstance<Call>()
                .firstOrNull { call -> CallDefinitionClause.`is`(call) }
                ?: return emptyList()
            val head = CallDefinitionClause.head(definitionClause) ?: return emptyList()

            return PsiTreeUtil.findChildrenOfType(head, Call::class.java)
                .asSequence()
                .filter { call -> VariableSymbol.variableName(call)?.let { NameMatch.same(it, call, name, element) } == true }
                .mapNotNull { call -> VariableSymbol.fromDeclaration(call) }
                .distinct()
                .toList()
        }

        @RequiresReadLock
        private fun pinMatchRightSideSymbols(element: PsiElement, name: String): List<VariableSymbol> {
            val match = generateSequence(element) { it.parent }
                .filterIsInstance<Match>()
                .firstOrNull()
                ?: return emptyList()
            val fromRightOperand = match.rightOperand()?.let { right ->
                PsiTreeUtil.isAncestor(right, element, false)
            } == true
            if (!fromRightOperand) return emptyList()

            val leftOperandText = match.leftOperand()?.text ?: return emptyList()
            if (!leftOperandText.contains("^$name")) return emptyList()

            val pinnedLeftVariable = sequenceOf(match.leftOperand())
                .filterNotNull()
                .flatMap { left ->
                    PsiTreeUtil.findChildrenOfType(left, Call::class.java).asSequence()
                }
                .firstOrNull { leftCall ->
                    VariableSymbol.variableName(leftCall)?.let { NameMatch.same(it, leftCall, name, element) } == true &&
                        generateSequence(leftCall as PsiElement) { it.parent }
                            .filterIsInstance<org.elixir_lang.psi.UnaryOperation>()
                            .any { unary ->
                                unary.operator().text == "^" &&
                                    unary.operand()?.let { operand -> PsiTreeUtil.isAncestor(operand, leftCall, false) } == true
                            }
                }
                ?: return emptyList()

            return MultiResolve.resolveResultList(name, false, pinnedLeftVariable)
                .filter { it.isValidResult }
                .map { it.element }
                .mapNotNull(VariableSymbol::fromDeclaration)
                .distinct()
        }
    }
}
