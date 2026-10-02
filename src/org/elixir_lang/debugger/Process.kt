/*
 * Copyright 2012-2015 Sergey Ignatov
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
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.elixir_lang.ElixirFileType
import org.elixir_lang.beam.chunk.lines.file_names.Index
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.debugger.configuration.Debuggable
import org.elixir_lang.debugger.configuration.doNotInterpretPatterns
import org.elixir_lang.debugger.line_breakpoint.Handler
import org.elixir_lang.debugger.line_breakpoint.Properties
import org.elixir_lang.debugger.node.ProcessSnapshot
import org.elixir_lang.debugger.node.event.Listener
import org.elixir_lang.debugger.node.ok_error_reason.ErrorReason
import org.elixir_lang.debugger.node.ok_error_reason.OK
import org.elixir_lang.run.Configuration
import org.elixir_lang.run.ensureWorkingDirectory
import org.elixir_lang.util.ElixirCoroutineService
import org.elixir_lang.util.supervisedChildScope
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime
import org.elixir_lang.debugger.node.Exception as NodeException

class Process(session: XDebugSession, private val executionEnvironment: ExecutionEnvironment) :
    XDebugProcess(session), Listener {
    companion object {
        private val LOG = logger<Process>()
    }

    private val projectCoroutineService = session.project.service<ElixirCoroutineService>()

    private val scope: CoroutineScope =
        projectCoroutineService.supervisedChildScope("DebuggerProcess")

    /**
     * Serial dispatcher for all operations that touch [Node] (breakpoint set/remove, interpret, attach).
     * `limitedParallelism(1)` ensures ordering and prevents concurrent network calls to the BEAM node.
     */
    private val nodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Cached WSL distribution for the project, used for converting Linux file paths
     * reported by the Erlang VM back to Windows UNC paths for source navigation.
     * `null` for non-WSL projects (fast path - no overhead).
     */
    internal val wslDistribution: WSLDistribution? by lazy {
        org.elixir_lang.sdk.wsl.wslCompat.getDistributionByWindowsUncPath(session.project.basePath)
    }

    init {
        session.setPauseActionSupported(false)
    }

    override fun createConsole(): ExecutionConsole = debuggedExecutionResult.executionConsole
    override fun createTabLayouter(): TabLayouter = tabLayouter

    private val tabLayouter by lazy {
        TabLayouter(node)
    }

    private fun sourcePosition(breakpoint: XLineBreakpoint<Properties>): SourcePosition? =
        breakpoint.sourcePosition?.let { SourcePosition.create(it) }

    private val breakpointHandlers = arrayOf<XBreakpointHandler<*>>(Handler(this))

    private val debuggedExecutionResult by lazy {
        executionEnvironment.executor.let { executor ->
            debuggableConfiguration
                .debuggedConfiguration(debuggedName, cookie)
                .getState(executor, executionEnvironment)!!
                .execute(executor, executionEnvironment.runner)!!
        }
    }

    private val nodesUUID by lazy { UUID.randomUUID()!! }
    private val cookie by lazy { debuggableConfiguration.cookie ?: nodesUUID.toString() }
    private val debuggedName by lazy { debuggableConfiguration.nodeName ?: "debugged$nodesUUID@127.0.0.1" }
    private val debuggerName by lazy { "debugger$nodesUUID@127.0.0.1" }

    private val nodeDelegate = lazy {
        try {
            //TODO add the debugger node to disposable hierarchy (we may fail to initialize session so the session will not be stopped!)
            Node(
                debuggerName,
                debuggedName,
                cookie,
                { debuggedExecutionResult },
                this,
                scope.supervisedChildScope("DebuggerNode")
            )
        } catch (e: NodeException) {
            session.reportError(e.message ?: "Exception creating JInterface node")
            session.stop()
            throw ExecutionException(e)
        }
    }
    private val node by nodeDelegate

    private val debuggableConfiguration: Debuggable<*>
        get() = session.runProfile as Debuggable<*>

    private val sourcePositionToBreakpoint = ConcurrentHashMap<SourcePosition, XLineBreakpoint<Properties>>()

    fun addBreakpoint(breakpoint: XLineBreakpoint<Properties>) {
        sourcePosition(breakpoint)?.let { sourcePosition ->
            val moduleAtoms = moduleAtoms(moduleNameSet(sourcePosition))

            if (moduleAtoms.isNotEmpty()) {
                sourcePositionToBreakpoint[sourcePosition] = breakpoint

                for (moduleAtom in moduleAtoms) {
                    moduleAtom
                        .let(::OtpErlangAtom)
                        .run {
                            afterInitialized { addBreakpoint(breakpoint, sourcePosition, this) }
                        }
                }
            } else {
                session.reportMessage(
                    "Unable to determine module for breakpoint at ${sourcePosition.file} line ${sourcePosition.line}",
                    MessageType.ERROR
                )
            }
        }
    }

    private fun afterInitialized(task: () -> Unit) {
        if (initialized.get()) {
            scope.launch(nodeDispatcher) {
                try {
                    task()
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception

                    session.reportError(exception.message ?: exception.toString())
                }
            }
        } else {
            initializers.add(task)
        }
    }

    private fun addBreakpoint(
        breakpoint: XLineBreakpoint<Properties>,
        sourcePosition: SourcePosition,
        module: OtpErlangAtom
    ) {
        try {
            val response = node.setBreakpoint(module, sourcePositionLineToModuleLine(sourcePosition.line))

            when (response) {
                OK -> session.reportMessage(
                    "Breakpoint at ${sourcePosition.file}:${sourcePosition.line} set",
                    MessageType.INFO
                )
                is ErrorReason -> session.updateBreakpointPresentation(breakpoint, null, inspect(response.reason))
            }
        } catch (exception: Exception) {
            session.updateBreakpointPresentation(breakpoint, null, exception.message)
        }
    }

    // sourcePosition.line is 0-based, but `:int` lines are 1-based
    private fun sourcePositionLineToModuleLine(sourcePositionLine: Int) = sourcePositionLine + 1

    /**
     * [sourcePositionToBreakpoint] records file:line<->breakpoint correspondence, so this is no-op.
     */
    override fun breakpointIsSet(module: String, file: String, line: Int) {}

    override fun breakpointReached(pid: OtpErlangPid, snapshots: List<ProcessSnapshot>) {
        val processInBreakpoint =
            ContainerUtil.find(snapshots) { elixirProcessSnapshot -> elixirProcessSnapshot.pid == pid }!!
        val breakPosition = SourcePosition.create(processInBreakpoint, wslDistribution)
        val breakpoint = getLineBreakpoint(breakPosition)
        val suspendContext = SuspendContext(this, pid, snapshots)
        if (breakpoint == null) {
            session.positionReached(suspendContext)
        } else {
            val shouldSuspend = session.breakpointReached(breakpoint, null, suspendContext)
            if (!shouldSuspend) {
                resume(suspendContext)
            }
        }
    }

    override fun debuggerStarted() {
        session.reportMessage("Debug process started", MessageType.INFO)
    }

    override fun debuggerStopped() {
        session.reportMessage("Debug process stopped", MessageType.INFO)
        session.stop()
    }

    override fun doGetProcessHandler(): ProcessHandler = debuggedExecutionResult.processHandler

    override fun failedToDebugRemoteNode(nodeName: String, error: OtpErlangObject) {
        session.reportMessage(
            "Failed to debug remote node '$nodeName'. Details: ${inspect(error)}",
            MessageType.ERROR
        )
    }

    override fun failedToInterpretModules(
        nodeName: String,
        errorReasonByModule: Map<String, OtpErlangObject>
    ) {
        val stringBuilder = StringBuilder("Failed to interpret modules on node (")
            .append(nodeName)
            .append("):\n\n")

        for ((key, value) in errorReasonByModule) {
            stringBuilder.append(key).append(": ").append(inspect(value)).append("\n\n")
        }

        stringBuilder.append(
            "Make sure they are compiled with debug_info option, their sources are located in same directory as " + ".beam files, modules are available on the node."
        )

        session.reportMessage(stringBuilder.toString(), MessageType.WARNING)
    }

    override fun failedToSetBreakpoint(
        module: String,
        file: String,
        line: Int,
        errorMessage: OtpErlangObject
    ) {
        val sourcePosition = SourcePosition.create(file, line, wslDistribution)
        val breakpoint = getLineBreakpoint(sourcePosition)

        if (breakpoint != null) {
            session.updateBreakpointPresentation(
                breakpoint,
                AllIcons.Debugger.Db_invalid_breakpoint,
                inspect(errorMessage)
            )
        }

        session.reportMessage(
            "Failed to set breakpoint. Module: " + module + " Line: " + (line + 1),
            MessageType.WARNING
        )
    }

    override fun interpretedModules(interpretedModuleList: List<InterpretedModule>) {
        tabLayouter.interpretedModules(interpretedModuleList)
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = breakpointHandlers

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return object : XDebuggerEditorsProvider() {
            override fun createDocument(
                project: Project,
                expression: XExpression,
                sourcePosition: XSourcePosition?,
                mode: EvaluationMode
            ): Document {
                val file = LightVirtualFile("plain-text-elixir-debugger.txt", expression.expression)

                return FileDocumentManager.getInstance().getDocument(file)!!
            }

            override fun getFileType(): FileType {
                return ElixirFileType.INSTANCE
            }
        }
    }

    private fun getLineBreakpoint(sourcePosition: SourcePosition?): XLineBreakpoint<Properties>? =
        if (sourcePosition != null) sourcePositionToBreakpoint[sourcePosition] else null

    private fun moduleNameSet(breakpointPosition: SourcePosition): Set<String> {
        val virtualFile = breakpointPosition.file
        val project = debuggableConfiguration.getProject()

        return breakpointModuleNames(project, virtualFile, breakpointPosition.sourcePosition.offset) ?: PsiManager
            .getInstance(project)
            .findFile(virtualFile)
            ?.let { psiFile ->
                when (psiFile) {
                    is org.elixir_lang.eex.File -> {
                        val module = ModuleUtilCore.findModuleForPsiElement(psiFile)
                        val rootDirectory = ensureWorkingDirectory(project, module)

                        val path = virtualFile.path
                        try {
                            java.io.File(path).relativeTo(
                                java.io.File(rootDirectory)
                            )
                        } catch (_: IllegalArgumentException) {
                            null
                        }?.let { relativeFile ->
                            val filename = relativeFile.path

                            FileBasedIndex
                                .getInstance()
                                .getContainingFiles(
                                    Index.NAME,
                                    filename,
                                    GlobalSearchScope.allScope(project)
                                )
                                .map { fileNameVirtualFile -> beamFileModuleName(fileNameVirtualFile.name) }
                                .toSet()
                        } ?: emptySet()
                    }
                    else -> emptySet()
                }
            } ?: emptySet()
    }

    fun removeBreakpoint(breakpoint: XLineBreakpoint<Properties>) {
        sourcePosition(breakpoint)?.let { breakpointPosition ->
            sourcePositionToBreakpoint.remove(breakpointPosition)

            val moduleAtoms = moduleAtoms(moduleNameSet(breakpointPosition))
            val line = breakpointPosition.line

            scope.launch(nodeDispatcher) {
                try {
                    moduleAtoms.forEach { moduleAtom ->
                        moduleAtom
                            .let(::OtpErlangAtom)
                            .let { node.removeBreakpoint(it, line) }
                    }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception

                    session.reportError(exception.message ?: exception.toString())
                }
            }
        }
    }

    override fun resume(context: XSuspendContext?) {
        scope.launch(nodeDispatcher) { node.resume() }
    }

    private val initialized = AtomicBoolean(false)
    private val initializers = ConcurrentLinkedQueue<() -> Unit>()

    override fun sessionInitialized() {
        afterInitialized {
            session.reportMessage("Interpreting modules... ", MessageType.INFO)

            val interpretationSeconds = measureNanoTime {
                val sdkPaths = ReadAction.nonBlocking(Callable {
                    debuggableConfiguration.let { it as Configuration }.sdkPaths()
                }).executeSynchronously()

                node.interpret(
                    sdkPaths,
                    debuggableConfiguration.doNotInterpretPatterns()
                )
            }.let { TimeUnit.NANOSECONDS.toSeconds(it) }

            session.reportMessage("... completed ($interpretationSeconds seconds)", MessageType.INFO)
        }
        afterInitialized {
            node.attach()
            session.reportMessage("Attached to Elixir node", MessageType.INFO)
        }

        asyncRunInitializers()
    }

    private fun asyncRunInitializers() {
        scope.launch(nodeDispatcher) {
            try {
                runInitializers()

                initialized.set(true)

                // run anything inserted between first runInitializers and above line
                runInitializers()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception

                session.reportError(exception.message ?: exception.toString())
                session.stop()
            }
        }
    }

    private fun runInitializers() {
        while (!initializers.isEmpty()) {
            val initializer = initializers.poll()

            if (initializer != null) {
                try {
                    initializer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.error("Debugger initializer failed", e)
                    throw e
                }
            }
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        scope.launch(nodeDispatcher) { node.stepInto() }
    }

    override fun startStepOut(context: XSuspendContext?) {
        scope.launch(nodeDispatcher) { node.stepOut() }
    }

    override fun startStepOver(context: XSuspendContext?) {
        scope.launch(nodeDispatcher) { node.stepOver() }
    }

    override fun stop() {
        if (nodeIsInitialized()) {
            node.stop()
        }

        scope.cancel()
    }

    private fun nodeIsInitialized(): Boolean = nodeDelegate.isInitialized()

    override fun unknownMessage(messageText: String) {
        session.reportMessage("Unknown message received: $messageText", MessageType.WARNING)
        session.stop()
    }

    fun evaluate(
        pid: OtpErlangPid,
        stackPointer: Int,
        module: OtpErlangAtom,
        function: String,
        arity: Int,
        file: String,
        line: Int,
        expression: String,
        callback: XDebuggerEvaluator.XEvaluationCallback
    ) {
        scope.launch(nodeDispatcher) {
            node.evaluate(pid, stackPointer, module, function, arity, file, line, expression, callback)
        }
    }
}
