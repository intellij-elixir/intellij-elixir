/*
 * Copyright 2012-2014 Sergey Ignatov
 * Copyright 2017 Jake Becker
 * Copyright 2017-2018 Kadie Enheduanna Inanna
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elixir_lang.debugger

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangPid
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.debugger.node.Binding
import org.elixir_lang.debugger.node.TraceElement
import org.elixir_lang.debugger.stack_frame.variable.Elixir
import org.elixir_lang.debugger.stack_frame.variable.Erlang
import org.elixir_lang.utils.ElixirModulesUtil

class StackFrame(
    private val process: Process,
    private val pid: OtpErlangPid,
    private val traceElement: TraceElement,
    wslDistribution: WSLDistribution? = null
) : XStackFrame() {
    private val sourcePosition: SourcePosition? = SourcePosition.create(traceElement, wslDistribution)

    override fun getEvaluator(): XDebuggerEvaluator = Evaluator(
            process,
            pid,
            traceElement.level,
            OtpErlangAtom(traceElement.module),
            traceElement.function,
            traceElement.arguments.size,
            traceElement.file,
            traceElement.line
    )

    override fun getSourcePosition(): XSourcePosition? = sourcePosition?.sourcePosition

    override fun customizePresentation(component: ColoredTextContainer) {
        val elixirModuleName = ElixirModulesUtil.erlangModuleNameToElixir(traceElement.module)
        val lineSuffix = if (traceElement.line > 1) {
            ", line ${traceElement.line}"
        } else {
            ""
        }
        val title = "$elixirModuleName.${InspectAtom.remoteCall(traceElement.function)}/${traceElement.arguments.size}$lineSuffix"

        component.append(title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        val children = XValueChildrenList(traceElement.bindings.size)
        traceElement
                .bindings
                .groupBy(Binding::elixirName)
                .flatMap { (elixirName, bindings) ->
                    val erlangVariables =
                            bindings
                                    .sorted()
                                    .map { binding ->
                                        Erlang(binding.erlangName, binding.value)
                                    }

                    if (elixirName != null) {
                        listOf(Elixir(elixirName, erlangVariables))
                    } else {
                        erlangVariables
                    }
                }
                .forEach { children.add(it) }
        node.addChildren(children, true)
    }
}
