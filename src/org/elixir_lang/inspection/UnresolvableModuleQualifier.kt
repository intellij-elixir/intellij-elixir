package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.reference.module.UnaliasedName
import org.elixir_lang.reference.resolver.Module as ModuleResolver

class UnresolvableModuleQualifier : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession
    ): PsiElementVisitor {
        // Only inspect files under Source or Test Source roots.  Files in unmarked
        // directories (e.g. config/, priv/) are not indexed as source and their module
        // resolution scope is narrower -- flagging errors there produces false positives.
        val virtualFile = holder.file.virtualFile
        if (virtualFile == null ||
            !ProjectRootManager.getInstance(holder.project).fileIndex.isInSourceContent(virtualFile)
        ) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        // Skip injected language fragments (e.g. Elixir code blocks inside @doc/@moduledoc
        // heredocs).  Module references in documentation examples are illustrative and
        // not expected to resolve.
        if (InjectedLanguageManager.getInstance(holder.project).isInjectedFragment(holder.file)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return object : ElixirVisitor() {
            // Cached per-file: whether opaque `use` calls exist.  Computed once on first
            // unresolved qualifier and reused for all subsequent qualifiers in the same file.
            private var opaqueUseCallsResult: Boolean? = null

            // Cached per-file: all alias calls whose target ends with ".Router.Helpers",
            // keyed by the short name they introduce (e.g. "Helpers", "RouterHelpers").
            // null means not yet computed.
            private var routerHelpersAliasesByName: Map<String, List<Call>>? = null

            override fun visitMatchedQualifiedNoArgumentsCall(o: ElixirMatchedQualifiedNoArgumentsCall) =
                checkQualifier(o)

            override fun visitUnmatchedQualifiedNoArgumentsCall(o: ElixirUnmatchedQualifiedNoArgumentsCall) =
                checkQualifier(o)

            override fun visitMatchedQualifiedNoParenthesesCall(o: ElixirMatchedQualifiedNoParenthesesCall) =
                checkQualifier(o)

            override fun visitUnmatchedQualifiedNoParenthesesCall(o: ElixirUnmatchedQualifiedNoParenthesesCall) =
                checkQualifier(o)

            override fun visitMatchedQualifiedParenthesesCall(o: ElixirMatchedQualifiedParenthesesCall) =
                checkQualifier(o)

            override fun visitUnmatchedQualifiedParenthesesCall(o: ElixirUnmatchedQualifiedParenthesesCall) =
                checkQualifier(o)

            private fun checkQualifier(qualified: Qualified) {
                if (Unquote.isQualified(qualified)) {
                    return
                }

                val qualifier = qualified.qualifier()

                // Skip dynamic/runtime qualifiers that cannot be resolved as module names.
                when (qualifier) {
                    is AtOperation,
                    is ElixirCaptureNumericOperation,
                    is ElixirParentheticalStab,
                    is ElixirStructOperation,
                    is UnqualifiedNoArgumentsCall<*>,
                    is UnqualifiedParenthesesCall<*>,
                    is QualifiedNoArgumentsCall<*>,
                    is QualifiedBracketOperation,
                    is QualifiedParenthesesCall<*> -> return
                }

                if (qualifier !is QualifiableAlias) {
                    return
                }

                if (qualified.qualifiedToModulars().isEmpty()) {
                    if (isResolvablePhoenixRouterHelpers(qualifier)) {
                        return
                    }

                    if (hasOpaqueUseCalls(qualifier)) {
                        return
                    }

                    val qualifierName = qualifier.text

                    holder.registerProblem(
                        qualifier,
                        "Module '$qualifierName' is not defined or aliased in this scope",
                        ProblemHighlightType.ERROR
                    )
                }
            }

            private fun isResolvablePhoenixRouterHelpers(qualifier: QualifiableAlias): Boolean {
                val unaliasedName =
                    UnaliasedName.unaliasedName(qualifier) ?: qualifier.fullyQualifiedName()

                // Phoenix generates `*.Router.Helpers` modules at compile time, but current module
                // indexing/resolution does not always surface those generated helper modules directly.
                // This inspection-level suppression avoids false positives until resolver/indexing
                // can provide first-class synthetic/helper resolution from `*.Router` definitions.
                // TODO: Replace with first-class synthetic module resolution from *.Router definitions.
                if (unaliasedName.endsWith(".Router.Helpers")) {
                    val routerModuleName = unaliasedName.removeSuffix(".Helpers")

                    return ModuleResolver.resolve(qualifier, routerModuleName, false)
                        .any { it.isValidResult }
                }

                val qualifierShortName = qualifier.name ?: return false

                // Use cached alias map (computed once per file) to find alias calls whose
                // target ends with ".Router.Helpers" and whose introduced name matches.
                val aliasMap = routerHelpersAliasesByName ?: buildRouterHelpersAliasMap(qualifier).also {
                    routerHelpersAliasesByName = it
                }

                val matchingAliases = aliasMap[qualifierShortName] ?: return false

                return matchingAliases.any { aliasCall ->
                    val targetName = aliasTargetName(aliasCall) ?: return@any false
                    val routerModuleName = targetName.removeSuffix(".Helpers")
                    ModuleResolver.resolve(qualifier, routerModuleName, false)
                        .any { it.isValidResult }
                }
            }

            /**
             * Scans the file once and builds a map from introduced alias name to alias
             * calls whose target ends with ".Router.Helpers".
             */
            private fun buildRouterHelpersAliasMap(qualifier: QualifiableAlias): Map<String, List<Call>> =
                PsiTreeUtil.collectElementsOfType(qualifier.containingFile, Call::class.java)
                    .filter { Alias.`is`(it) }
                    .mapNotNull { aliasCall ->
                        val targetName = aliasTargetName(aliasCall) ?: return@mapNotNull null
                        if (!targetName.endsWith(".Router.Helpers")) return@mapNotNull null
                        val aliasedName = aliasNameIntroducedBy(aliasCall) ?: return@mapNotNull null
                        aliasedName to aliasCall
                    }
                    .groupBy({ it.first }, { it.second })

            /**
             * Checks whether the file contains `use` calls whose target module has no
             * discoverable `__using__/1` macro.  These opaque `use` calls can inject
             * arbitrary aliases, imports, and requires via compile-time macro expansion
             * (e.g. `ExUnit.CaseTemplate`'s `using do...end` block) that the static PSI
             * walker cannot trace.  When such calls exist we cannot be certain that an
             * unresolved qualifier is genuinely missing -- it may have been injected by the
             * opaque macro -- so the inspection suppresses the error.
             *
             * The result is cached per visitor (i.e. per file) so that repeated unresolved
             * qualifiers in the same file do not re-scan all `use` calls.
             */
            private fun hasOpaqueUseCalls(qualifier: QualifiableAlias): Boolean {
                opaqueUseCallsResult?.let { return it }

                val result = PsiTreeUtil.collectElementsOfType(qualifier.containingFile, Call::class.java)
                    .filter { Use.`is`(it) }
                    .any { useCall ->
                        val modulars = Use.modulars(useCall)

                        // If the use target can't even be resolved, we have no visibility
                        // into what it injects -- treat as opaque.
                        if (modulars.isEmpty()) return@any true

                        // If the used module has no __using__/1 defmacro that the walker
                        // can trace, the injected aliases are invisible to static analysis.
                        modulars.any { modular -> Using.definers(modular).none() }
                    }

                opaqueUseCallsResult = result
                return result
            }

            /**
             * Returns the fully-qualified module name from the first argument of an `alias` call,
             * e.g. `"IcWeb.Router.Helpers"` for `alias IcWeb.Router.Helpers`.
             */
            private fun aliasTargetName(aliasCall: Call): String? {
                val finalArgs = aliasCall.finalArguments() ?: return null
                if (finalArgs.isEmpty()) return null

                val firstArg = finalArgs[0].stripAccessExpression()
                return when (firstArg) {
                    is QualifiableAlias -> firstArg.fullyQualifiedName()
                    else -> moduleName(firstArg)?.name
                }
            }

            /**
             * Returns the short name introduced by an alias call -- either the explicit
             * `as:` name or the last segment of the target module.
             */
            private fun aliasNameIntroducedBy(aliasCall: Call): String? {
                val asValue = aliasCall.keywordArgument("as")?.stripAccessExpression()
                if (asValue != null) {
                    return moduleName(asValue)?.name
                }

                return aliasTargetName(aliasCall)
                    ?.split(".")
                    ?.lastOrNull()
            }
        }
    }
}
