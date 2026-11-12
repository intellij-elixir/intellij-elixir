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
import org.elixir_lang.psi.impl.blockKeyword
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.stripAccessExpressions
import org.elixir_lang.psi.operation.Match

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

            val keywordLists = childCalls.projectKeywordLists().toList()

            keywordLists.firstOrNull()?.depsPath()?.let { depsPath = it }

            keywordLists
                .firstNotNullOfOrNull { calledFunction(it.keywordValue("deps") as? Call) }
                ?.let { depsNameArity ->
                    childCalls
                        .asSequence()
                        .onEach { ProgressManager.checkCanceled() }
                        .filter { isDefining(it, depsNameArity) }
                        .flatMapTo(depSet) { it.deps(isDependency) }
                }
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
            ProjectKeywords.keyword(
                mixExs,
                "deps",
                ProjectKeywords.Reading.Written(emptySet()),
                ProjectKeywords.NeedsWhole.EVERY_ANSWER,
            ) { value ->
                names(mixExs, value)?.let { ProjectKeywords.Reading.Written(it) }
            }

        @RequiresReadLock
        private fun names(mixExs: ElixirFile, deps: PsiElement): Set<String>? {
            val nameArity = calledFunction((deps as? Call)?.takeUnless { it is Qualified }) ?: return null

            return mixExs.modulars()
                .filter { PsiTreeUtil.isAncestor(it, deps, true) }
                .flatMap { modularChildCalls(it) }
                .filter { isDefining(it, nameArity) }
                .ifEmpty { return null }
                .flatMapTo(mutableSetOf()) { definer ->
                    val list = ProjectKeywords.returnedList(definer)?.takeIf { ProjectKeywords.returnsOnlyItsBody(definer) } ?: return null

                    list.children.map { name(it.stripAccessExpression()) ?: return null }
                }
        }

        private fun name(element: PsiElement): String? =
            ((element as? ElixirTuple)?.children?.stripAccessExpressions()?.firstOrNull() as? ElixirAtom)?.let(AtomName::of)
    }
}

private fun Call.deps(isDependency: Boolean): List<Dep> = ProjectKeywords.returnedList(this)?.deps(isDependency) ?: emptyList()

/** The keywords of each list a `project/0` definition returns, in order. */
@RequiresReadLock
private fun Array<Call>.projectKeywordLists(): Sequence<QuotableKeywordList> =
    asSequence()
        .onEach { ProgressManager.checkCanceled() }
        .filter(ProjectKeywords::isProject)
        .mapNotNull { ProjectKeywords.returnedList(it)?.keywords }

private fun QuotableKeywordList.depsPath(): String? =
    (keywordValue("deps_path")?.stripAccessExpression() as? ElixirLine)?.body?.text

private fun isDefining(call: Call, nameArity: NameArity): Boolean =
    capabilities(call)?.runtimeFunction == true &&
        nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
            name == nameArity.name && arityInterval.contains(nameArity.arity)
        } == true

/**
 * The function a `deps:` value [call]s, `null` for a bare name `project/0` binds first: that is a variable, as in Elixir,
 * and its value is never read.
 */
@RequiresReadLock
private fun calledFunction(call: Call?): NameArity? =
    call?.takeUnless { it is UnqualifiedNoArgumentsCall<*> && isBoundBefore(it) }?.nameArity()

/**
 * Whether a match before the bare [name], in the `project/0` definition that holds it, binds that name in the
 * definition's own clause. A pattern binds at any depth (`{deps, _} = ...`, `a = deps = ...`).
 */
@RequiresReadLock
private fun isBoundBefore(name: Call): Boolean {
    val project = generateSequence(name.parent) { it.parent }.filterIsInstance<Call>().firstOrNull(ProjectKeywords::isProject)
        ?: return false
    val variable = name.functionName()

    return PsiTreeUtil.findChildrenOfType(project, Match::class.java).any { match ->
        match.textOffset < name.textOffset &&
            generateSequence(match.parent) { it.parent }.takeWhile { it !== project }.none { opensScope(project, it) } &&
            PsiTreeUtil.collectElementsOfType(match.leftOperand(), UnqualifiedNoArgumentsCall::class.java)
                .any { it.functionName() == variable }
    }
}

/**
 * Whether variables bound under [element] stay there: an `fn`, a `do`, `else`, `after`, `rescue` or `catch` body other than
 * [project]'s own, written as a block or as a keyword, or a `with` or `for` clause. A match in the arguments of a call that
 * opens a body, as in `case deps = x do`, binds outside it.
 */
private fun opensScope(project: Call, element: PsiElement): Boolean =
    when (element) {
        is ElixirAnonymousFunction -> true
        is ElixirDoBlock -> PsiTreeUtil.getParentOfType(element, Call::class.java) !== project
        is QuotableKeywordPair ->
            element.blockKeyword() != null &&
                PsiTreeUtil.getParentOfType(element, Call::class.java) !== project
        is Call -> element !is Qualified && element.functionName() in CLAUSE_SCOPES
        else -> false
    }

private val CLAUSE_SCOPES = setOf("with", "for")

/** The name and arity this call calls. */
private fun Call.nameArity(): NameArity? = functionName()?.let { NameArity(it, resolvedFinalArity()) }

private fun ElixirList.deps(isDependency: Boolean): List<Dep> =
    children.map { it.stripAccessExpression() }.asSequence().flatMap { Deps.from(it, isDependency) }.toList()
