package org.elixir_lang.psi

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.EEx
import org.elixir_lang.Name
import org.elixir_lang.NameArityInterval
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.ElixirClausePresentation
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.nameTextRange
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.mix.Generator
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Callback
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.structure_view.element.Timed
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition

/**
 * Whether a call puts function or macro names in scope, and which names at which arities. Every walker that needs
 * the answer asks here; `CallableDeclarationGuardTest` fails a file that lists the forms itself.
 *
 * Three entry points, nesting `headBindingFormOf ⊆ syntacticFormOf ⊆ formOf`, each answering as much as its caller
 * can afford to ask:
 *
 * - [formOf] - every form, for a caller that may resolve a reference.
 * - [syntacticFormOf] - the forms recognised without resolving, for a caller that may not, stub building above all.
 * - [headBindingFormOf] - the two forms whose head binds parameters, for a caller that needs only those.
 */
object CallableDeclaration {
    enum class Form { CLAUSE, CALLBACK, DELEGATION, EXCEPTION, EEX_FUNCTION_FROM, GENERATOR_EMBED }

    /** @property arityInterval `null` when the call does not say, as for `EEx.function_from_string` given `@args`. */
    data class Declaration(val name: Name, val arityInterval: ArityInterval?) {
        /** An arity the call does not say could be any, so it accepts every arity. */
        fun accepts(arity: Int): Boolean = arityInterval?.let { arity in it } ?: true

        fun nameArityInterval(): NameArityInterval = NameArityInterval(name, arityInterval ?: ArityInterval(0, null))
    }

    /**
     * What a declaration can do. A site asks the capability it means rather than which `def*` wrote the declaration:
     * a guard is expanded at compile time, as a macro is, but evaluates its arguments, as a function does.
     *
     * @property quotesArguments whether a call receives its arguments unevaluated, so a bare variable in them may be
     *   a new binding.
     * @property compileTime whether it is expanded at compile time: a `MACRO-` export, which a caller in another
     *   module must `require`, and which implements a `@macrocallback`.
     * @property usableInGuards whether a guard may call it: a `defguard`, or a compiled definition marked
     *   `guard: true`, as `Kernel.is_atom/1` is.
     * @property visibility `null` when the call does not say, as for an EEx kind that is not a literal atom.
     * @property overridable whether `defoverridable` can name it straight after it is declared.
     */
    data class Capabilities(
        val quotesArguments: Boolean,
        val compileTime: Boolean,
        val usableInGuards: Boolean,
        val visibility: Visibility?,
        val overridable: Boolean
    ) {
        /** Called at run time: by a local call, `apply/3`, a capture or a dispatch by name. */
        val runtimeFunction: Boolean get() = !compileTime

        /** Callable from another module; a visibility the call does not say could be public, so it counts as public. */
        val public: Boolean get() = visibility != Visibility.PRIVATE

        /** What `apply/3` or an MFA tuple reaches from another module. */
        val remoteCallable: Boolean get() = runtimeFunction && public

        val presentation: Presentation
            get() = when {
                quotesArguments -> Presentation.MACRO
                usableInGuards -> Presentation.GUARD
                else -> Presentation.FUNCTION
            }
    }

    /** How a declaration is shown: one a guard may call is shown as a guard, one that quotes its arguments as a macro. */
    enum class Presentation { FUNCTION, MACRO, GUARD }

    /** The `def*` a clause is written with, which alone decides the clause's [Capabilities]; its visibility is always known. */
    enum class Definer(
        val keyword: String,
        quotesArguments: Boolean,
        compileTime: Boolean,
        usableInGuards: Boolean,
        val visibility: Visibility
    ) {
        DEF(Function.DEF, false, false, false, Visibility.PUBLIC),
        DEFP(Function.DEFP, false, false, false, Visibility.PRIVATE),
        DEFMEMO(Function.DEFMEMO, false, false, false, Visibility.PUBLIC),
        DEFMEMOP(Function.DEFMEMOP, false, false, false, Visibility.PRIVATE),
        DEFMACRO(Function.DEFMACRO, true, true, false, Visibility.PUBLIC),
        DEFMACROP(Function.DEFMACROP, true, true, false, Visibility.PRIVATE),
        DEFGUARD(Function.DEFGUARD, false, true, true, Visibility.PUBLIC),
        DEFGUARDP(Function.DEFGUARDP, false, true, true, Visibility.PRIVATE);

        val capabilities = Capabilities(quotesArguments, compileTime, usableInGuards, visibility, overridable = true)

        companion object {
            private val BY_KEYWORD = entries.associateBy { it.keyword }

            fun of(keyword: String): Definer? = BY_KEYWORD[keyword]

            /**
             * The `def*` a clause with [capabilities] is written with, as a compiled definition is shown. A guard-safe
             * function, as `Kernel.is_atom/1` is, is written with `def`.
             */
            fun writing(capabilities: Capabilities): Definer? =
                entries.firstOrNull { definer ->
                    definer.capabilities.compileTime == capabilities.compileTime &&
                        definer.capabilities.public == capabilities.public &&
                        (!capabilities.compileTime || definer.capabilities.usableInGuards == capabilities.usableInGuards)
                }
        }
    }

    /**
     * A declaration classified once, whether a source call or a compiled definition, so its capabilities and what it
     * defines are read without classifying it again.
     */
    sealed class Declared {
        abstract val capabilities: Capabilities?

        @RequiresReadLock
        abstract fun definitions(state: ResolveState): List<Declaration>

        class Source(val call: Call, val form: Form) : Declared() {
            @get:RequiresReadLock
            override val capabilities: Capabilities? get() = capabilitiesOf(call, form)

            override fun definitions(state: ResolveState): List<Declaration> = definitions(call, form, state)
        }

        class Compiled(val definition: BeamCallDefinition) : Declared() {
            @get:RequiresReadLock
            override val capabilities: Capabilities get() = capabilitiesOf(definition)

            override fun definitions(state: ResolveState): List<Declaration> =
                listOf(declaration(definition.nameArityInterval))
        }
    }

    private val HEAD_BINDING = listOf(Form.CLAUSE, Form.DELEGATION)
    private val OTHER_SYNTACTIC = listOf(Form.CALLBACK, Form.EXCEPTION)
    private val RESOLVING = listOf(Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED)

    /** Whether [call] is [form], asking only [form]'s own predicate. The forms are disjoint, so this agrees with [formOf]. */
    @RequiresReadLock
    @JvmOverloads
    fun isForm(call: Call, form: Form, state: ResolveState = ResolveState.initial()): Boolean =
        when (form) {
            Form.CLAUSE -> CallDefinitionClause.`is`(call)
            Form.DELEGATION -> Delegation.`is`(call)
            Form.CALLBACK -> Callback.`is`(call)
            Form.EXCEPTION -> Exception.`is`(call)
            Form.EEX_FUNCTION_FROM -> EEx.isFunctionFrom(call, state)
            Form.GENERATOR_EMBED -> Generator.isEmbed(call, state)
        }

    /** [formOf]'s answer for [call] - `null` meaning it declares nothing - carried in a [ResolveState] as [CLASSIFIED]. */
    class Classified(val call: Call, val form: Form?)

    /** Lets a walker that already classified a call hand the answer to the processor, so it is not classified twice. */
    val CLASSIFIED: Key<Classified> = Key.create("CallableDeclaration.CLASSIFIED")

    private fun classified(call: Call, state: ResolveState): Classified? = state.get(CLASSIFIED)?.takeIf { it.call == call }

    @RequiresReadLock
    fun formOf(call: Call, state: ResolveState): Form? =
        when (val classified = classified(call, state)) {
            null -> syntacticFormOf(call) ?: RESOLVING.firstOrNull { isForm(call, it, state) }
            else -> classified.form
        }

    /** [formOf], restricted to the forms recognised without resolving a reference, so safe during stub building. */
    @RequiresReadLock
    fun syntacticFormOf(call: Call): Form? = headBindingFormOf(call) ?: OTHER_SYNTACTIC.firstOrNull { isForm(call, it) }

    /** The two forms whose head binds parameters. */
    @RequiresReadLock
    fun headBindingFormOf(call: Call): Form? = HEAD_BINDING.firstOrNull { isForm(call, it) }

    /** [headBindingFormOf], read from [CLASSIFIED] when the walk already classified [call]. */
    @RequiresReadLock
    fun headBindingFormOf(call: Call, state: ResolveState): Form? =
        when (val classified = classified(call, state)) {
            null -> headBindingFormOf(call)
            else -> classified.form?.takeIf { it in HEAD_BINDING }
        }

    @RequiresReadLock
    fun declares(call: Call, state: ResolveState): Boolean = formOf(call, state) != null

    /**
     * What [call] defines in its own module: every form's [declarations] but a `@callback`'s, which the implementing
     * module defines - so an `import` does not bring it in and a `@spec` does not name it.
     */
    @RequiresReadLock
    fun definitions(call: Call, state: ResolveState): List<Declaration> = definitions(call, formOf(call, state), state)

    /** [definitions], given [form], [formOf]'s answer for [call]. */
    @RequiresReadLock
    fun definitions(call: Call, form: Form?, state: ResolveState): List<Declaration> =
        when (form) {
            null, Form.CALLBACK -> emptyList()
            Form.CLAUSE, Form.DELEGATION, Form.EXCEPTION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED ->
                declarations(call, form, state)
        }

    /** Each call in [modular]'s module scope that defines something there, with its [definitions], once per change. */
    @RequiresReadLock
    fun definitionsIn(modular: Call): List<Pair<Call, List<Declaration>>> =
        CachedValuesManager.getCachedValue(modular) {
            CachedValueProvider.Result.create(
                CallDefinitionClause.modularChildCalls(modular)
                    .map { it to definitions(it, ResolveState.initial()) }
                    .filter { (_, definitions) -> definitions.isNotEmpty() },
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }

    /** [form] must be [formOf]'s answer for [call]; callers already have it from dispatching on it. */
    @RequiresReadLock
    fun declarations(call: Call, form: Form, state: ResolveState): List<Declaration> =
        when (form) {
            Form.CLAUSE -> listOfNotNull(CallDefinitionClause.functionNameArityInterval(call, state)?.let(::declaration))
            Form.CALLBACK -> listOfNotNull(
                (call as? AtUnqualifiedNoParenthesesCall<*>)
                    ?.let { Callback.headCall(it) }
                    ?.let { CallDefinitionHead.nameArityInterval(it, state) }
                    ?.let(::declaration)
            )
            Form.DELEGATION -> listOfNotNull(
                delegationHead(call)?.let { CallDefinitionHead.nameArityInterval(it, state) }?.let(::declaration)
            )
            Form.EXCEPTION -> Exception.NAME_ARITY_LIST.map { Declaration(it.name, ArityInterval(it.arity, it.arity)) }
            Form.EEX_FUNCTION_FROM -> listOfNotNull(eexFunctionFrom(call))
            Form.GENERATOR_EMBED -> listOfNotNull(generatorEmbed(call))
        }

    /** What [element] - a source call or a compiled definition alike - can do, `null` when it declares nothing. */
    @RequiresReadLock
    fun capabilitiesOf(element: PsiElement, state: ResolveState): Capabilities? = declaredOf(element, state)?.capabilities

    /** Whether [element] declares a macro, or anything else only callable at compile time. */
    @RequiresReadLock
    fun isCompileTime(element: PsiElement): Boolean = capabilitiesOf(element, ResolveState.initial())?.compileTime == true

    /**
     * Whether [element] defines [name]/[arity], a macro if [compileTime] - as what implements a callback or protocol
     * function, or handles a message, does.
     */
    @RequiresReadLock
    fun defines(element: PsiElement, name: String, arity: Int, compileTime: Boolean): Boolean {
        val state = ResolveState.initial()
        val declared = declaredOf(element, state) ?: return false

        return declared.capabilities?.compileTime == compileTime &&
            declared.definitions(state).any { it.name == name && it.accepts(arity) }
    }

    /**
     * [element] classified once - a source call or a compiled definition alike - `null` when it declares nothing. The
     * one question every site asks, whatever a reference resolved to.
     */
    @RequiresReadLock
    fun declaredOf(element: PsiElement, state: ResolveState): Declared? =
        when (element) {
            is Call -> formOf(element, state)?.let { Declared.Source(element, it) }
            is BeamCallDefinition -> Declared.Compiled(element)
            else -> null
        }

    /**
     * The [Definer] of a clause, `null` when [call] is none. `CallDefinitionClause.is` is this, so it resolves
     * nothing and is safe during stub building.
     */
    @RequiresReadLock
    fun definerOf(call: Call): Definer? =
        call.functionName()?.let { Definer.of(it) }?.takeIf { CallDefinitionClause.isCallingDefiner(call, it.keyword) }

    /** Its export says whether it is a macro, and its stub whether its docs mark it `guard: true`. */
    private fun capabilitiesOf(definition: BeamCallDefinition): Capabilities {
        val compileTime = definition.time == Timed.Time.COMPILE

        return Capabilities(
            quotesArguments = compileTime && !definition.isGuard,
            compileTime = compileTime,
            usableInGuards = definition.isGuard,
            visibility = if (definition.isExported) Visibility.PUBLIC else Visibility.PRIVATE,
            overridable = false
        )
    }

    private fun capabilitiesOf(call: Call, form: Form): Capabilities? =
        when (form) {
            Form.CLAUSE -> definerOf(call)?.capabilities
            Form.CALLBACK ->
                if (Callback.Kind.of(call) == Callback.Kind.MACROCALLBACK) {
                    Capabilities(true, true, false, Visibility.PUBLIC, false)
                } else {
                    Capabilities(false, false, false, Visibility.PUBLIC, false)
                }
            Form.DELEGATION -> Capabilities(false, false, false, Visibility.PUBLIC, true)
            // `defoverridable` straight after `defexception` finds its functions not yet defined.
            Form.EXCEPTION -> Capabilities(false, false, false, Visibility.PUBLIC, false)
            Form.EEX_FUNCTION_FROM -> Capabilities(false, false, false, EEx.visibility(call), true)
            Form.GENERATOR_EMBED -> Capabilities(false, false, false, Visibility.PRIVATE, true)
        }

    /**
     * The element spelling the name [call] declares - a clause's or `defdelegate` head's identifier, an EEx function's
     * name atom - or `null` for a form with no one name of its own. A declared name's range, pointer and highlight
     * are all read from it.
     */
    @RequiresReadLock
    @JvmOverloads
    fun nameElement(call: Call, state: ResolveState = ResolveState.initial()): PsiElement? =
        formOf(call, state)?.let { nameElement(call, it) }

    private fun nameElement(call: Call, form: Form): PsiElement? =
        when (form) {
            Form.CLAUSE -> CallDefinitionClause.nameIdentifier(call)
            Form.DELEGATION -> Delegation.nameIdentifier(call)
            Form.EEX_FUNCTION_FROM -> EEx.declaredNameAtom(call)
            Form.CALLBACK, Form.EXCEPTION, Form.GENERATOR_EMBED -> null
        }

    /**
     * What `getNameIdentifier` returns for a declaring call. It is asked while stubs are built, so it knows only the
     * forms recognised without resolving: [nameElement] where that has an answer, a `@callback`'s head name, and the
     * call's own name for `defexception`.
     */
    @RequiresReadLock
    fun syntacticNameIdentifier(call: Call): PsiElement? =
        when (val form = syntacticFormOf(call)) {
            // an EEx function or embed needs resolving to be told apart, so `syntacticFormOf` never names one
            null, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> null
            Form.CLAUSE, Form.DELEGATION -> nameElement(call, form)
            Form.CALLBACK -> org.elixir_lang.structure_view.element.Callback.nameIdentifier(call)
            Form.EXCEPTION -> call.functionNameElement()
        }

    /** The declaration whose [nameElement] spells the name at [range] in [file], `null` when none does. */
    @RequiresReadLock
    fun declarationNamedAt(file: PsiFile, range: TextRange): Call? =
        generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { call -> nameElement(call)?.let(::nameTextRange) == range }

    /**
     * How [call] reads in a label: its `def*` and head, as `defdelegate name(a)`, and an EEx function as the `def` it
     * compiles to; `null` for a form with no head, or an EEx function whose kind or arguments are not literal.
     */
    @RequiresReadLock
    fun label(call: Call): String? =
        when (formOf(call, ResolveState.initial())) {
            Form.CLAUSE, Form.DELEGATION -> ElixirClausePresentation.elementText(call)
            Form.EEX_FUNCTION_FROM -> eexLabel(call)
            else -> null
        }

    private fun eexLabel(call: Call): String? {
        val kind = EEx.kind(call) ?: return null
        val name = EEx.declaredName(call) ?: return null
        val parameters = EEx.argumentList(call)
            ?.map { (it.stripAccessExpression() as? ElixirAtom)?.literalName() ?: return null }
            ?: return null

        return "$kind $name(${parameters.joinToString(", ")})"
    }

    /** The `defdelegate` whose head [call] is, `null` when it heads none: a head is a declaration, not a call. */
    @RequiresReadLock
    fun delegationHeadedBy(call: Call): Call? =
        com.intellij.psi.util.PsiTreeUtil.getParentOfType(call, Call::class.java)
            ?.takeIf { isForm(it, Form.DELEGATION) }
            ?.takeIf { delegation ->
                delegationHead(delegation)?.let { org.elixir_lang.structure_view.element.CallDefinitionHead.strip(it) } == call
            }

    /** The `as:` value of a `defdelegate`, `null` when it has none. */
    @RequiresReadLock
    fun delegationAsValue(delegation: Call): PsiElement? = delegation.keywordArgument("as")?.stripAccessExpression()

    /** The `as:` atom of a `defdelegate`, naming what it delegates to when that is not its own name. */
    @RequiresReadLock
    fun delegationAs(delegation: Call): ElixirAtom? = delegationAsValue(delegation) as? ElixirAtom

    /**
     * The name a `defdelegate` headed [headName] calls in its `to:` module: its `as:`, or its own name when it has none;
     * `null` when `as:` names nothing fixed.
     */
    @RequiresReadLock
    fun delegatedName(delegation: Call, headName: String): String? =
        when (val value = delegationAsValue(delegation)) {
            null -> headName
            else -> (value as? ElixirAtom)?.literalName()
        }

    /**
     * Where a `defdelegate` without `as:` takes one: straight after its last option, inside the list when the options
     * are one, as `defdelegate/2` takes no third argument, so a trailing comma or line break stays after it.
     */
    @RequiresReadLock
    fun delegationAsOffset(delegation: Call): Int? {
        val options = delegation.finalArguments()?.lastOrNull() ?: return null

        return when (val stripped = options.stripAccessExpression()) {
            is ElixirList -> stripped.children.lastOrNull()
                ?.let { (it as? QuotableKeywordList)?.quotableKeywordPairList()?.lastOrNull() ?: it }
                ?.textRange?.endOffset
            else -> options.textRange?.endOffset
        }
    }

    /** The one head of a `defdelegate`; a list of heads declares nothing here yet (#4040). */
    @RequiresReadLock
    fun delegationHead(call: Call): PsiElement? = call.finalArguments()?.takeIf { it.size == 2 }?.first()

    private fun declaration(nameArityInterval: NameArityInterval): Declaration =
        Declaration(nameArityInterval.name, nameArityInterval.arityInterval)

    private fun eexFunctionFrom(call: Call): Declaration? =
        EEx.declaredName(call)?.let { name ->
            Declaration(name, EEx.argumentList(call)?.size?.let { arity -> ArityInterval(arity, arity) })
        }

    private fun generatorEmbed(call: Call): Declaration? =
        Generator.Embed.of(call)?.let { embed ->
            call.finalArguments()?.firstOrNull()?.stripAccessExpression()?.let { it as? ElixirAtom }?.literalName()?.let { prefix ->
                val arity = embed.parameters.size

                Declaration("${prefix}_${embed.suffix}", ArityInterval(arity, arity))
            }
        }
}
