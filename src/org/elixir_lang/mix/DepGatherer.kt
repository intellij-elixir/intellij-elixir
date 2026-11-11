package org.elixir_lang.mix

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArity
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.package_manager.DepGatherer
import org.elixir_lang.psi.*
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.foldChildrenWhile
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.impl.stripAccessExpressions
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
            val childCalls = modularChildCalls(element).toTypedArray()

            childCalls.projectKeywordList()?.depsPath()?.let { depsPath = it }

            childCalls
                    .foldDepsDefinersWhile(listOf<Dep>()) { depsDefiner, acc ->
                        AccumulatorContinue(acc + depsDefiner.deps(isDependency), true)
                    }
                    .accumulator
                    .let { depSet.addAll(it) }
        }
    }

    companion object {
        /**
         * The names of the dependencies [mixExs] declares, read from its source alone, as an index must: each element of
         * the list `deps:` calls for is a tuple, whose first atom is the app Mix loads whatever its requirement or options
         * say. [ProjectKeywords.Reading.Unknown] where any part of that can't be read: a `deps:` that isn't a call of a
         * function its module defines, a definer that returns no list, or an element that isn't a tuple whose first element
         * is a literal atom. A `mix.exs` with no `deps:` declares none.
         */
        @RequiresReadLock
        fun declared(mixExs: ElixirFile): ProjectKeywords.Reading<Set<String>> =
            ProjectKeywords.keyword(mixExs, "deps", ProjectKeywords.Reading.Written(emptySet())) { value ->
                names(mixExs, value)?.let { ProjectKeywords.Reading.Written(it) }
            }

        @RequiresReadLock
        private fun names(mixExs: ElixirFile, deps: PsiElement): Set<String>? {
            val nameArity = (deps as? Call)?.takeUnless { it is Qualified }?.nameArity() ?: return null

            return mixExs.modulars()
                .filter { PsiTreeUtil.isAncestor(it, deps, true) }
                .flatMap { modularChildCalls(it) }
                .filter { isDefining(it, nameArity) }
                .ifEmpty { return null }
                .flatMapTo(mutableSetOf()) { definer ->
                    val list = ProjectKeywords.returned(definer)?.stripAccessExpressionAndParentheses() as? ElixirList ?: return null

                    list.children.map { name(it.stripAccessExpression()) ?: return null }
                }
        }

        private fun name(element: PsiElement): String? =
            ((element as? ElixirTuple)?.children?.stripAccessExpressions()?.firstOrNull() as? ElixirAtom)?.let(AtomName::of)
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

@RequiresReadLock
private fun Array<Call>.projectKeywordList(): QuotableKeywordList? {
    for (call in this) {
        ProgressManager.checkCanceled()

        if (ProjectKeywords.isProject(call)) {
            call.lastKeywordList()?.let { return it }
        }
    }

    return null
}

private fun QuotableKeywordList.depsPath(): String? =
    (keywordValue("deps_path")?.stripAccessExpression() as? ElixirLine)?.body?.text

@RequiresReadLock
private fun Array<Call>.depsNameArity(): NameArity? {
    var nameArity: NameArity? = null

    for (call in this) {
        if (ProjectKeywords.isProject(call)) {
            nameArity = call.lastKeywordList()?.depsNameArity()

            if (nameArity != null) {
                break
            }
        }
    }

    return nameArity
}

private fun isDefining(call: Call, nameArity: NameArity): Boolean =
    capabilities(call)?.runtimeFunction == true &&
        nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
            name == nameArity.name && arityInterval.contains(nameArity.arity)
        } == true

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

private fun QuotableKeywordList.depsNameArity(): NameArity? = (keywordValue("deps") as? Call)?.nameArity()

/** The name and arity this call calls. */
private fun Call.nameArity(): NameArity? = functionName()?.let { NameArity(it, resolvedFinalArity()) }

private fun ElixirList.deps(isDependency: Boolean): List<Dep> =
    children.map { it.stripAccessExpression() }.asSequence().flatMap { Deps.from(it, isDependency) }.toList()
