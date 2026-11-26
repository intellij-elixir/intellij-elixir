package org.elixir_lang.declaration

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArity
import org.elixir_lang.beam.CompiledIdentity
import org.elixir_lang.beam.FileType as BeamFileType
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.expander.NameIndex
import org.elixir_lang.expander.WrittenHead
import org.elixir_lang.model.psi.atom.mfaReferenceDecision
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.qualification.Qualified as QualifiedPsi
import org.elixir_lang.psi.impl.functionNameAtomValue
import org.elixir_lang.psi.impl.headAtomValue
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.qualifier
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.CallDefinitionSpecification

/** What a use can reach, and what is visible at a position: the one place features ask. */
interface CandidateSource {
    /**
     * Every candidate at [use], by declaring element in the order the source first found each, and each element's
     * candidates in the order found. [incompleteCode] says the use is being typed, so candidates otherwise dropped may
     * be returned.
     */
    @RequiresReadLock
    fun candidates(use: Use, incompleteCode: Boolean): List<Found>

    /** [candidates], and whether their lacking one Elixir reaches proves [use] reaches nothing. */
    @RequiresReadLock
    fun answer(use: Use, incompleteCode: Boolean): Answer = Answer(candidates(use, incompleteCode), absence = null)

    /** What completion offers at [position]. */
    @RequiresReadLock
    fun visible(position: CompletionPosition): List<Visible>

    /** What completion offers at the call [position]. */
    @RequiresReadLock
    fun visible(position: Call): List<Visible> = visible(CompletionPosition.OfCall(position))

    /** The names the declaring [call] writes beyond a clause's own head word. */
    @RequiresReadLock
    fun writtenNames(call: Call): List<WrittenName>

    /** The form [call] declares with, or `null` where it declares nothing. */
    @RequiresReadLock
    fun declaredForm(call: Call): Form?

    /** The exports of the module [call] is qualified by, or `null` where this source can't say. */
    @RequiresReadLock
    fun qualifiedExports(call: Call): QualifiedExports? = null

    /**
     * Whether the module [call]'s qualifier names is held where [call] is. For a qualifier that is an alias, it is
     * [module]'s answer.
     */
    @RequiresReadLock
    fun qualifiedModule(call: Qualified): QualifiedModule

    /**
     * Whether the module the whole of [alias] names is held where [alias] is, the one place an alias's module is
     * located. A member of `Foo.{A}` names the module `Foo` names, with `A` nested in it.
     */
    @RequiresReadLock
    fun module(alias: QualifiableAlias): QualifiedModule

    /**
     * The PSI of the module [held], this source's answer from [qualifiedModule] or [module], found: each `defmodule`
     * or `.beam` module. A [QualifiedModule.Held] from another source is an error.
     */
    @RequiresReadLock
    fun modulars(held: QualifiedModule.Held): Set<PsiElement>

    /**
     * The definitions of [name] that [modular], a module, wrote and no longer holds, because `defoverridable` took them and
     * a later definition of their name and arity replaced them, in the order it defined them: Elixir's docs row takes
     * what each documents in turn (`Module.compile_doc`, `module.ex:1880-1912`). `null` where the source can't answer,
     * as the default does, and empty where it answered and nothing was replaced.
     */
    @RequiresReadLock
    fun replaced(modular: PsiElement, name: String): List<Found>? = null

    /**
     * [found], one of [use]'s candidates, where it is written, as a feature that reads that sees it
     * ([Feature.readsLocation]). A `.beam`'s definition is each clause of its export the `.beam`'s decompiled source
     * writes; any other find is itself.
     */
    @RequiresReadLock
    fun located(use: Use, found: Found): List<Found> = decompiledClauses(found) ?: listOf(found)
}

/** Whether [this], a head, declares the `.beam` entry [entry], a macro where [macro], by kind, name and arity. */
internal fun Declaration.declaresEntry(entry: NameArity, macro: Boolean): Boolean =
    capabilities.compileTime == macro && name == entry.name && arity.admits(entry.arity)

/**
 * Whether [this], a head, writes the `.beam` export [entry], a macro where [macro]: only a public head of its kind can,
 * and a contract, as a `@callback` is, writes none.
 */
internal fun Declaration.writesExport(entry: NameArity, macro: Boolean): Boolean =
    capabilities.public && !isContract(this) && declaresEntry(entry, macro)

/**
 * [this], a `.beam`'s definition, at each of [heads] that writes an export of it, in [module], declared as that head
 * declares it; `null` where none does.
 */
internal fun Found.atHeadsWriting(heads: Iterable<WrittenHead>, module: String? = head.module): List<Found>? =
    candidate.declaration.writtenBy(heads)?.map { written -> writtenAt(written, module) }

/** Each of [heads] that writes an export of [this], a `.beam`'s definition; `null` where none does. */
private fun Declaration.writtenBy(heads: Iterable<WrittenHead>): List<WrittenHead>? {
    val arities = arity.arityInterval() ?: return null

    return heads
        .filter { written -> arities.closed().any { written.declaration.writesExport(NameArity(name, it), capabilities.compileTime) } }
        .ifEmpty { null }
}

/**
 * [found], a `.beam`'s definition, at each clause of its export the `.beam`'s decompiled source writes, declared as that
 * clause declares it; `null` where it is no `.beam`'s definition, or the decompiled source writes no clause of it.
 */
@RequiresReadLock
private fun decompiledClauses(found: Found): List<Found>? {
    val declaration = found.candidate.declaration
    if (declaration.declared !is Declared.Compiled) return null
    val modular = decompiledModule(found.element) ?: return null
    // The index name of the decompiled `defmodule` the clauses are written in.
    val module = (modular as? CanonicallyNamed)?.canonicalName() ?: found.head.module

    return found.atHeadsWriting(NameIndex.of(modular).named(declaration.name), module)
}

/**
 * Each clause of the export [definition], a `.beam`'s definition declared as [declaration], that the `.beam`'s
 * decompiled source writes, as [CandidateSource.located] places a find of it; `null` where [definition] is no `.beam`'s
 * definition, or that source writes no clause of it.
 */
@RequiresReadLock
internal fun decompiledHeads(definition: PsiElement, declaration: Declaration): List<WrittenHead>? =
    decompiledModule(definition)?.let { declaration.writtenBy(NameIndex.of(it).named(declaration.name)) }

/** The decompiled `defmodule` [definition], a `.beam`'s definition, is written in. */
@RequiresReadLock
private fun decompiledModule(definition: PsiElement): Call? =
    ((definition as? BeamCallDefinition)?.navigationElement as? Call)?.let(CallDefinitionClause::enclosingModularMacroCall)

/** [this] at [written], a head its call writes in [module], declared and headed as that head declares it. */
private fun Found.writtenAt(written: WrittenHead, module: String?): Found =
    copy(
        candidate = candidate.copy(declaration = written.declaration),
        element = written.call,
        head = Head(written.written, module, written.call, written.declaration.name, written.declaration.arity)
    )

/**
 * A name [element] a declaring call writes, how it presents, and why it may not be a name Elixir defines, [uncertain],
 * `null` where it is.
 */
data class WrittenName(val element: PsiElement, val presentation: Presentation, val uncertain: Uncertain?)

/** Where completion asks what is visible. */
sealed class CompletionPosition {
    /** The element completion runs at. */
    abstract val element: PsiElement

    /** The element that names the module whose functions are offered; `null` where none is named. */
    abstract val module: PsiElement?

    /** A [call], qualified or not. */
    data class OfCall(val call: Call) : CompletionPosition() {
        override val element: PsiElement get() = call
        override val module: PsiElement? get() = (call as? QualifiedPsi)?.qualifier()
    }

    /**
     * What the dot of `Mod.` ends in completion's copy of the file, where the dummy identifier follows it: the qualified
     * alias a bare dummy makes, or the qualified expression a dummy with a name typed before it makes.
     */
    data class AfterDot(val dotted: PsiElement) : CompletionPosition() {
        override val element: PsiElement get() = dotted
        override val module: PsiElement?
            get() = when (dotted) {
                is QualifiableAlias -> dotted.qualifier()
                is QualifiedPsi -> dotted.qualifier()
                else -> null
            }
    }

    /** The name of the qualified capture `&Mod.name/arity`, [name], at its [arity]. */
    data class Capture(val name: Call, val arity: Int) : CompletionPosition() {
        override val element: PsiElement get() = name
        override val module: PsiElement? get() = (name as? QualifiedPsi)?.qualifier()
    }

    /** An [element] that is not a call, such as a sigil's `~` or an operator's left operand: what its env sees. */
    data class At(override val element: PsiElement) : CompletionPosition() {
        override val module: PsiElement? get() = null
    }

    /** The function [atom] of an MFA, whose module [module] names. */
    data class Mfa(val atom: ElixirAtom, override val module: PsiElement) : CompletionPosition() {
        override val element: PsiElement get() = atom
    }
}

/**
 * A [candidate] at a use, the PSI [element] that declares it, [via], the `import` and `use` calls and then the
 * `defdelegate` calls it was reached through, the [head] that declares it, and for a delegation target the head of the
 * delegation the use reached it through, [delegatedThrough], as that delegation's own [head] is, why it may not be
 * what Elixir reaches, [uncertain], `null` where it is, and the arities of it the `import` it was reached through
 * brings in, [imported], where its source records them; `null` where it records none, and the `import` calls in [via]
 * say, and the doc Elixir keeps for it, [recordedDoc], where an expansion defined it and recorded one.
 */
data class Found(
    val candidate: Candidate,
    val element: PsiElement,
    val via: List<Call>,
    val head: Head,
    val delegatedThrough: Head?,
    val uncertain: Uncertain? = null,
    val imported: Set<Int>? = null,
    val recordedDoc: RecordedDoc? = null,
) {
    val certain: Boolean get() = uncertain == null

    /**
     * The definition a find is one of: its [identity] in the [module] that declares it, else, where its identity is not
     * known, the find [alone], which groups with no other.
     */
    data class Definition(val identity: NameArity?, val module: String?, val alone: Found?)
}

/**
 * The doc Elixir keeps for a function an expansion defined, as `Module.compile_doc` took it from the `@doc` pending
 * where the definition was compiled: a [Text], or [Hidden] where it is `false` or, with a name that starts with an
 * underscore, none.
 */
sealed interface RecordedDoc {
    /** A doc written as [markdown]. */
    data class Text(val markdown: String) : RecordedDoc

    /** The doc of a function that isn't part of the public API: `@doc false`, which an `@impl` also gives where no doc is written. */
    data object Hidden : RecordedDoc
}

/**
 * One head of a definition: the PSI [written] that writes it, the [module] that declares it, as the source that found
 * it knows it, the call [placedBy] that places it in [module], and its [name] and [arity]. [written] is a clause's or a
 * delegation's head, one of a list of `defdelegate` heads, the call of a form that writes no head, or a compiled
 * definition; it is `null` where nothing written declares the head, as where debug info places it at the macro clause
 * that injected it. [placedBy] is the clause itself where it is written in [module]'s own body, else the call in
 * [module]'s file whose output injected it; it is `null` for a compiled definition, or where no such call is found.
 */
data class Head(val written: PsiElement?, val module: String?, val placedBy: Call?, val name: String, val arity: ArityKnowledge) {
    /**
     * Whether nothing written names it, as where a macro's output places it: its element is the macro's clause, the
     * call that injected it, or a clause written with its name unquoted, none of which writes its head.
     */
    val placed: Boolean get() = written == null
}

/**
 * A use's [found], and why their holding nothing Elixir reaches may not prove the use reaches nothing, [absence],
 * `null` where it does.
 */
data class Answer(val found: List<Found>, val absence: Uncertain?)

/** The name of the module that declares [this], so two copies of one module are one. */
fun Found.declaringModuleName(): String? = head.module

/**
 * Where a clause is defined in its module, as Elixir defines the module's clauses in order: [offset] in the module's
 * file, of the clause or of the call that injects it, then [within], the head's offset in its own file, which orders
 * the heads one call writes or injects.
 */
data class ClauseOrder(val offset: Int, val within: Int) : Comparable<ClauseOrder> {
    override fun compareTo(other: ClauseOrder): Int = compareValuesBy(this, other, ClauseOrder::offset, ClauseOrder::within)

    companion object {
        /** A clause whose [head] is written in its own module. */
        fun at(head: PsiElement): ClauseOrder = ClauseOrder(head.textOffset, head.textOffset)
    }
}

/**
 * Where [this]'s clause is defined in the module that declares it: at the call [Head.placedBy] that places its head
 * there, then at its head, or at its element where nothing writes the head. `null` where no call places it.
 */
@RequiresReadLock
fun Found.clauseOrder(): ClauseOrder? = head.placedBy?.let { ClauseOrder(it.textOffset, (head.written ?: element).textOffset) }

/** The `.beam` whose decompiled mirror [this] is written in; `null` for a declaration written in source, or compiled. */
internal fun Declaration.mirroredIn(): VirtualFile? =
    (declared as? Declared.Source)?.origin?.file?.takeIf { it.fileType is BeamFileType }

/** The function [this] is one definition of, as Elixir keys it: its head's [Head.identity]. */
@RequiresReadLock
fun Found.identity(): NameArity? = head.identity(candidate.declaration, element)

/**
 * The function [this], a head of [declaration] found at [element], is one definition of, as Elixir keys it:
 * [Declaration.identity], unless the head written for it declares a larger full arity, as a head with defaults does
 * where its find is one of the arities they add. A head written in a `.beam`'s decompiled mirror is its
 * [keyedIdentity]. A placed head is read from [element] where that is a clause whose name is unquoted
 * ([nameUnquotedHead]); another placed head, or a compiled find, keeps [Declaration.identity].
 */
@RequiresReadLock
fun Head.identity(declaration: Declaration, element: PsiElement): NameArity? {
    val identity = declaration.identity()
    val declared = declaration.declared as? Declared.Source ?: return identity
    val written = written ?: nameUnquotedHead(element) ?: return identity
    if (declaration.mirroredIn() != null) return declaration.keyedIdentity()
    // Only these forms write a head of their own; another's written element is the call of its form.
    if (declared.form != Form.CLAUSE && declared.form != Form.DELEGATION) return identity
    val full = fullArity(written) ?: return identity

    return if (identity != null && full > identity.arity) NameArity(declaration.name, full) else identity
}

/**
 * The function [this] is one definition of, as Elixir keys it: [Declaration.identity], read as compiled where [this] is
 * written in a `.beam`'s decompiled mirror, which writes each arity a default generates as a head of its own.
 */
internal fun Declaration.keyedIdentity(): NameArity? {
    val identity = identity() ?: return null

    return mirroredIn()?.let { CompiledIdentity.of(it, identity.name, identity.arity) } ?: identity
}

/** The full arity [head], a written head, declares, each default counted; `null` where its arity is not known. */
@RequiresReadLock
internal fun fullArity(head: PsiElement): Int? =
    CallDefinitionHead.nameArityInterval(head, ResolveState.initial())?.arityInterval?.maximum

/**
 * The head of [element] where it is a `def`-family clause whose name is unquoted, as a macro's `quote` or an unquote
 * fragment writes it; `null` for any other. A name unquoted from an atom is known, not computed: the decompiler writes
 * so a compiled macro's name that is no identifier, and that clause, where debug info places a head its quote writes,
 * is the macro's own.
 */
@RequiresReadLock
internal fun nameUnquotedHead(element: PsiElement): PsiElement? {
    val clause = (element as? Call)?.takeIf { CallDefinitionClause.`is`(it) } ?: return null
    val head = CallDefinitionClause.head(clause) ?: return null

    return head.takeIf { (CallDefinitionHead.strip(it) as? Call)?.let(Unquote::`is`) == true && headAtomValue(it) == null }
}

/** The definition [this] is one of, as Elixir keys it: its [identity] in the module that declares it, else [this] alone. */
@RequiresReadLock
fun Found.definition(): Found.Definition =
    identity().let { identity -> Found.Definition(identity, declaringModuleName(), takeIf { identity == null }) }

/**
 * What completion offers as [lookupName] at a position. [name] and [declaration] are `null` exactly when the name has
 * no atom value. [importedArities] are the arities of [declaration]'s closed interval that the `import` it was reached
 * through brings in, `null` when no `import` reached it, that `import` brings in every arity of the interval, or the
 * interval is not closed; [declaration]'s arity stays the whole interval. [head] is the head that declares it, where the
 * source gives one: the walk places no head, so gives none.
 */
data class Visible(
    val lookupName: String,
    val name: String?,
    val declaration: Declaration?,
    val element: PsiElement,
    val importedArities: Set<Int>?,
    val head: Head? = null,
)

/** A use of a callable. */
sealed class Use {
    /** A call, used at [arity]: a capture's name is used at the capture's arity. */
    sealed class OfCall : Use() {
        abstract val entrance: Call
        abstract val arity: Int
    }

    /** A use that names [name], an atom value. */
    @ConsistentCopyVisibility
    data class Named internal constructor(override val entrance: Call, val name: String, override val arity: Int) : OfCall()

    /** A qualified `unquote`, which names no declaration but can reach any in the qualifier's module. */
    @ConsistentCopyVisibility
    data class AnyName internal constructor(override val entrance: Call, override val arity: Int) : OfCall()

    /** A `@spec` [head], which names only what its own [modular] declares. */
    @ConsistentCopyVisibility
    data class Specified internal constructor(val head: Call, val modular: Call, val name: String, val arity: Int) : Use()

    /**
     * An MFA [atom] naming [name] in the modules [module] names, from outside them. [arity] `null` is any arity: Elixir
     * checks only a literal one.
     */
    @ConsistentCopyVisibility
    data class Remote internal constructor(
        val atom: ElixirAtom,
        val module: PsiElement,
        val name: String,
        val arity: Int?
    ) : Use()

    /**
     * A HEEx component [tag] named [name], used as a call of one argument from [entrance]: the view's Elixir root, or a
     * module a remote tag's alias names.
     */
    @ConsistentCopyVisibility
    data class Tag internal constructor(val tag: XmlTag, val entrance: PsiElement, val name: String) : Use() {
        val arity: Int get() = 1
    }

    /**
     * A documentation link in [context] to [name], an atom value, used as a call of [arity] arguments from [entrance]:
     * the module the link names, or the one it is written in.
     */
    @ConsistentCopyVisibility
    data class Link internal constructor(
        val context: PsiElement,
        val entrance: PsiElement,
        val name: String,
        val arity: Int
    ) : Use()

    companion object {
        /** The use of the component a HEEx [tag] named [name] names, from [entrance]. */
        @RequiresReadLock
        fun tag(tag: XmlTag, entrance: PsiElement, name: String): Tag = Tag(tag, entrance, name)

        /** The use of the function a documentation link in [context] names, from [entrance]. */
        @RequiresReadLock
        fun link(context: PsiElement, entrance: PsiElement, name: String, arity: Int): Link =
            Link(context, entrance, name, arity)

        /** `null` when [call] names nothing, as when its name has no atom value. */
        @RequiresReadLock
        fun of(call: Call): OfCall? = of(call, Unquote.arity(call))

        /** The use of a capture's [name], `f` or `Mod.f` in `&f/2` or `&Mod.f/2`, at the capture's [arity]. */
        @RequiresReadLock
        fun capture(name: Call, arity: Int): OfCall? = of(name, arity)

        /** The use of the function a `@spec` [head] specifies, `f(t)` in `@spec f(t) :: t`. */
        @RequiresReadLock
        fun specification(head: Call, moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): Specified? {
            ThreadingAssertions.assertReadAccess()

            val modular = CallDefinitionClause.enclosingModularMacroCall(moduleAttribute) ?: return null
            val (name, arity) = CallDefinitionSpecification.typeNameArity(head) ?: return null

            return Specified(head, modular, name, arity)
        }

        /** The use of the function an MFA [atom] names, `:f` in `{M, :f, 1}` or `apply(M, :f, [a])`. */
        @RequiresReadLock
        fun mfa(atom: ElixirAtom): Remote? {
            ThreadingAssertions.assertReadAccess()

            val context = atom.mfaReferenceDecision().context ?: return null
            val name = quotedAtomValue(atom) ?: return null

            return Remote(atom, context.moduleElement, name, context.arity)
        }

        private fun of(call: Call, arity: Int): OfCall? {
            ThreadingAssertions.assertReadAccess()

            val functionName = call.functionName() ?: return null

            return if (call is Qualified && Unquote.isQualified(call, functionName)) {
                AnyName(call, arity)
            } else {
                functionNameAtomValue(call)?.let { Named(call, it, arity) }
            }
        }
    }
}
