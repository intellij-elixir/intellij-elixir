package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.RejectedCall
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.admits
import org.elixir_lang.declaration.atItsHead
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.passed
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.model.psi.atom.contentTextRange
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.Delegation

/**
 * The Symbols a use names, from the preference step: each admitted candidate's; or, with none admitted, a [NearMiss] of
 * each head [RejectedCall.named] gives, at the lowest arity it accepts. A use of a delegation names the delegation, whose
 * Go To lands on what it delegates to.
 */
@Suppress("UnstableApiUsage")
internal object UseSymbols {
    /** A qualified `unquote` can reach any function of its module, and a name with no atom value none: neither names one. */
    @RequiresReadLock
    fun of(use: Use.OfCall?): List<Symbol> =
        when (use) {
            is Use.Named -> of(use, remote(use)) { use.arity }
            is Use.AnyName, null -> emptyList()
        }

    /** A `@spec` names what its definition's head declares, so a head with defaults gives it the head's one Symbol. */
    @RequiresReadLock
    fun of(use: Use.Specified?): List<Symbol> =
        use?.let { specified ->
            of(specified, remote(specified)) { found -> headArity(found.candidate.declaration) ?: specified.arity }
        }.orEmpty()

    @RequiresReadLock
    private fun of(use: Use, remote: Boolean, arity: (Found) -> Int): List<Symbol> {
        val found = step(use).map { it.found }

        return RejectedCall.named(found, Found::candidate, remote, ::admits)
            ?.flatMap { named -> (named.arities.minOrNull() ?: named.from)?.let { symbols(named.candidate, it) }.orEmpty() }
            ?.map(::NearMiss)
            ?: admitted(found, arity)
    }

    /**
     * Whether [use] reaches a module from outside it, as a qualified call or an MFA atom does: it then reaches only
     * what the module exports, where a local call or a `@spec` reaches every function it holds.
     */
    internal fun remote(use: Use): Boolean = use is Use.Remote || (use as? Use.OfCall)?.entrance is Qualified

    /** The Symbols of the candidates the step admits for [use], never a [NearMiss]: what a call site names. */
    @RequiresReadLock
    fun admitted(use: Use.Named): List<Symbol> = admitted(step(use).map { it.found }) { use.arity }

    @RequiresReadLock
    private fun admitted(found: List<Found>, arity: (Found) -> Int): List<Symbol> =
        found.filter(::admitted).flatMap { symbols(it, arity(it)) }

    /** The arity of the one Symbol a head declares: its lowest, as a head with defaults declares one Symbol for its name. */
    fun headArity(declaration: Declaration): Int? = declaration.arity.arityInterval()?.minimum

    @RequiresReadLock
    private fun step(use: Use): List<Preferred> =
        Resolved.step(use, false)

    /** Whether each `import` [found] was reached through brings it in at [arity]. */
    @RequiresReadLock
    internal fun admits(found: Found, arity: Int): Boolean {
        val declaration = found.candidate.declaration

        return found.via.filter { Import.`is`(it) }.all { importCall ->
            Import.filter(importCall, listOf(found.element) + found.via, ElixirLanguageLevelResolver.languageLevelFor(importCall))
                .admits(declaration.name, arity, declaration.capabilities.compileTime)
        }
    }

    private fun admitted(found: Found): Boolean = found.candidate.applicability.admitted

    /**
     * The Symbols of what [delegation] delegates to, read from the step's `directlyDelegatedTo` at the use its head is,
     * at what the head passes on.
     */
    @RequiresReadLock
    fun delegatedTo(delegation: Call): List<Symbol> =
        delegated(delegation)?.let { (found, passed) -> found.flatMap { symbols(it, passed) } }.orEmpty()

    /** The Symbols [delegation]'s head names besides its own: with no `as:`, it calls each target by the head's name. */
    @RequiresReadLock
    fun namedByHead(delegation: Call): List<Symbol> =
        if (Delegation.headKeepsTargetName(delegation)) delegatedTo(delegation) else emptyList()

    /** What [delegation] calls, as `T.f/1`, when it delegates to nothing that takes what its head passes on. */
    @RequiresReadLock
    fun unfoundTarget(delegation: Call): String? {
        if (delegatedTo(delegation).isNotEmpty()) return null

        val use = headUse(delegation) ?: return null
        val passed = atItsHead(delegation, use, Feature.SYMBOL_REFERENCES)?.let { passed(it, use) } ?: use.arity

        return Delegation.fromCall(delegation)?.to()?.let { "$it.${use.name}/$passed" }
    }

    /**
     * The delegation whose name [leaf] is, when its head names [symbol], directly or down a chain of delegations without
     * `as:`: [namedByHead] read the other way, at one occurrence of [symbol]'s name.
     */
    @RequiresReadLock
    fun namingByHead(leaf: PsiElement, symbol: Symbol): Call? {
        val delegation = DeclaringForm.named(leaf)?.takeIf { it.form == Form.DELEGATION }?.call ?: return null
        val named = definitionsNamedByHead(delegation).flatMap { definition ->
            reachable(definition) { next ->
                (next as? Call)?.takeIf { Delegation.`is`(it) }?.let(::definitionsNamedByHead).orEmpty()
            }
        }

        return delegation.takeIf { named.any { definition -> names(definition, symbol, leaf) } }
    }

    /** Whether [definition] is one of [symbol]'s, for a use at [at]. */
    @RequiresReadLock
    private fun names(definition: PsiElement, symbol: Symbol, at: PsiElement): Boolean =
        when (symbol) {
            is FunctionSymbol -> FunctionSymbol.matches(definition, symbol, at)
            is ProtocolFunction -> symbol in symbols(definition, symbol.name, symbol.arity)
            else -> false
        }

    /** The definitions [namedByHead]'s Symbols are made from. */
    @RequiresReadLock
    fun definitionsNamedByHead(delegation: Call): List<PsiElement> =
        if (Delegation.headKeepsTargetName(delegation)) delegated(delegation)?.first.orEmpty().map { it.element } else emptyList()

    /** What the step finds [delegation] delegating to at the use its head is, with the arity the head passes on. */
    @RequiresReadLock
    private fun delegated(delegation: Call): Pair<List<Found>, Int>? {
        val use = headUse(delegation) ?: return null
        val paired = atItsHead(delegation, use, Feature.SYMBOL_REFERENCES) ?: return null

        return paired.directlyDelegatedTo to passed(paired, use)
    }

    /** The use [delegation]'s head is, `null` where it names nothing. */
    @RequiresReadLock
    internal fun headUse(delegation: Call): Use.Named? = (Delegation.head(delegation) as? Call)?.let(Use::of) as? Use.Named

    /** The arity [use], a delegation's head, calls its target at. */
    internal fun passed(paired: Preferred, use: Use.Named): Int = paired.found.candidate.declaration.arity.passed() ?: use.arity

    @RequiresReadLock
    private fun symbols(found: Found, arity: Int): List<Symbol> = symbols(found.element, found.candidate.declaration, arity)

    /**
     * The Symbols [definition] has for [name] at [arity], built as a use's are: a definition is a use of a target that
     * is one of them.
     */
    @RequiresReadLock
    fun symbols(definition: PsiElement, name: String, arity: Int): List<Symbol> =
        declarations(definition, name, arity).flatMap { symbols(definition, it, arity) }

    /** The declarations [definition], a declaring call or a compiled definition, makes for [name] at an arity that admits [arity]. */
    @RequiresReadLock
    fun declarations(definition: PsiElement, name: String, arity: Int): List<Declaration> =
        when (definition) {
            is BeamCallDefinition -> listOf(definition.declaration())
            is Call ->
                DeclaringForm.declaredBy(definition)
                    ?.let { form -> Declarations.of(form, definition, ResolveState.initial()).mapNotNull { it.declaration } }
                    .orEmpty()
            else -> emptyList()
        }.filter { it.name == name && it.arity.admits(arity) }

    @RequiresReadLock
    private fun symbols(definition: PsiElement, declaration: Declaration, arity: Int): List<Symbol> =
        when (val declared = declaration.declared) {
            is Declared.Source -> {
                val call = definition as? Call

                when (declared.form) {
                    Form.CLAUSE, Form.DELEGATION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED ->
                        call?.let { symbols(it, DeclaringForm.names(it, declared.form).singleOrNull(), declaration, arity) }
                    Form.EXCEPTION -> call?.let { symbols(it, it.functionNameElement(), declaration, arity) }
                    Form.CALLBACK -> null
                }
            }
            is Declared.Compiled ->
                ((definition as? BeamCallDefinition)?.navigationElement as? Call)
                    ?.let { symbols(it, CallDefinitionClause.nameIdentifier(it), declaration, arity) }
        }.orEmpty()

    /** The text a rename replaces: an atom's content, without its `:` or quotes, or the whole name. */
    @RequiresReadLock
    fun nameRange(nameIdentifier: PsiElement): TextRange =
        when (val name = nameIdentifier.stripAccessExpression()) {
            is ElixirAtom -> contentTextRange(name).shiftRight(name.textRange.startOffset)
            else -> nameIdentifier.textRange
        }

    /** The Symbol of [declaration], which [call] makes under [nameIdentifier], at [arity]: what a use and a declaration share. */
    @RequiresReadLock
    fun symbols(call: Call, nameIdentifier: PsiElement?, declaration: Declaration, arity: Int): List<Symbol> {
        val range = nameIdentifier?.let(::nameRange) ?: return emptyList()
        val modular = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()
        val owner = FunctionSymbol.owner(modular) ?: return emptyList()
        val macro = FunctionSymbol.isMacro(declaration.capabilities)

        return listOf(
            if (Protocol.`is`(modular)) {
                ProtocolFunction(call.containingFile, range, owner.moduleName, declaration.name, arity, macro)
            } else {
                // A decompiled clause's `originalFile` is the compiled file the decompiled editor opens.
                FunctionSymbol(
                    call.containingFile.originalFile,
                    range,
                    owner.moduleName,
                    declaration.name,
                    arity,
                    macro,
                    owner.injected,
                    declaration.arity == ArityKnowledge.Unknown
                )
            }
        )
    }
}
