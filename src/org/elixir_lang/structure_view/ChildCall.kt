package org.elixir_lang.structure_view

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.operation.Or
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import org.elixir_lang.structure_view.element.Callback
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.structure_view.element.EExFunctionFrom
import org.elixir_lang.structure_view.element.Exception
import org.elixir_lang.structure_view.element.Overridable
import org.elixir_lang.structure_view.element.Quote
import org.elixir_lang.structure_view.element.Type
import org.elixir_lang.structure_view.element.Use
import org.elixir_lang.structure_view.element.call_definition_by_name_arity.FunctionByNameArity
import org.elixir_lang.structure_view.element.call_definition_by_name_arity.MacroByNameArity
import org.elixir_lang.structure_view.element.ex_unit.case.Describe
import org.elixir_lang.structure_view.element.ex_unit.case.Test
import org.elixir_lang.structure_view.element.modular.Implementation
import org.elixir_lang.structure_view.element.modular.Modular
import org.elixir_lang.structure_view.element.modular.Module
import org.elixir_lang.structure_view.element.modular.Protocol
import org.elixir_lang.structure_view.element.modular.Unknown
import org.elixir_lang.structure_view.element.structure.Structure

/**
 * Every kind of call the structure view models as a child of a modular, in one ordered list, which
 * [Module.callChildren] and [Model.isSuitable] both derive from.
 *
 * Scope is this dispatch only. `File.getChildren` and [org.elixir_lang.structure_view.element.Body]
 * build tree nodes from their own lists, so a call one of those misses can still be claimed by
 * [Model.isSuitable] - a `use Foo` inside a `def`.
 *
 * Order decides which entry wins for a call several match. [Unknown] is last because `Unknown.is` is
 * `hasDoBlockOrKeyword()`, which most entries above it also satisfy; it is not a catch-all, and a call
 * matching no entry builds nothing.
 */
object ChildCall {
    class Accumulator(
        val modular: Modular,
        val treeElementList: MutableList<TreeElement>,
        val functionByNameArity: FunctionByNameArity,
        val macroByNameArity: MacroByNameArity,
        val overridableSet: MutableSet<Overridable>,
        val useSet: MutableSet<Use>,
        val childCallQueue: java.util.Queue<Call>
    )

    /**
     * @param handle `null` for an entry [Model.isSuitable] claims but this dispatch never builds; such
     *   an entry is skipped here rather than swallowing the call.
     * @param suitable a non-inclusion, not an exclusion - [Model.isSuitable] asks whether *any*
     *   suitable entry matches.
     */
    class Entry(
        val name: String,
        val matches: (Call, ResolveState) -> Boolean,
        val handle: ((Accumulator, Call, ResolveState) -> Unit)?,
        val suitable: Boolean = true
    )

    val ENTRIES: List<Entry> = listOf(
        Entry(
            "or",
            matches = { call, _ -> call is Or },
            suitable = false,
            handle = { accumulator, call, _ -> accumulator.childCallQueue.addAll(orOperandCalls(call as Or)) }
        ),
        Entry(
            "declaration",
            matches = { call, state -> CallableDeclaration.formOf(call, state) != null },
            handle = ::declaration
        ),
        Entry(
            "specification",
            matches = { call, _ -> CallDefinitionSpecification.`is`(call) },
            handle = { accumulator, call, _ -> accumulator.functionByNameArity.addSpecificationToCallDefinition(call) }
        ),
        Entry(
            "implementation",
            matches = { call, _ -> org.elixir_lang.psi.Implementation.`is`(call) },
            handle = { accumulator, call, _ ->
                accumulator.treeElementList.add(Implementation(accumulator.modular, call))
            }
        ),
        Entry(
            "module",
            matches = { call, _ -> org.elixir_lang.psi.Module.`is`(call) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Module(accumulator.modular, call)) }
        ),
        Entry(
            "overridable",
            matches = { call, _ -> Overridable.`is`(call) },
            handle = { accumulator, call, _ ->
                val overridable = Overridable(accumulator.modular, call)
                accumulator.overridableSet.add(overridable)
                accumulator.treeElementList.add(overridable)
            }
        ),
        Entry(
            "protocol",
            matches = { call, _ -> org.elixir_lang.psi.Protocol.`is`(call) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Protocol(accumulator.modular, call)) }
        ),
        Entry(
            "quote",
            matches = { call, _ -> org.elixir_lang.psi.QuoteMacro.`is`(call) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Quote(accumulator.modular, call)) }
        ),
        Entry(
            "structure",
            matches = { call, _ -> Structure.`is`(call) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Structure(call)) }
        ),
        Entry(
            "type",
            matches = { call, _ -> Type.`is`(call) },
            handle = { accumulator, call, _ ->
                Type.fromCall(accumulator.modular, call)?.let { accumulator.treeElementList.add(it) }
            }
        ),
        Entry(
            "use",
            matches = { call, _ -> org.elixir_lang.psi.Use.`is`(call) },
            handle = { accumulator, call, _ ->
                val use = Use(accumulator.modular, call)
                accumulator.useSet.add(use)
                accumulator.treeElementList.add(use)
            }
        ),
        Entry(
            "ex_unit describe",
            matches = { call, _ -> org.elixir_lang.psi.ex_unit.Case.isDescribe(call, ResolveState.initial()) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Describe(accumulator.modular, call)) }
        ),
        Entry(
            "ex_unit test",
            matches = { call, _ -> org.elixir_lang.psi.ex_unit.Case.isTest(call, ResolveState.initial()) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Test(accumulator.modular, call)) }
        ),
        Entry(
            "call definition head",
            matches = { call, _ -> CallDefinitionHead.`is`(call) },
            handle = null
        ),
        Entry(
            "unknown",
            matches = { call, _ -> Unknown.`is`(call) },
            handle = { accumulator, call, _ -> accumulator.treeElementList.add(Unknown(accumulator.modular, call)) }
        )
    )

    /**
     * Entries with no handler are invisible here, so a call one of them claims still falls through to
     * a later entry that builds.
     */
    @RequiresReadLock
    fun build(accumulator: Accumulator, call: Call, resolveState: ResolveState) {
        ThreadingAssertions.assertReadAccess()

        ENTRIES
            .firstOrNull { entry -> entry.handle != null && entry.matches(call, resolveState) }
            ?.let { entry -> entry.handle!!(accumulator, call, resolveState) }
    }

    @RequiresReadLock
    fun isSuitable(call: Call, resolveState: ResolveState): Boolean {
        ThreadingAssertions.assertReadAccess()

        return ENTRIES.any { entry -> entry.suitable && entry.matches(call, resolveState) }
    }

    /** Every declaring form, so a new one fails to compile here until it is shown. */
    private fun declaration(accumulator: Accumulator, call: Call, state: ResolveState) {
        when (CallableDeclaration.formOf(call, state)) {
            null -> Unit
            CallableDeclaration.Form.CLAUSE ->
                if (CallableDeclaration.isCompileTime(call)) {
                    accumulator.macroByNameArity.addClausesToCallDefinition(call)
                } else {
                    accumulator.functionByNameArity.addClausesToCallDefinition(call)
                }
            CallableDeclaration.Form.CALLBACK ->
                Callback.fromCall(accumulator.modular, call)?.let { accumulator.treeElementList.add(it) }
            CallableDeclaration.Form.DELEGATION -> accumulator.functionByNameArity.addDelegationToTreeElementList(call)
            CallableDeclaration.Form.EXCEPTION -> accumulator.functionByNameArity.exception = Exception(call)
            CallableDeclaration.Form.EEX_FUNCTION_FROM, CallableDeclaration.Form.GENERATOR_EMBED ->
                accumulator.functionByNameArity.addEExFunctionFromToCallDefinition(EExFunctionFrom(accumulator.modular, call))
        }
    }

    private fun orOperandCalls(or: Or): List<Call> =
        listOfNotNull(or.leftOperand() as? Call, or.rightOperand() as? Call)
}
