package org.elixir_lang.mix

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import org.elixir_lang.NameArity
import org.elixir_lang.package_manager.DepGatherer
import org.elixir_lang.psi.*
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.foldChildrenWhile
import org.elixir_lang.psi.impl.call.macroChildCalls
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.util.AccumulatorContinue

/**
 * @param isDependency whether the `mix.exs` being visited belongs to a dependency; see [Dep.from].
 */
class DepGatherer(private val isDependency: Boolean = false) : DepGatherer() {
    /**
     * `Mix.Project.deps_path/1` reads `:deps_path` and expands it, and `Mix.Dep.in_dependency`
     * pushes the result down to every dependency already absolute, so there is one `deps` directory
     * per project tree and the top-level project owns it. `mix new --umbrella` therefore writes
     * `deps_path: "../../deps"` into each app. Taken verbatim here; the caller resolves it.
     */
    override var depsPath: String? = null
        private set

    override fun visitFile(file: PsiFile) {
        // Caller (Resolution.packagePsiFileToDepSet) already holds a read lock via runReadAction { ... }.
        // A nested runReadAction here is redundant. Protect from inadvertent calls with
        // assertion.
        assert(ApplicationManager.getApplication().isReadAccessAllowed) {
            "DepGatherer.visitFile must be called under a read lock"
        }
        if (file is ElixirFile) {
            file.acceptChildren(this)
        }
    }

    override fun visitElement(element: PsiElement) {
        if (element is Call && Module.`is`(element)) {
            val childCalls = element.macroChildCalls()

            childCalls.projectKeywordList()?.depsPath()?.let { depsPath = it }

            childCalls
                    .foldDepsDefinersWhile(listOf<Dep>()) { depsDefiner, acc ->
                        AccumulatorContinue(acc + depsDefiner.deps(isDependency), true)
                    }
                    .accumulator
                    .let { depSet.addAll(it) }
        }
    }
}

private fun Call.deps(isDependency: Boolean): List<Dep> = lastList()?.deps(isDependency) ?: emptyList()

private fun Call.lastList(): ElixirList? =
    foldChildrenWhile(null as ElixirList?) { child, acc ->
        if (child is ElixirList) {
            AccumulatorContinue(child, true)
        } else {
            AccumulatorContinue(acc, true)
        }
    }.accumulator

private fun <R> Array<Call>.foldDepsDefinersWhile(
        initial: R,
        operation: (Call, acc: R) -> AccumulatorContinue<R>
): AccumulatorContinue<R> {
    var final = AccumulatorContinue(initial, true)

    depsNameArity()?.let { depsNameArity ->
        for (childCall in this) {
            ProgressManager.checkCanceled()

            if (isDefining(childCall, depsNameArity)) {
                final = operation(childCall, final.accumulator)

                if (!final.`continue`) {
                    break
                }
            }
        }
    }

    return final
}

private fun Array<Call>.projectKeywordList(): QuotableKeywordList? {
    for (call in this) {
        ProgressManager.checkCanceled()

        if (isDefiningProject(call)) {
            call.lastKeywordList()?.let { return it }
        }
    }

    return null
}

private fun QuotableKeywordList.depsPath(): String? =
    (keywordValue("deps_path")?.stripAccessExpression() as? ElixirLine)?.body?.text

private fun Array<Call>.depsNameArity(): NameArity? {
    var nameArity: NameArity? = null

    for (call in this) {
        if (isDefiningProject(call)) {
            nameArity = call.lastKeywordList()?.depsNameArity()

            if (nameArity != null) {
                break
            }
        }
    }

    return nameArity
}

private fun isDefining(call: Call, nameArity: NameArity): Boolean =
        if (CallableDeclaration.definerOf(call)?.capabilities?.runtimeFunction == true) {
            nameArityInterval(call, ResolveState.initial())?.let { definedNameArityInterval ->
                if (definedNameArityInterval.name == nameArity.name &&
                        definedNameArityInterval.arityInterval.contains(nameArity.arity)) {
                    true
                } else {
                    null
                }
            }
        } else {
            null
        } ?: false

private fun isDefiningProject(call: Call): Boolean =
        if (CallableDeclaration.definerOf(call)?.capabilities?.remoteCallable == true) {
            nameArityInterval(call, ResolveState.initial())?.let { nameArityRange ->
                if (nameArityRange.name == "project" && nameArityRange.arityInterval.contains(0)) {
                    true
                } else {
                    null
                }
            }
        } else {
            null
        } ?: false

private fun Call.lastKeywordList(): QuotableKeywordList? =
        foldChildrenWhile(null as QuotableKeywordList?) { projectChild, acc ->
                    if (projectChild is ElixirList) {
                        val lastKeywordList = projectChild.children.last { it is QuotableKeywordList } as QuotableKeywordList?
                        AccumulatorContinue(lastKeywordList, true)
                    } else {
                        AccumulatorContinue(acc, true)
                    }
                }
                .accumulator

private fun QuotableKeywordList.depsNameArity(): NameArity? =
        keywordValue("deps")
                ?.let { it as? Call }
                ?.let { depsCall ->
                    depsCall
                            .functionName()
                            ?.let { name ->
                                NameArity(name, depsCall.resolvedFinalArity())
                            }
                }

private fun ElixirList.deps(isDependency: Boolean): List<Dep> =
    children.map { it.stripAccessExpression() }.asSequence().flatMap { Deps.from(it, isDependency) }.toList()
