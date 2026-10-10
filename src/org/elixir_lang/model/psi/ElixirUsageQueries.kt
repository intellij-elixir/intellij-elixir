package org.elixir_lang.model.psi

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.model.Pointer
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.model.search.SearchContext
import com.intellij.model.search.SearchService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.usages.impl.rules.UsageType
import com.intellij.util.AbstractQuery
import com.intellij.util.MergeQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.heex.HeexLanguage
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.heex.isInHeex
import org.elixir_lang.heex.xml.ComponentTagName
import org.elixir_lang.heex.xml.HeexComponentResolver
import org.elixir_lang.injection.PsiLanguageInjectionHost
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.model.psi.atom.AtomSymbol
import org.elixir_lang.model.psi.callback.BehaviourMembership
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.model.psi.callback.CallbackImplementation
import org.elixir_lang.model.psi.function.FunctionArityKeywordPairReference
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.model.psi.module.ModuleReference
import org.elixir_lang.model.psi.module.ModuleSymbol
import org.elixir_lang.model.psi.module_attribute.ModuleAttributeReference
import org.elixir_lang.model.psi.module_attribute.ModuleAttributeSymbol
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.model.psi.type.TypeReference
import org.elixir_lang.model.psi.type.TypeSymbol
import org.elixir_lang.model.psi.type.TypeVariableSymbol
import org.elixir_lang.model.psi.variable.VariableReference
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.model.psi.words.DecodedWordQuery
import org.elixir_lang.model.psi.words.WordOccurrenceMapper
import org.elixir_lang.model.psi.words.buildQueryFromLeaves
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.moduleAttributeName
import org.elixir_lang.psi.impl.identifierTextRange
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.scope.ancestorTypeSpec
import org.elixir_lang.reference.Callable
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.psi.operation.capture.NonNumeric as CaptureNonNumeric
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import java.text.Normalizer
import java.util.concurrent.Callable as JCallable

/**
 * Builds the usage queries for an [ElixirSymbolWithUsages] target; dispatches on the target type.
 * Free-standing (not part of any `Searcher`) so that [ElixirSymbolUsageSearcher] and
 * [ElixirRenameUsageSearcher] can share it: `Searcher` is `@ApiStatus.OverrideOnly`, so neither
 * searcher may invoke the other's interface methods - only the platform may call a `Searcher`.
 *
 * - Every searchable symbol contributes its self-declaration usage.
 * - A [ProtocolFunction] additionally contributes each **call site** that dispatches to it
 *   (`Protocol.function(args)` of matching name/arity) and each implementing `def`/`defmacro`
 *   clause inside a `defimpl` of the same protocol (so rename keeps every implementation in sync;
 *   for Find Usages the implementations are also reachable via "Go To Implementation").
 * - A [FunctionSymbol] additionally contributes each **call site** that dispatches to it.
 *   Qualified calls (`Module.function(args)`) are matched via [Call.isCalling]; unqualified calls
 *   require scope resolution and are resolved by delegating to the legacy [Callable] walker.
 *
 * Candidates are found by a name-anchored text search (efficient, only visits files containing the
 * name); behaviour membership is resolved via [BehaviourMembership]; call-site dispatch is resolved
 * via [Call.isCalling].
 */
@Suppress("UnstableApiUsage")
internal object ElixirUsageQueries {
    @RequiresReadLock
    fun searchRequests(
        project: Project,
        target: ElixirSymbolWithUsages,
        searchScope: SearchScope
    ): Collection<Query<out Usage>> {
        val queries = mutableListOf<Query<out Usage>>()
        queries += ElixirDirectUsageQuery(
            ElixirPsiUsage.create(
                target.file,
                target.range,
                declaration = true,
                usageTextByName = target.declarationTextByName
            )
        )

        queries += usageQueries(project, target, searchScope)

        // A symbol declared inside a decompiled file (e.g. a `.beam`) has usages that live in the SAME
        // decompiled file - for a BEAM type, every `@spec`/`@type` reference to it, plus the right-hand
        // side of its own `@type`. Those are invisible to the queries above: Find Usages is driven by
        // SearchService.searchWord over a GlobalSearchScope, which only visits word-indexed files, and a
        // `.beam` file's indexed content is its binary bytes - the decompiled text is never scanned. Re-run
        // the same per-symbol queries against a LocalSearchScope over the decompiled mirror: a
        // LocalSearchScope makes searchWord scan the given PSI text directly (no index), so the in-memory
        // mirror's occurrences are found. The global pass above cannot see these, so there is no overlap.
        val compiledFile = target.compiledFile
        if (compiledFile != null) {
            // The decompiled mirror to scan (the same cached instance whether `target.file` arrived as the
            // compiled file or as the mirror restored through the pointer - `getMirror()` caches it).
            val mirror = compiledFile.decompiledPsiFile
            // The per-symbol mappers anchor each usage to the mirror element's `containingFile` - the
            // in-memory mirror `ElixirFile`, which has no editor document/VirtualFile. The usage view cannot
            // map such usages to real lines, so they all collapse onto a single line. Re-anchor them to the
            // navigable compiled BEAM file (the mirror's `originalFile`): the mirror text is byte-for-byte the
            // decompiled editor's document text, so the absolute offsets are identical and now resolve to the
            // correct decompiled-editor lines - exactly like the declaration usage and Go To Declaration.
            usageQueries(project, target, LocalSearchScope(mirror)).mapTo(queries) { query ->
                query.mapping { usage: Usage ->
                    if (usage is ElixirPsiUsage && usage.file !== compiledFile) {
                        ElixirPsiUsage(compiledFile, usage.range, usage.declaration, usage.usageType, usage.usageTextByName)
                    } else {
                        usage
                    }
                }
            }
        }

        return queries
    }

    private fun usageQueries(
        project: Project,
        target: ElixirSymbolWithUsages,
        searchScope: SearchScope
    ): List<Query<out Usage>> {
        val queries = mutableListOf<Query<out Usage>>()
        when (target) {
            is Callback -> {
                queries += implementationQuery(project, target, searchScope)
            }

            is ProtocolFunction -> {
                queries += protocolCallSiteQuery(project, target, searchScope)
                queries += protocolImplementationQuery(project, target, searchScope)
            }

            is FunctionSymbol -> {
                queries += functionDeclarationFamilyQuery(project, target, searchScope)
                queries += functionCallSiteQuery(project, target, searchScope)
            }

            is AtomSymbol -> {
                // An AtomSymbol IS a function reference (module/name/arity/macro, anchored at the
                // def clause's name identifier - see AtomSymbol.fromClause), so renaming from the
                // atom must rename everything renaming from the def would: the declaration
                // family, call sites, specs, captures, keyword pairs - and the atoms themselves,
                // which FunctionCallSiteMapper's own atomUsage branch already finds. Reuse the
                // function queries via the field-for-field equivalent FunctionSymbol.
                val functionSymbol =
                    FunctionSymbol(target.file, target.range, target.moduleName, target.name, target.arity, target.macro)
                queries += functionDeclarationFamilyQuery(project, functionSymbol, searchScope)
                queries += functionCallSiteQuery(project, functionSymbol, searchScope)
            }

            is ModuleSymbol -> {
                queries += moduleUsageQuery(project, target, searchScope)
            }

            is TypeSymbol -> {
                queries += typeUsageQuery(project, target, searchScope)
            }

            is TypeVariableSymbol -> {
                queries += typeVariableUsageQuery(project, target, searchScope)
            }

            is ModuleAttributeSymbol -> {
                queries += moduleAttributeReadUsageQuery(project, target, searchScope)
                queries += moduleAttributeWriteUsageQuery(target, searchScope)
            }

            is VariableSymbol -> {
                queries += variableUsageQuery(project, target, searchScope)
            }
        }
        return queries
    }

    private fun implementationQuery(
        project: Project,
        callback: Callback,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(project, callback.name, searchScope, ImplementationMapper(callback.createPointer()))

    private fun protocolCallSiteQuery(
        project: Project,
        pf: ProtocolFunction,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(project, pf.name, searchScope, ProtocolCallSiteMapper(pf.createPointer()))

    private fun protocolImplementationQuery(
        project: Project,
        pf: ProtocolFunction,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(project, pf.name, searchScope, ProtocolImplementationMapper(pf.createPointer()))

    /**
     * Maps each occurrence of the callback name to an implementing definition clause, if any.
     */
    private class ImplementationMapper(
        private val callbackPointer: Pointer<out Callback>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val callback = callbackPointer.dereference() ?: return emptyList()

            // A `defoverridable name: arity` entry that names this callback (resolved through the
            // behaviour in scope) - keeps the overridable entry renaming with the callback.
            defoverridableKeyUsage(leaf, callback)?.let { return listOf(it) }

            // Nearest enclosing call-definition clause (def/defp/defmacro/...).
            val defClause = leaf.enclosingCalls().firstOrNull { CallDefinitionClause.`is`(it) } ?: return emptyList()

            // The occurrence must be the clause's own name, not a call in its body.
            val nameIdentifier = CallDefinitionClause.nameIdentifier(defClause) ?: return emptyList()
            if (!PsiTreeUtil.isAncestor(nameIdentifier, leaf, false)) return emptyList()

            if (!CallbackImplementation.implements(defClause, callback)) return emptyList()

            return listOf(
                ElixirPsiUsage.create(
                    nameIdentifier,
                    TextRange(0, nameIdentifier.textLength),
                    declaration = false,
                    usageType = IMPLEMENTATION
                )
            )
        }

        /**
         * If [leaf] is inside the key of a `defoverridable name: arity` entry that resolves to
         * [callback], the keyword-key occurrence, else `null`. Resolution reuses the shared
         * [FunctionArityKeywordPairReference] attached to the `defoverridable` host so behaviour-scope
         * logic stays in one place.
         */
        @RequiresReadLock
        private fun defoverridableKeyUsage(leaf: PsiElement, callback: Callback): PsiUsage? {
            val occurrence = FunctionArityKeywordPair.at(leaf) ?: return null
            if (occurrence.host != FunctionArityKeywordPair.Host.DEFOVERRIDABLE) return null
            if (occurrence.name != callback.name || occurrence.arity != callback.arity) return null

            val matches = PsiSymbolReferenceService.getService()
                .getReferences(occurrence.hostCall)
                .asSequence()
                .filterIsInstance<FunctionArityKeywordPairReference>()
                .filter { it.absoluteRange.containsOffset(leaf.textRange.startOffset) }
                .flatMap { it.resolveReference() }
                .filterIsInstance<Callback>()
                .any { it == callback }
            if (!matches) return null

            val key = occurrence.pair.keywordKey
            return ElixirPsiUsage.create(
                key,
                TextRange(0, key.textLength),
                declaration = false,
                usageType = IMPLEMENTATION
            )
        }
    }

    /**
     * Maps each occurrence of a protocol function name to a **call site** that dispatches to it, if any.
     * A call site is a qualified call `Protocol.function(args)` (of matching name/arity) whose resolved
     * module is the protocol.
     */
    private class ProtocolCallSiteMapper(
        private val protocolFunctionPointer: Pointer<out ProtocolFunction>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val protocolFunction = protocolFunctionPointer.dereference() ?: return emptyList()

            // Nearest enclosing call. A call site is an invocation, not a definition clause.
            val call = leaf.enclosingCalls().firstOrNull() ?: return emptyList()
            if (CallDefinitionClause.`is`(call)) return emptyList()

            // The occurrence must be the call's own function name, not one of its arguments.
            val nameElement = call.functionNameElement() ?: return emptyList()
            if (!PsiTreeUtil.isAncestor(nameElement, leaf, false)) return emptyList()

            // The call must dispatch to this protocol function: `<protocolName>.<name>/<arity>`.
            if (!call.isCalling(protocolFunction.protocolName, protocolFunction.name, protocolFunction.arity)) {
                return emptyList()
            }

            return listOf(
                ElixirPsiUsage.create(
                    nameElement,
                    TextRange(0, nameElement.textLength),
                    declaration = false,
                    usageType = CALL
                )
            )
        }
    }

    /**
     * Maps each occurrence of a protocol function name to an implementing `def`/`defmacro` clause
     * inside a `defimpl` of the same protocol, if any. Unlike Find Usages (where implementations are
     * reached via "Go To Implementation"), rename MUST update every `defimpl` clause so the protocol
     * member and all its concrete implementations stay in sync.
     */
    private class ProtocolImplementationMapper(
        private val protocolFunctionPointer: Pointer<out ProtocolFunction>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val protocolFunction = protocolFunctionPointer.dereference() ?: return emptyList()

            // Nearest enclosing call-definition clause (def/defmacro).
            val defClause = leaf.enclosingCalls().firstOrNull { CallDefinitionClause.`is`(it) } ?: return emptyList()

            // The occurrence must be the clause's own name, not a call in its body.
            val nameIdentifier = CallDefinitionClause.nameIdentifier(defClause) ?: return emptyList()
            if (!PsiTreeUtil.isAncestor(nameIdentifier, leaf, false)) return emptyList()

            val nameArity =
                    CallDefinitionClause.nameArityInterval(defClause, ResolveState.initial()) ?: return emptyList()
            if (nameArity.name != protocolFunction.name || protocolFunction.arity !in nameArity.arityInterval) {
                return emptyList()
            }

            // `def` implements a function protocol member; `defmacro` a macro member.
            val capabilities = CallDefinitionClause.capabilities(defClause) ?: return emptyList()
            val kindMatches =
                    if (protocolFunction.macro) capabilities.quotesArguments else capabilities.runtimeFunction
            if (!kindMatches) return emptyList()

            // The clause must live directly inside a `defimpl` for this protocol.
            val defimpl = CallDefinitionClause.enclosingModularMacroCall(defClause) ?: return emptyList()
            if (!Implementation.`is`(defimpl)) return emptyList()
            if (Implementation.protocolName(defimpl) != protocolFunction.protocolName) return emptyList()

            return listOf(
                ElixirPsiUsage.create(
                    nameIdentifier,
                    TextRange(0, nameIdentifier.textLength),
                    declaration = false,
                    usageType = IMPLEMENTATION
                )
            )
        }
    }

    /**
     * Builds a word-index query that finds every source token whose text matches the function name,
     * then hands each occurrence to [FunctionCallSiteMapper]. `includeInjections()` is required for
     * [FunctionCallSiteMapper.heexComponentTagUsage] to see a `~H` sigil's injected HTML PSI - an
     * injected fragment is a separate file from its host `.ex`, invisible to a plain word search. A component tag
     * is markup, not an atom, so each other spelling of the name is also searched, for tags and documentation code
     * alone.
     */
    private fun functionCallSiteQuery(
        project: Project,
        symbol: FunctionSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> {
        val mapper = FunctionCallSiteMapper(symbol.createPointer())
        val calls = wordQuery(project, symbol.name, searchScope, mapper, injections = true)
        // The decoded search cannot reach `<.src_µ>` or a call in a documentation code block, and lists every other use
        // itself, so these find those two only.
        val unreached = FunctionCallSiteMapper(symbol.createPointer(), unreachedOnly = true)
        val others = (spellings(symbol.name) - symbol.name).map { spelling ->
            literalWordQuery(project, spelling, searchScope, unreached, injections = true)
        }

        return others.fold(calls) { merged, next -> MergeQuery(merged, next) }
    }

    private fun typeUsageQuery(
        project: Project,
        symbol: TypeSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(project, symbol.searchText, searchScope, TypeUsageMapper(symbol.createPointer()))

    private fun typeVariableUsageQuery(
        project: Project,
        symbol: TypeVariableSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(
            project,
            symbol.searchText,
            symbol.maximalSearchScope?.intersectWith(searchScope) ?: searchScope,
            TypeVariableUsageMapper(symbol.createPointer())
        )

    private fun variableUsageQuery(
        project: Project,
        symbol: VariableSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(
            project,
            symbol.searchText,
            symbol.maximalSearchScope?.intersectWith(searchScope) ?: searchScope,
            VariableUsageMapper(symbol.createPointer())
        )

    private fun moduleAttributeReadUsageQuery(
        project: Project,
        symbol: ModuleAttributeSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(
            project,
            symbol.searchText,
            symbol.maximalSearchScope?.intersectWith(searchScope) ?: searchScope,
            ModuleAttributeReadUsageMapper(symbol.createPointer())
        )

    private fun moduleAttributeWriteUsageQuery(
        symbol: ModuleAttributeSymbol,
        @Suppress("UNUSED_PARAMETER") searchScope: SearchScope
    ): Query<out PsiUsage> = ModuleAttributeWriteUsageQuery(symbol.createPointer())

    /**
     * Finds every reference to this module using the word index (efficient) and then resolves
     * each occurrence via the existing PSI reference infrastructure. This handles all forms:
     * - `alias MyApp.Module` / `use MyApp.Module` / `import MyApp.Module`
     * - `alias MyApp.{Module, AnotherModule}` (multi-alias)
     * - Bare references in code: `Supervisor.init(\[MyApp.Module])`
     * - Aliased short-name references: `Module.function()` where `alias MyApp.Module` is in scope
     */
    private fun moduleUsageQuery(
        project: Project,
        symbol: ModuleSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        bySpelling(symbol.searchText) { word ->
            SearchService.getInstance()
                .searchWord(project, word)
                .caseSensitive(true)
                .inContexts(SearchContext.inCode())
                .inScope(searchScope)
                .buildQueryFromLeaves(ModuleUsageMapper(symbol.createPointer()))
        }

    private class ModuleUsageMapper(
        private val symbolPointer: Pointer<out ModuleSymbol>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()

            // Walk up to the outermost QualifiableAlias containing this leaf.
            val alias = generateSequence(leaf) { it.parent }
                .filterIsInstance<QualifiableAlias>()
                .lastOrNull()
                ?: return emptyList()

            // Skip module declaration names - null reference by convention.
            // The declaration is already contributed by ElixirDirectUsageQuery.
            if (alias.reference == null) return emptyList()

            // Primary: pure structural match via fullyQualifiedName(). Works for:
            //   alias MyApp.Module, use MyApp.Module, MyApp.{Module, Other}, MyApp.Module in code
            val fqn = alias.fullyQualifiedName().removeElixirPrefix()
            if (fqn == symbol.moduleName) return listOf(moduleNameUsage(alias, fqn, symbol))

            // Secondary: bare short-name reference where the FQN is just the last segment.
            // e.g. `Module` in code where `alias MyApp.Module` is in lexical scope.
            // `alias MyApp.Module` is simultaneously a usage of `MyApp.Module` AND a declaration
            // of `Module` in the enclosing scope - bare `Module` references that declaration
            // transitively. Check structurally: is there an alias/use/import of symbol.moduleName
            // in the enclosing defmodule? No index or scope resolution required.
            if (fqn == symbol.searchText && hasEnclosingModuleAlias(alias, symbol.moduleName)) {
                return listOf(
                    ElixirPsiUsage.create(
                        alias,
                        TextRange(0, alias.textLength),
                        declaration = false,
                        usageType = MODULE_REFERENCE,
                        // A bare reference stays bare: the `alias` line it rides on is renamed in
                        // the same pass, so only the new name's last segment belongs here.
                        usageTextByName = { newName -> newName.substringAfterLast('.') }
                    )
                )
            }

            // Tertiary: a name relative to a module around it, `Inner` for a `defmodule Inner` in `A`; which module
            // it names is the resolver's answer.
            if (symbol.moduleName.endsWith(".$fqn") && symbol in ModuleReference.resolve(alias, fqn)) {
                return listOf(moduleNameUsage(alias, symbol.moduleName, symbol))
            }

            return emptyList()
        }

        /**
         * [alias] spelling [fqn], [symbol]'s name, or a suffix of it.
         *
         * A rename's new name replaces the name as declared ([ModuleSymbol.targetName]), so the module's new name is
         * the rest of the old one, `Outer.` for `Renamee` declared in `Outer`, then the new name. Where [alias]
         * spells only a suffix - `Renamee` in `alias Grouped.{Renamee, Sibling}`, or inside `Outer` - the qualifier
         * it leaves out is left out of the new text too, or the member would become `Grouped.Fresh`. A rename
         * that moves the module out of that qualifier cannot be written there, so the full name is.
         */
        private fun moduleNameUsage(alias: QualifiableAlias, fqn: String, symbol: ModuleSymbol): PsiUsage {
            val declaredPrefix =
                if (symbol.moduleName.endsWith(symbol.targetName)) symbol.moduleName.removeSuffix(symbol.targetName) else ""
            val qualifierPrefix = fqn.removeSuffix(alias.text).takeIf { it != fqn } ?: ""
            val usageTextByName: ((String) -> String)? =
                if (declaredPrefix == qualifierPrefix) {
                    null
                } else {
                    { newName ->
                        val newFqn = declaredPrefix + newName

                        if (newFqn.startsWith(qualifierPrefix)) newFqn.removePrefix(qualifierPrefix) else newFqn
                    }
                }

            return ElixirPsiUsage.create(
                alias,
                TextRange(0, alias.textLength),
                declaration = false,
                usageType = MODULE_REFERENCE,
                usageTextByName = ModuleSymbol.textAt(alias.text, usageTextByName)
            )
        }

        private fun hasEnclosingModuleAlias(element: PsiElement, targetFqn: String): Boolean {
            val enclosingModule = generateSequence(element) { it.parent }
                .filterIsInstance<Call>()
                .firstOrNull { Module.`is`(it) }
                ?: return false

            return PsiTreeUtil.findChildrenOfType(enclosingModule, Call::class.java).any { call ->
                (call.isCalling(KERNEL, ALIAS) ||
                    call.isCalling(KERNEL, USE) ||
                    call.isCalling(KERNEL, IMPORT)) &&
                    call.finalArguments()
                        ?.firstOrNull()
                        ?.stripAccessExpression()
                        ?.let { argument -> argumentAliasFqns(argument).any { it == targetFqn } } == true
            }
        }

        /**
         * The fully-qualified names an alias/use/import argument brings into scope. A plain
         * `alias Grouped.Renamee` argument IS a [QualifiableAlias]; a multi-alias group
         * `alias Grouped.{Renamee, Sibling}` is not - its MEMBERS are the [QualifiableAlias]es,
         * each of which computes its FQN through the group qualifier. Enumerating the argument
         * itself plus its descendants covers both shapes (nested nodes resolve to their full
         * FQN too, so extras are harmless duplicates, never wrong names).
         */
        private fun argumentAliasFqns(argument: PsiElement): Sequence<String> {
            val self = (argument as? QualifiableAlias)?.let { sequenceOf(it) } ?: emptySequence()
            val descendants = PsiTreeUtil.findChildrenOfType(argument, QualifiableAlias::class.java).asSequence()
            return (self + descendants).map { it.fullyQualifiedName().removeElixirPrefix() }
        }

        private fun String.removeElixirPrefix(): String =
            if (startsWith("Elixir.")) removePrefix("Elixir.") else this
    }

    /**
     * Finds additional declaration clauses for the same logical function family
     * (`module.name/arity`) as [symbol].
     */
    private fun functionDeclarationFamilyQuery(
        project: Project,
        symbol: FunctionSymbol,
        searchScope: SearchScope
    ): Query<out PsiUsage> =
        wordQuery(project, symbol.name, searchScope, FunctionDeclarationFamilyMapper(symbol.createPointer()))

    /**
     * The literal word search for the atom [atom] over [scope], merged with the search for the occurrences it misses:
     * a name written with an escape, an operator, or decomposed. Elixir names are case-sensitive. [injections] says the
     * literal search maps occurrences into injected PSI, which the other search then does too.
     */
    private fun <T : Any> wordQuery(
        project: Project,
        atom: String,
        scope: SearchScope,
        mapper: WordOccurrenceMapper<T>,
        injections: Boolean = false
    ): Query<out T> {
        val literal = literalWordQuery(project, atom, scope, mapper, injections)

        return MergeQuery(literal, DecodedWordQuery(project, atom, scope, mapper, injections))
    }

    /** The platform's case-sensitive word search for [word] in code over [scope], mapped by [mapper]. */
    private fun <T : Any> literalWordQuery(
        project: Project,
        word: String,
        scope: SearchScope,
        mapper: WordOccurrenceMapper<T>,
        injections: Boolean
    ): Query<out T> =
        SearchService.getInstance()
            .searchWord(project, word)
            .caseSensitive(true)
            .inContexts(SearchContext.inCode())
            .inScope(scope)
            .let { if (injections) it.includeInjections() else it }
            .buildQueryFromLeaves(mapper)

    /**
     * One query per spelling the quoter reads as [word]'s atom, so a use written decomposed, or with a micro sign for
     * a mu, is found.
     */
    private fun <T> bySpelling(word: String, query: (String) -> Query<out T>): Query<out T> =
        spellings(word).map(query).reduce { merged, next -> MergeQuery(merged, next) }

    /**
     * The strings completion matches the prefix typed to reach [word] against: the name, and each spelling Elixir reads
     * as its atom at [element]'s language level. Before 1.14 each spelling is a name of its own, so only [word] itself.
     */
    @RequiresReadLock
    fun lookupStrings(word: String, element: PsiElement): Set<String> {
        val level = { ElixirLanguageLevelResolver.languageLevelFor(element) }
        val atom = identifierAtomName(word, level)

        return spellings(word).filterTo(linkedSetOf()) { identifierAtomName(it, level) == atom }
    }

    /**
     * Every way to write [word] that Elixir reads as its atom: each character as it is or decomposed, and a mu as the
     * micro sign, in every combination. A name with more combinations than [MAX_SPELLINGS] gets only the whole name
     * composed, decomposed, and with every mu a micro sign.
     */
    private fun spellings(word: String): Set<String> {
        val composed = Normalizer.normalize(word, Normalizer.Form.NFC)
        val alternatives = composed.codePoints().toArray().map { alternatives(it) }
        val combinations = alternatives.fold(1) { count, each -> minOf(count * each.size, MAX_SPELLINGS + 1) }

        if (combinations > MAX_SPELLINGS) {
            val micro = composed.replace(GREEK_MU, MICRO_SIGN)

            return linkedSetOf(word, composed, micro).also { whole ->
                whole += whole.map { Normalizer.normalize(it, Normalizer.Form.NFD) }
            }
        }

        return alternatives.fold(listOf("")) { prefixes, each -> prefixes.flatMap { prefix -> each.map { prefix + it } } }
            .toCollection(linkedSetOf(word))
    }

    private fun alternatives(codePoint: Int): List<String> {
        val character = String(Character.toChars(codePoint))

        return if (character == GREEK_MU || character == MICRO_SIGN) {
            listOf(GREEK_MU, MICRO_SIGN)
        } else {
            listOf(character, Normalizer.normalize(character, Normalizer.Form.NFD)).distinct()
        }
    }

    private const val GREEK_MU = "\u03BC"
    private const val MICRO_SIGN = "\u00B5"
    private const val MAX_SPELLINGS = 256

    /**
     * Maps each occurrence of a function name to a matching declaration clause in the same
     * logical function family (`module.name/arity`).
     */
    private class FunctionDeclarationFamilyMapper(
        private val symbolPointer: Pointer<out FunctionSymbol>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()

            val defClause = leaf.enclosingCalls().firstOrNull { CallDefinitionClause.`is`(it) } ?: return emptyList()
            val nameIdentifier = CallDefinitionClause.nameIdentifier(defClause) ?: return emptyList()
            if (!PsiTreeUtil.isAncestor(nameIdentifier, leaf, false)) return emptyList()
            if (!defClause.matchesFunctionFamily(symbol)) return emptyList()

            // Direct usage query already contributes the symbol's own declaration.
            if (defClause.containingFile.virtualFile == symbol.file.virtualFile &&
                nameIdentifier.textRange == symbol.range
            ) {
                return emptyList()
            }

            return listOf(
                ElixirPsiUsage.create(
                    nameIdentifier,
                    TextRange(0, nameIdentifier.textLength),
                    declaration = true
                )
            )
        }
    }

    /**
     * Maps each occurrence of a function name to a **call site** for the given [FunctionSymbol].
     *
     * Qualified calls (`Module.function(args)`) are matched by name/arity without scope resolution.
     * Unqualified calls are resolved via the legacy [Callable] scope-walker. With [unreachedOnly] it maps component
     * tags and calls in documentation code blocks alone, which [DecodedWordQuery] does not walk.
     */
    private class FunctionCallSiteMapper(
        private val symbolPointer: Pointer<out FunctionSymbol>,
        private val unreachedOnly: Boolean = false
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()

            if (unreachedOnly && !isInDocumentation(leaf)) {
                return heexComponentTagUsage(leaf, offsetInLeaf, symbol)?.let { listOf(it) }.orEmpty()
            }

            atomUsage(leaf, symbol)?.let { return listOf(it) }

            keywordKeyUsage(leaf, symbol)?.let { return listOf(it) }

            captureUsage(leaf, symbol)?.let { return listOf(it) }

            heexComponentTagUsage(leaf, offsetInLeaf, symbol)?.let { return listOf(it) }

            embeddedCallUsage(leaf, offsetInLeaf, symbol)?.let { return listOf(it) }

            val call = leaf.enclosingCalls().firstOrNull() ?: return emptyList()
            // Skip definition heads/clauses - they are declarations, not call sites.
            if (CallDefinitionClause.isHead(call) || CallDefinitionClause.`is`(call)) return emptyList()

            val nameElement = call.functionNameElement() ?: return emptyList()
            if (!PsiTreeUtil.isAncestor(nameElement, leaf, false)) return emptyList()

            specUsage(call, symbol)?.let { return listOf(it) }

            if (!matchesCallSite(call, symbol)) return emptyList()

            return listOf(
                ElixirPsiUsage.create(
                    nameElement,
                    TextRange(0, nameElement.textLength),
                    declaration = false,
                    usageType = CALL
                )
            )
        }

        /** Whether [leaf] is in the Elixir injected into a documentation code block. */
        private fun isInDocumentation(leaf: PsiElement): Boolean {
            val file = leaf.containingFile ?: return false
            val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return false

            return PsiLanguageInjectionHost.isDocumentation(host)
        }

        /**
         * Whether [call] is a call site for [symbol]: a qualified call (`Module.name/arity`) or an
         * unqualified call resolved via the legacy [Callable] scope-walker.
         */
        @RequiresReadLock
        private fun matchesCallSite(call: Call, symbol: FunctionSymbol): Boolean =
            if (call.resolvedModuleName() == symbol.moduleName &&
                call.functionName() == symbol.name &&
                call.resolvedFinalArity() == symbol.arity
            ) {
                true
            } else {
                Callable(call).multiResolve(false)
                    .asSequence()
                    .filter { it.isValidResult }
                    .mapNotNull { it.element as? Call }
                    .filter { CallDefinitionClause.`is`(it) }
                    .flatMap { FunctionSymbol.fromClause(it) }
                    .any { it == symbol }
            }

        /**
         * A plain Elixir call (`{some_function()}`) embedded in an *injected* `~H` fragment that
         * resolves to [symbol], else `null`. The word search hands back a leaf from the fragment's
         * HEEx root, which has no [Call] structure, so the call is found by offset in the Elixir
         * root instead. A top-level `.heex` file is excluded: its Elixir-root occurrence already
         * reaches the ordinary walk in [map], and its other roots' occurrences would
         * duplicate it.
         */
        @RequiresReadLock
        private fun embeddedCallUsage(leaf: PsiElement, offsetInLeaf: Int, symbol: FunctionSymbol): PsiUsage? {
            val leafFile = leaf.containingFile ?: return null
            if (leafFile.virtualFile !is VirtualFileWindow || !leafFile.isInHeex()) return null
            if (leaf.language.isKindOf(ElixirLanguage)) return null

            val elixirRoot = leafFile.viewProvider.getPsi(ElixirLanguage) ?: return null
            val absoluteOffset = leaf.textRange.startOffset + offsetInLeaf
            // findChildrenOfType expands the lazily parsed Elixir root; a raw findElementAt can
            // return the unexpanded chameleon node.
            val call = PsiTreeUtil.findChildrenOfType(elixirRoot, Call::class.java)
                .filterNot { CallDefinitionClause.isHead(it) || CallDefinitionClause.`is`(it) }
                .firstOrNull { candidate ->
                    candidate.functionNameElement()?.textRange?.contains(absoluteOffset) == true
                } ?: return null

            val nameElement = call.functionNameElement() ?: return null
            if (!matchesCallSite(call, symbol)) return null

            return ElixirPsiUsage.create(
                nameElement,
                TextRange(0, nameElement.textLength),
                declaration = false,
                usageType = CALL
            )
        }

        /**
         * A HEEx component tag name (`<.button>`, `<MyAppWeb.CoreComponents.button>`) at
         * [offsetInLeaf] that resolves to [symbol], else `null`. A tag has no enclosing [Call], so
         * the ordinary walk never reaches it; resolution is [HeexComponentResolver]'s, the same as
         * Go To Declaration. A `<:slot>` tag is never a usage.
         *
         * Only the HEEx root's occurrence is accepted: it is the one root every host delivers (an
         * injected `~H` fragment yields nothing from its nested template data root), and accepting
         * other roots' occurrences would report the same tag once per root. The tag itself is found
         * by absolute offset in the HTML root.
         */
        @RequiresReadLock
        private fun heexComponentTagUsage(leaf: PsiElement, offsetInLeaf: Int, symbol: FunctionSymbol): PsiUsage? {
            val leafFile = leaf.containingFile ?: return null
            if (!leafFile.isInHeex()) return null
            if (!leaf.language.isKindOf(HeexLanguage.INSTANCE)) return null

            val htmlRoot = leafFile.viewProvider.getPsi(HTMLLanguage.INSTANCE) ?: return null
            val absoluteOffset = leaf.textRange.startOffset + offsetInLeaf
            // findChildrenOfType expands the lazily parsed HTML root; a raw findElementAt can
            // return the unexpanded chameleon node.
            val htmlLeaf = PsiTreeUtil.findChildrenOfType(htmlRoot, XmlToken::class.java)
                .firstOrNull { it.node.elementType == XmlTokenType.XML_NAME && it.textRange.contains(absoluteOffset) }
                ?: return null
            val tag = htmlLeaf.parent as? XmlTag ?: return null

            val prefix = when (val component = ComponentTagName.parse(tag.name)) {
                is ComponentTagName.Local -> "."
                is ComponentTagName.Remote -> "${component.aliasChain}."
                is ComponentTagName.Slot, null -> return null
            }

            if (symbol !in HeexComponentResolver.resolveFunctionSymbols(tag)) return null

            return ElixirPsiUsage.create(
                htmlLeaf,
                TextRange(0, htmlLeaf.textLength),
                declaration = false,
                usageType = CALL,
                // Keep the `.`/alias prefix on rename: `<.button>` becomes `<.newName>`.
                usageTextByName = { newName -> "$prefix$newName" }
            )
        }
        /**
         * If [leaf] is the captured NAME of a `&name/arity` or `&Mod.name/arity` capture that
         * resolves to [symbol], the name occurrence, else `null`. The bare name inside a capture
         * classifies as a variable to the [Callable] scope-walker (so the generic call-site path
         * below cannot resolve it); the capture-aware resolution lives on the capture element's
         * own [CaptureNameArity] reference, which this reuses.
         */
        @RequiresReadLock
        private fun captureUsage(leaf: PsiElement, symbol: FunctionSymbol): PsiUsage? {
            val capture = generateSequence(leaf) { it.parent }
                .takeWhile { it !is PsiFile }
                .filterIsInstance<CaptureNonNumeric>()
                .firstOrNull() ?: return null
            val reference = capture.reference as? CaptureNameArity ?: return null

            // Only the captured name is a usage - not the `/arity` digits, and for a qualified
            // capture not the `Mod.` qualifier (CaptureNameArity's range is the name alone).
            val absoluteNameRange = reference.rangeInElement.shiftRight(capture.textRange.startOffset)
            if (!absoluteNameRange.contains(leaf.textRange)) return null
            if (reference.arity != symbol.arity) return null

            val matches = reference.multiResolve(false)
                .asSequence()
                .filter { it.isValidResult }
                .mapNotNull { it.element as? Call }
                .filter { CallDefinitionClause.`is`(it) }
                .flatMap { FunctionSymbol.fromClause(it) }
                .any { it == symbol }
            if (!matches) return null

            return ElixirPsiUsage.create(
                capture,
                reference.rangeInElement,
                declaration = false,
                usageType = CALL
            )
        }

        @RequiresReadLock
        private fun specUsage(call: Call, symbol: FunctionSymbol): PsiUsage? {
            call.enclosingSpecAttributeIfHead() ?: return null
            val matches = PsiSymbolReferenceService.getService()
                .getReferences(call)
                .flatMap { it.resolveReference() }
                .filterIsInstance<FunctionSymbol>()
                .any { it == symbol }
            if (!matches) return null

            val nameElement = call.functionNameElement() ?: return null
            return ElixirPsiUsage.create(
                nameElement,
                TextRange(0, nameElement.textLength),
                declaration = false,
                usageType = SPECIFICATION
            )
        }

        @RequiresReadLock
        private fun keywordKeyUsage(leaf: PsiElement, symbol: FunctionSymbol): PsiUsage? {
            val occurrence = FunctionArityKeywordPair.at(leaf) ?: return null
            // `defoverridable` keys resolve to a Callback, handled via the Callback search path.
            if (occurrence.host == FunctionArityKeywordPair.Host.DEFOVERRIDABLE) return null
            if (occurrence.name != symbol.name || occurrence.arity != symbol.arity) return null

            val matches = PsiSymbolReferenceService.getService()
                .getReferences(occurrence.hostCall)
                .asSequence()
                .filterIsInstance<FunctionArityKeywordPairReference>()
                .filter { it.absoluteRange.containsOffset(leaf.textRange.startOffset) }
                .flatMap { it.resolveReference() }
                .filterIsInstance<FunctionSymbol>()
                .any { it == symbol }
            if (!matches) return null

            val key = occurrence.pair.keywordKey
            return ElixirPsiUsage.create(
                key,
                TextRange(0, key.textLength),
                declaration = false,
                usageType = CALL
            )
        }

        @RequiresReadLock
        private fun atomUsage(leaf: PsiElement, symbol: FunctionSymbol): PsiUsage? {
            val atom = PsiTreeUtil.getParentOfType(leaf, ElixirAtom::class.java, false) ?: return null
            val references = PsiSymbolReferenceService.getService()
                .getReferences(atom)
                .filterIsInstance<AtomReference>()
            if (references.isEmpty()) return null

            // The reference's range is the name between the colon and any quotes, however many tokens an escape splits
            // it into, so a rename replaces the name and not the leaf that happened to be handed on.
            val reference = references.firstOrNull { reference ->
                reference.resolveReference()
                    .filterIsInstance<AtomSymbol>()
                    .any {
                        it.moduleName == symbol.moduleName &&
                            it.name == symbol.name &&
                            it.arity == symbol.arity &&
                            it.macro == symbol.macro
                    }
            } ?: return null

            return ElixirPsiUsage.create(
                atom,
                reference.absoluteRange.shiftLeft(atom.textRange.startOffset),
                declaration = false,
                usageType = CALL
            )
        }
    }

    private class TypeUsageMapper(
        private val symbolPointer: Pointer<out TypeSymbol>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()
            val call = leaf.enclosingCalls().firstOrNull() ?: return emptyList()
            // Type usages are only valid inside type/spec syntax, never in executable code
            // (for example, variable bindings in function heads).
            if (call.ancestorTypeSpec() == null) return emptyList()
            val nameElement = call.functionNameElement() ?: return emptyList()
            if (!PsiTreeUtil.isAncestor(nameElement, leaf, false)) return emptyList()
            if (call.enclosingTypeDeclarationIfHead() != null) return emptyList()
            if (call.enclosingSpecAttributeIfHead() != null) return emptyList()

            val resolved = TypeReference.resolveSymbols(call)
            if (resolved.none { it == symbol }) return emptyList()

            return listOf(
                ElixirPsiUsage.create(
                    nameElement,
                    TextRange(0, nameElement.textLength),
                    declaration = false,
                    usageType = SPECIFICATION
                )
            )
        }
    }

    private class TypeVariableUsageMapper(
        private val symbolPointer: Pointer<out TypeVariableSymbol>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()
            // The search scope is the single enclosing `@type`/`@spec` attribute, so any same-named
            // occurrence here is either this variable's declaration or one of its usages.
            val call = generateSequence(leaf) { it.parent }
                .takeWhile { it !is PsiFile }
                .filterIsInstance<Call>()
                .firstOrNull { candidate ->
                    val nameElement = candidate.functionNameElement()
                    nameElement != null &&
                        PsiTreeUtil.isAncestor(nameElement, leaf, false) &&
                        candidate.functionName() == symbol.name
                }
                ?: return emptyList()
            val nameElement = call.functionNameElement() ?: return emptyList()
            // Skip the declaration's own occurrence; it is contributed separately as declaration = true.
            if (nameElement.containingFile.virtualFile == symbol.file.virtualFile &&
                nameElement.textRange == symbol.range
            ) {
                return emptyList()
            }
            if (TypeReference.resolveTypeVariableSymbols(call).none { it == symbol }) return emptyList()

            return listOf(
                ElixirPsiUsage.create(
                    nameElement,
                    TextRange(0, nameElement.textLength),
                    declaration = false,
                    usageType = SPECIFICATION
                )
            )
        }
    }

    private class VariableUsageMapper(
        private val symbolPointer: Pointer<out VariableSymbol>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()
            val symbolChainRoot = symbol.chainRootSymbol() ?: return emptyList()
            for (candidate in generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }) {
                ProgressManager.checkCanceled()
                if (VariableSymbol.variableName(candidate) != symbol.name) continue
                val nameElement = VariableSymbol.nameIdentifierElement(candidate) ?: continue
                if (!PsiTreeUtil.isAncestor(nameElement, leaf, false)) continue
                if (nameElement.containingFile.virtualFile == symbol.file.virtualFile &&
                    nameElement.textRange == symbol.range
                ) {
                    continue
                }
                // A search scope is a text range, so it cannot tell a binding that SHADOWS this
                // variable (an `fn` parameter, a `case` clause pattern, a `for` generator) from
                // the outer one it hides - the inner binding sits inside the outer's scope.
                // Resolution can, so the occurrence is kept only when it is this same variable.
                if (!isOccurrenceOf(candidate, symbolChainRoot)) return emptyList()
                // NOTE: the right-hand read of a rebinding (`x` in `x = x + 1`) semantically reads
                // the PREVIOUS binding, but a rebinding chain is one user-facing variable, so it
                // is a usage regardless of which binding anchors the symbol. That is why the
                // comparison above is by chain root rather than by symbol.
                return listOf(
                    ElixirPsiUsage.create(
                        nameElement,
                        TextRange(0, nameElement.textLength),
                        declaration = false,
                        usageType = if (VariableSymbol.isDeclaration(candidate)) VALUE_WRITE else VALUE_READ
                    )
                )
            }
            return emptyList()
        }

        /**
         * True when [candidate] is an occurrence of the variable rooted at [symbolChainRoot].
         *
         * A binding is compared by its own chain root; a read is resolved first, because a read
         * belongs to whichever binding resolution reaches from where it stands. A read that
         * resolution cannot place at all falls back to the search scope, so that a gap in
         * resolution over-reports a usage rather than dropping one - Rename shares this search,
         * and a dropped usage leaves a dangling reference behind.
         */
        @RequiresReadLock
        private fun isOccurrenceOf(candidate: PsiElement, symbolChainRoot: VariableSymbol): Boolean {
            if (VariableSymbol.isDeclaration(candidate)) {
                return VariableSymbol.fromDeclaration(candidate)?.chainRootSymbol() == symbolChainRoot
            }
            val resolved = VariableReference.resolveSymbols(candidate)
            return resolved.isEmpty() || resolved.any { it.chainRootSymbol() == symbolChainRoot }
        }
    }

    private class ModuleAttributeReadUsageMapper(
        private val symbolPointer: Pointer<out ModuleAttributeSymbol>
    ) : WordOccurrenceMapper<PsiUsage> {
        @RequiresReadLock
        override fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<PsiUsage> {
            val symbol = symbolPointer.dereference() ?: return emptyList()

            for (candidate in generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }) {
                ProgressManager.checkCanceled()
                if (candidate !is Call) continue
                if (!candidate.isModuleAttributeNameElement()) continue
                if (candidate.functionName() != symbol.name) continue
                val nameElement = candidate.functionNameElement() ?: continue
                if (!PsiTreeUtil.isAncestor(nameElement, leaf, false)) continue
                // Same name + same module means the same logical attribute: a re-declared
                // (accumulated/overridden) attribute has several declaration sites, and a read
                // resolves only to the nearest preceding one - identity comparison would make a
                // read invisible when the rename starts from any of the other declarations.
                if (ModuleAttributeReference.resolveSymbols(candidate)
                        .none { it.name == symbol.name && it.moduleName == symbol.moduleName }
                ) {
                    continue
                }
                return listOf(
                    ElixirPsiUsage.create(
                        nameElement,
                        TextRange(0, nameElement.textLength),
                        declaration = false,
                        usageType = MODULE_ATTRIBUTE_READ
                    )
                )
            }

            return emptyList()
        }
    }

    private class ModuleAttributeWriteUsageQuery(
        private val symbolPointer: Pointer<out ModuleAttributeSymbol>
    ) : AbstractQuery<PsiUsage>() {
        override fun processResults(consumer: Processor<in PsiUsage>): Boolean =
            ReadAction.nonBlocking(JCallable {
                val symbol = symbolPointer.dereference() ?: return@JCallable true
                val declaration = generateSequence(symbol.file.findElementAt(symbol.range.startOffset)) { it.parent }
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .firstOrNull { it.atIdentifier.identifierTextRange() == symbol.range }
                    ?: return@JCallable true
                val modular = CallDefinitionClause.enclosingModularMacroCall(declaration) ?: return@JCallable true
                val seen = mutableSetOf<TextRange>(declaration.atIdentifier.textRange)

                // Every declaration in the module, not just the following ones: the rename can start from any of a
                // re-declared attribute's declaration sites, or from a read, which resolves to only one of them.
                for (candidate in CallDefinitionClause.modularChildCalls(modular)) {
                    ProgressManager.checkCanceled()
                    val declarationCandidate = candidate as? AtUnqualifiedNoParenthesesCall<*> ?: continue
                    val candidateSymbol = ModuleAttributeSymbol.fromDeclaration(declarationCandidate) ?: continue

                    if (candidateSymbol.name != symbol.name || candidateSymbol.moduleName != symbol.moduleName) continue
                    if (!seen.add(declarationCandidate.atIdentifier.textRange)) continue

                    val atIdentifier = declarationCandidate.atIdentifier
                    val usage = ElixirPsiUsage.create(
                        atIdentifier,
                        atIdentifier.identifierTextRange().shiftLeft(atIdentifier.textRange.startOffset),
                        declaration = false,
                        usageType = MODULE_ATTRIBUTE_WRITE
                    )
                    if (!consumer.process(usage)) return@JCallable false
                }

                true
            }).executeSynchronously()
    }
}

private val IMPLEMENTATION = UsageType { "Implementation" }

private val CALL = UsageType { "Function call" }

private val MODULE_REFERENCE = UsageType { "Module reference" }

private val SPECIFICATION = UsageType { "Specification" }

private val MODULE_ATTRIBUTE_READ = UsageType { "Module attribute read" }

private val MODULE_ATTRIBUTE_WRITE = UsageType { "Module attribute accumulate or override" }

private val VALUE_READ = UsageType { "Value read" }

private val VALUE_WRITE = UsageType { "Value write" }

private fun PsiElement.enclosingCalls(): Sequence<Call> =
        generateSequence(parent) { it.parent }.takeWhile { it !is PsiFile }.filterIsInstance<Call>()

@RequiresReadLock
private fun Call.matchesFunctionFamily(symbol: FunctionSymbol): Boolean {
    val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(this) ?: return false
    val moduleName = runCatching { Module.name(enclosingModular) }
        .getOrElse { if (it is ProcessCanceledException) throw it else null }
        ?: return false
    if (moduleName != symbol.moduleName) return false

    val nameArity = CallDefinitionClause.nameArityInterval(this, ResolveState.initial()) ?: return false
    if (nameArity.name != symbol.name || symbol.arity !in nameArity.arityInterval) return false

    val clauseIsMacro = CallDefinitionClause.capabilities(this)?.quotesArguments == true
    return clauseIsMacro == symbol.macro
}

@RequiresReadLock
private fun Call.enclosingSpecAttributeIfHead(): AtUnqualifiedNoParenthesesCall<*>? {
    val moduleAttribute = generateSequence(parent) { it.parent }
        .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
        .firstOrNull()
        ?: return null
    if (moduleAttributeName(moduleAttribute) != "@spec") return null
    val specification = CallDefinitionSpecification.specification(moduleAttribute) ?: return null
    val specHead = CallDefinitionSpecification.specificationType(specification) ?: return null
    return if (specHead.isEquivalentTo(this)) moduleAttribute else null
}

@RequiresReadLock
private fun Call.enclosingTypeDeclarationIfHead(): AtUnqualifiedNoParenthesesCall<*>? {
    val moduleAttribute = generateSequence(parent) { it.parent }
        .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
        .firstOrNull { org.elixir_lang.structure_view.element.Type.`is`(it) }
        ?: return null
    val specification = CallDefinitionSpecification.specification(moduleAttribute) ?: return null
    val typeHead = CallDefinitionSpecification.specificationType(specification) ?: return null
    return if (typeHead.isEquivalentTo(this)) moduleAttribute else null
}
