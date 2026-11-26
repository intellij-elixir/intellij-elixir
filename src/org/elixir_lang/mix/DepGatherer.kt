package org.elixir_lang.mix

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Macro
import org.elixir_lang.NameArity
import org.elixir_lang.expander.specialForm
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.package_manager.DepGatherer
import org.elixir_lang.psi.*
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.blockKeyword
import org.elixir_lang.psi.impl.call.body
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.elements
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.operation.When
import org.elixir_lang.structure_view.element.CallDefinitionHead

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
        if (file is ElixirFile) gather(file)
    }

    @RequiresReadLock
    private fun gather(mixExs: ElixirFile) {
        val written = ProjectKeywords.keyword(mixExs, "deps_path", ProjectKeywords.Reading.Absent) { value ->
            ProjectKeywords.literalText(value)?.let { ProjectKeywords.Reading.Written(it) }
        }
        when (written) {
            is ProjectKeywords.Reading.Written -> depsPath = written.value
            ProjectKeywords.Reading.Absent, ProjectKeywords.Reading.Unknown, is ProjectKeywords.Reading.Ambiguous -> Unit
        }

        // Each list `project/0` may return names its own `deps:`, and the watcher fetches what any of them declares.
        ProjectKeywords.keyword(mixExs, "deps", ProjectKeywords.Reading.Absent) { value -> depsLists(mixExs, value)?.let { ProjectKeywords.Reading.Written(it) } }
            .candidates
            .filterIsInstance<ProjectKeywords.Reading.Written<List<ElixirList?>>>()
            .forEach { (lists) ->
                ProgressManager.checkCanceled()
                lists.flatMapTo(depSet) { it?.deps(isDependency).orEmpty() }
            }
    }

    companion object {
        /**
         * The names of the dependencies [mixExs] declares, read from its source alone, as an index must: each element of
         * the list `deps:` calls for is a tuple, whose first atom is the app Mix loads whatever its requirement or options
         * say. [ProjectKeywords.Reading.Unknown] where any part of that can't be read: a `deps:` that isn't a call of a
         * function its module defines, a definer that may return something other than a list, or an element that isn't a
         * tuple whose first element is a literal atom. A `mix.exs` with no `deps:` declares none. A definer declares each
         * list it may return; each list `project/0` may return is a reading of its own.
         */
        @RequiresReadLock
        fun declared(mixExs: ElixirFile): ProjectKeywords.Reading<Set<String>> =
            ProjectKeywords.keyword(mixExs, "deps", ProjectKeywords.Reading.Written(emptySet())) { value ->
                names(mixExs, value)?.let { ProjectKeywords.Reading.Written(it) }
            }

        @RequiresReadLock
        private fun names(mixExs: ElixirFile, value: PsiElement): Set<String>? =
            depsLists(mixExs, value)?.flatMapTo(mutableSetOf()) { list ->
                (list ?: return null).elements().map { element -> (element as? ElixirTuple)?.let(Dep::nameAtom)?.let(AtomName::of) ?: return null }
            }
    }
}

/**
 * The lists a `deps:` [value] may give: each that its [definers] may return, `null` for one that isn't a list as written.
 * `null` where [value] is no call of a function the module of [mixExs] defines.
 */
@RequiresReadLock
private fun depsLists(mixExs: ElixirFile, value: PsiElement): List<ElixirList?>? =
    (value as? Call)?.let { definers(mixExs, it) }?.ifEmpty { null }?.flatMap(ProjectKeywords::returnedLists)

/**
 * The clauses of the function [deps] calls ([calledFunction]), in the module of [mixExs] that holds it, that it may run. A
 * head with no body, as default arguments need beside several clauses, runs none.
 */
@RequiresReadLock
private fun definers(mixExs: ElixirFile, deps: Call): List<Call> {
    val nameArity = calledFunction(deps) ?: return emptyList()

    return reached(
        mixExs.modulars()
            .filter { PsiTreeUtil.isAncestor(it, deps, true) }
            .flatMap { modularChildCalls(it) }
            .onEach { ProgressManager.checkCanceled() }
            .filter { isDefining(it, nameArity) && it.body() != null },
        deps.finalArguments()?.singleOrNull(),
    )
}

/**
 * The [clauses] a call with [argument] may run, in order, as Elixir runs the first whose pattern matches. Where
 * [argument] is a literal, a clause whose pattern is a literal of another value or type is skipped, and one whose
 * pattern surely matches ends the search unless it has a guard. A guarded clause, or one whose pattern isn't read here,
 * may run, so it is kept and the search goes on. Any other argument keeps every clause.
 */
@RequiresReadLock
private fun reached(clauses: List<Call>, argument: PsiElement?): List<Call> {
    val literal = argument?.let(::literal) ?: return clauses
    val reached = mutableListOf<Call>()

    for (clause in clauses) {
        ProgressManager.checkCanceled()
        val head = CallDefinitionClause.head(clause)?.stripAccessExpressionAndParentheses()

        val matched = head?.let { matches(it, literal) }

        if (matched != false) reached += clause
        if (matched == true && head !is When) break
    }

    return reached
}

/** Whether the one pattern of [head] matches [literal]: `null` where that depends on what the pattern is bound to. */
@RequiresReadLock
private fun matches(head: PsiElement, literal: OtpErlangObject): Boolean? {
    val pattern = (CallDefinitionHead.strip(head) as? Call)?.let(CallDefinitionHead::parameters)?.singleOrNull()?.let(::quoted) ?: return null

    return when {
        isLiteral(pattern) -> pattern == literal
        isVariable(pattern, ElixirLanguageLevelResolver.languageLevelFor(head)) -> true
        else -> null
    }
}

/** `_` and any other variable, but not `__MODULE__` or another special form written so, which is replaced by its value. */
private fun isVariable(quoted: OtpErlangObject, level: ElixirLanguageLevel): Boolean =
    Macro.isVariable(quoted) && !specialForm(((quoted as OtpErlangTuple).elementAt(0) as OtpErlangAtom).atomValue(), 0, level)

/** [element] quoted, where it is a literal this compares by value: an atom, an integer or a string. */
@RequiresReadLock
private fun literal(element: PsiElement): OtpErlangObject? = quoted(element)?.takeIf(::isLiteral)

/** A charlist quotes as a list, so it isn't one. */
private fun isLiteral(quoted: OtpErlangObject): Boolean =
    quoted is OtpErlangAtom || quoted is OtpErlangLong || quoted is OtpErlangBinary

@RequiresReadLock
private fun quoted(element: PsiElement): OtpErlangObject? = (element.stripAccessExpressionAndParentheses() as? Quotable)?.quote()

private fun isDefining(call: Call, nameArity: NameArity): Boolean =
    capabilities(call)?.runtimeFunction == true &&
        nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
            name == nameArity.name && arityInterval.contains(nameArity.arity)
        } == true

/**
 * The function of its own module a `deps:` value [call]s: `null` for a remote call, which names another module's, and
 * for a bare name `project/0` binds first, which is a variable, as in Elixir, whose value is never read.
 */
@RequiresReadLock
private fun calledFunction(call: Call): NameArity? =
    call.takeUnless { it is Qualified || it is UnqualifiedNoArgumentsCall<*> && isBoundBefore(it) }?.nameArity()

/**
 * Whether the bare [name], in the `project/0` definition that holds it, is bound before it: by the head of a clause that
 * holds it, as `else deps -> [deps: deps]` binds it, or by a match in the definition's own clause. A pattern binds at any
 * depth (`{deps, _} = ...`, `a = deps = ...`).
 */
@RequiresReadLock
private fun isBoundBefore(name: Call): Boolean {
    val project = name.parents(withSelf = false).filterIsInstance<Call>().firstOrNull(ProjectKeywords::isProject) ?: return false
    val variable = name.functionName()
    val binds = { pattern: PsiElement? ->
        PsiTreeUtil.collectElementsOfType(pattern, UnqualifiedNoArgumentsCall::class.java).any { it.functionName() == variable }
    }
    val enclosing = { element: PsiElement -> element.parents(withSelf = false).takeWhile { it !== project } }

    return enclosing(name).filterIsInstance<ElixirStabOperation>().any { binds(it.leftOperand()) } ||
        PsiTreeUtil.findChildrenOfType(project, Match::class.java).any { match ->
            match.textOffset < name.textOffset && enclosing(match).none { opensScope(project, it) } && binds(match.leftOperand())
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
    elements().flatMap { Deps.from(it, isDependency) }
