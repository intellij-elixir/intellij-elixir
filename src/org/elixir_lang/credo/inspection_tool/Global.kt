package org.elixir_lang.credo.inspection_tool

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.*
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.elixir_lang.Mix
import org.elixir_lang.credo.Action
import org.elixir_lang.isElixirMixModule
import org.elixir_lang.jps.shared.ParametersList
import org.elixir_lang.mix.Project
import org.elixir_lang.notification.setup_sdk.sdkOrNotify
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.concurrent.Callable

// Phase 2 location parsing: try the most specific location shape first to avoid path/line ambiguity.
// lib/level_web/ui/empty_state.ex:1:11: R: Modules should have a @moduledoc tag.
private val FLYCHECK_LINE_COLUMN_LOCATION_REGEX =
    Regex("(?<path>.+):(?<line>\\d+):(?<column>\\d+)")

// lib/level_web/ui/empty_state.ex:1: W: Prefer explicit module aliases
private val FLYCHECK_LINE_LOCATION_REGEX =
    Regex("(?<path>.+):(?<line>\\d+)")

// Phase 1 record parsing: split "location" from "tag/message" before parsing the location itself.
// apps/smoke_test/lib/smoke_test.ex: W: Test files should end with .exs
private val FLYCHECK_RECORD_REGEX =
    Regex("(?<location>.+): (?<tag>[A-Z]): (?<message>.+)")

internal data class FlycheckFinding(
    val path: String,
    val line: Int?,
    val column: Int?,
    val message: String,
)

internal fun parseFlycheckFinding(line: String): FlycheckFinding? {
    val recordMatch = FLYCHECK_RECORD_REGEX.matchEntire(line) ?: return null
    val recordGroups = recordMatch.groups as MatchNamedGroupCollection
    val location = recordGroups["location"]!!.value
    val message = recordGroups["message"]!!.value

    val lineColumnLocationMatch = FLYCHECK_LINE_COLUMN_LOCATION_REGEX.matchEntire(location)

    if (lineColumnLocationMatch != null) {
        val groups = lineColumnLocationMatch.groups as MatchNamedGroupCollection

        return FlycheckFinding(
            path = groups["path"]!!.value,
            line = groups["line"]!!.value.toInt(),
            column = groups["column"]!!.value.toInt(),
            message = message,
        )
    }

    val lineLocationMatch = FLYCHECK_LINE_LOCATION_REGEX.matchEntire(location)

    if (lineLocationMatch != null) {
        val groups = lineLocationMatch.groups as MatchNamedGroupCollection

        return FlycheckFinding(
            path = groups["path"]!!.value,
            line = groups["line"]!!.value.toInt(),
            column = null,
            message = message,
        )
    }

    return FlycheckFinding(
        path = location,
        line = null,
        column = null,
        message = message,
    )
}

private val LOG = logger<Global>()

internal class Global : GlobalInspectionTool() {
    private data class CredoRunOutcome(
        val findingCount: Int,
        val failureSummary: String? = null,
        val warningSummary: String? = null,
    )

    private data class CredoExecutionIssue(
        val module: Module,
        val workingDirectory: String,
        val summary: String,
    )

    override fun runInspection(
        scope: AnalysisScope,
        manager: InspectionManager,
        globalContext: GlobalInspectionContext,
        problemDescriptionsProcessor: ProblemDescriptionsProcessor,
    ) {
        // Save all modified documents before launching mix credo so the external process
        // sees the current file contents.  Same reasoning as dialyzer/inspection/Global.kt:
        // runInspection runs on a background thread (isReadActionNeeded = false);
        // saveAllDocuments() requires EDT; invokeAndWait is the correct bridge for
        // non-suspend blocking code.  ModalityState.nonModal() ensures the save is not
        // deferred by an open dialog.
        ApplicationManager.getApplication().invokeAndWait(
            { FileDocumentManager.getInstance().saveAllDocuments() },
            ModalityState.nonModal()
        )

        val project = scope.project
        val scopedModules = ApplicationManager.getApplication().runReadAction<List<Module>> {
            ModuleManager.getInstance(project).modules.filter { scope.containsModule(it) && it.isElixirMixModule() }
        }
        val moduleByWorkingDirectory = linkedMapOf<String, Module>()
        val executionFailures = mutableListOf<CredoExecutionIssue>()
        val executionWarnings = mutableListOf<CredoExecutionIssue>()

        for (module in scopedModules) {
            ProgressManager.checkCanceled()

            for (workingDirectory in workingDirectorySet(module)) {
                moduleByWorkingDirectory.putIfAbsent(workingDirectory, module)
            }
        }

        val selectedWorkingDirectories = mutableListOf<Pair<Module, String>>()

        // Multiple modules can collapse to the same umbrella root; run Credo once per root.
        for ((workingDirectory, module) in moduleByWorkingDirectory.entries.sortedBy { it.key.length }) {
            if (selectedWorkingDirectories.none { isSameOrAncestorPath(it.second, workingDirectory) }) {
                selectedWorkingDirectories.add(module to workingDirectory)
            }
        }

        for ((module, workingDirectory) in selectedWorkingDirectories) {
            ProgressManager.checkCanceled()

            val outcome = runWorkingDirectoryInspection(
                manager,
                globalContext,
                problemDescriptionsProcessor,
                module,
                workingDirectory
            )

            outcome.failureSummary?.let { summary ->
                executionFailures.add(CredoExecutionIssue(module, workingDirectory, summary))
            }

            outcome.warningSummary?.let { summary ->
                executionWarnings.add(CredoExecutionIssue(module, workingDirectory, summary))
            }
        }

        // Surface external-tool failures in inspection results to avoid misleading "nothing to report" UX.
        for (failure in executionFailures) {
            registerCredoExecutionFailureProblem(
                manager,
                globalContext,
                problemDescriptionsProcessor,
                failure
            )
        }

        if (executionFailures.isNotEmpty()) {
            notifyCredoIssue(
                project,
                title = "Credo inspection failed in ${executionFailures.size} working director${if (executionFailures.size == 1) "y" else "ies"}",
                message = buildCredoIssueMessage(executionFailures),
                type = NotificationType.ERROR
            )
        } else if (executionWarnings.isNotEmpty()) {
            notifyCredoIssue(
                project,
                title = "Credo reported execution warnings",
                message = buildCredoIssueMessage(executionWarnings),
                type = NotificationType.WARNING
            )
        }
    }

    private fun workingDirectorySet(module: Module): Set<String> =
        ApplicationManager.getApplication().runReadAction<Set<String>> {
            ModuleRootManager
                .getInstance(module)
                .contentRoots
                .filter { virtualFile ->
                    virtualFile.findChild(Project.MIX_EXS) != null
                }
                .map(::resolveCredoWorkingDirectory)
                .toHashSet()
        }

    private fun resolveCredoWorkingDirectory(contentRoot: VirtualFile): String {
        val parent = contentRoot.parent
        val grandParent = parent?.parent

        return if (parent?.name == "apps" && grandParent?.findChild(Project.MIX_EXS) != null) {
            // In umbrella projects, run Credo from the umbrella root so task/config are available.
            grandParent.path
        } else {
            contentRoot.path
        }
    }

    private fun runWorkingDirectoryInspection(
        manager: InspectionManager,
        globalContext: GlobalInspectionContext,
        problemDescriptionsProcessor: ProblemDescriptionsProcessor,
        module: Module,
        workingDirectory: String,
    ): CredoRunOutcome {
        val elixirSdk = ReadAction.nonBlocking(Callable {
            ElixirSdkLookup.resolveWithErlang(module).sdkOrNotify(module)
        }).executeSynchronously()
            ?: return CredoRunOutcome(findingCount = 0) // notification already fired by sdkOrNotify
        val project = module.project

        val service = org.elixir_lang.credo.Service.getInstance(project)
        val environment = service.environmentVariableData.envs
        val erlParameters = ParametersList.parse(service.erlArguments).toList()
        val elixirParameters = ParametersList.parse(service.elixirArguments).toList()

        ProgressManager.checkCanceled()

        val processOutput = ProgressManager
            .getInstance()
            .run(object : Task.WithResult<ProcessOutput, ExecutionException>(
                project,
                "mix credo in $workingDirectory",
                true
            ) {
                @Throws(ExecutionException::class)
                override fun compute(indicator: ProgressIndicator): ProcessOutput {
                    indicator.isIndeterminate = true

                    val commandLine =
                        Mix.commandLine(
                            environment,
                            workingDirectory,
                            elixirSdk,
                            erlParameters,
                            elixirParameters,
                            false,
                            project,
                            false
                        )
                            .withCharset(StandardCharsets.UTF_8)
                            .withWorkDirectory(workingDirectory)
                            .apply { addParameters("credo", "--format", "flycheck", "--mute-exit-status") }

                    if (LOG.isDebugEnabled) {
                        LOG.debug(
                            "Running Credo inspection command workingDirectory='${workingDirectory}' command='${commandLine.commandLineString}'"
                        )
                    }

                    return ExecUtil.execAndGetOutput(commandLine)
                }
            })

        if (LOG.isDebugEnabled) {
            LOG.debug(
                "Credo process completed workingDirectory='${workingDirectory}' exitCode=${processOutput.exitCode} " +
                    "stdoutLines=${processOutput.stdoutLines.size} stderrLines=${processOutput.stderrLines.size} " +
                    "timeout=${processOutput.isTimeout} cancelled=${processOutput.isCancelled}"
            )
        }

        val stdout = processOutput.stdout.stripColor()
        val stderr = processOutput.stderr.stripColor()
        val combinedOutput = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

        if (processOutput.isCancelled || processOutput.isTimeout) {
            return CredoRunOutcome(
                findingCount = 0,
                failureSummary = summarizeCredoOutput(combinedOutput.ifBlank { "Credo process did not complete." })
            )
        }

        if (stderr.isNotEmpty()) {
            LOG.warn("Credo STDERR:\n$stderr")
        }

        var unmatchedLineCount = 0
        var unmatchedLineSuppressed = false
        var findingCount = 0

        // Credo can emit non-flycheck preamble/progress lines; ignore them with debug throttling.
        for (colorizedLine in processOutput.stdoutLines) {
            ProgressManager.checkCanceled()

            val line = colorizedLine.stripColor()
            val finding = parseFlycheckFinding(line)

            if (finding == null) {
                unmatchedLineCount += 1

                if (unmatchedLineCount <= 5) {
                    debugSkip(
                        reason = "line did not match flycheck format",
                        workingDirectory = workingDirectory,
                        rawLine = line
                    )
                } else if (!unmatchedLineSuppressed && LOG.isDebugEnabled) {
                    unmatchedLineSuppressed = true
                    LOG.debug(
                        "Skipping additional non-flycheck Credo output lines " +
                            "workingDirectory='${workingDirectory}' alreadySkipped=$unmatchedLineCount"
                    )
                }

                continue
            }

            val path = finding.path
            // Keep one malformed path from aborting the whole inspection run (seen on Windows/WSL paths).
            val absolutePath = try {
                Paths.get(workingDirectory, path).toString()
            } catch (invalidPathException: InvalidPathException) {
                debugSkip(
                    reason = "invalid path from Credo output",
                    workingDirectory = workingDirectory,
                    rawLine = line,
                    details = "path='$path' error='${invalidPathException.message}'"
                )
                continue
            }
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)

            if (virtualFile == null) {
                debugSkip(
                    reason = "virtual file not found",
                    workingDirectory = workingDirectory,
                    rawLine = line,
                    details = "path='$path' absolutePath='$absolutePath'"
                )
                continue
            }

            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile == null) {
                    debugSkip(
                        reason = "PsiFile not found for virtual file",
                        workingDirectory = workingDirectory,
                        rawLine = line,
                        details = "absolutePath='$absolutePath'"
                    )
                    return@runReadAction
                }

                val document = psiFile.viewProvider.document
                if (document == null) {
                    debugSkip(
                        reason = "document unavailable for PsiFile",
                        workingDirectory = workingDirectory,
                        rawLine = line,
                        details = "absolutePath='$absolutePath'"
                    )
                    return@runReadAction
                }

                val lineNumber = finding.line?.minus(1)
                val start: Int
                val end: Int
                val columnNumber = finding.column

                // Support line+column, line-only, and file-level findings from Credo flycheck output.
                if (lineNumber != null) {
                    if (lineNumber !in 0 until document.lineCount) {
                        debugSkip(
                            reason = "line number out of document range",
                            workingDirectory = workingDirectory,
                            rawLine = line,
                            details = "lineNumber=$lineNumber documentLineCount=${document.lineCount} absolutePath='$absolutePath'"
                        )
                        return@runReadAction
                    }

                    val lineStartOffset = document.getLineStartOffset(lineNumber)
                    val lineEndOffset = document.getLineEndOffset(lineNumber)

                    if (columnNumber != null) {
                        val offset = lineStartOffset + (columnNumber - 1)

                        if (offset !in lineStartOffset until lineEndOffset) {
                            debugSkip(
                                reason = "column offset out of line range",
                                workingDirectory = workingDirectory,
                                rawLine = line,
                                details = "columnNumber=$columnNumber lineStartOffset=$lineStartOffset lineEndOffset=$lineEndOffset absolutePath='$absolutePath'"
                            )
                            return@runReadAction
                        }

                        start = offset
                        end = start + 1
                    } else {
                        start = lineStartOffset
                        end = lineEndOffset
                    }
                } else {
                    // No line info means the finding applies to the file as a whole.
                    start = 0
                    end = document.textLength
                }

                if (end <= start) {
                    debugSkip(
                        reason = "invalid descriptor range (end <= start)",
                        workingDirectory = workingDirectory,
                        rawLine = line,
                        details = "start=$start end=$end absolutePath='$absolutePath'"
                    )
                    return@runReadAction
                }

                val startElement = psiFile.findElementAt(start)
                if (startElement == null) {
                    debugSkip(
                        reason = "start PSI element not found",
                        workingDirectory = workingDirectory,
                        rawLine = line,
                        details = "start=$start absolutePath='$absolutePath'"
                    )
                    return@runReadAction
                }

                val endElement = psiFile.findElementAt(end - 1)
                if (endElement == null) {
                    debugSkip(
                        reason = "end PSI element not found",
                        workingDirectory = workingDirectory,
                        rawLine = line,
                        details = "end=${end - 1} absolutePath='$absolutePath'"
                    )
                    return@runReadAction
                }

                val message = finding.message
                val problemDescriptor = manager.createProblemDescriptor(
                    startElement,
                    endElement,
                    message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    false
                )
                val refElement = globalContext.refManager.getReference(psiFile)

                problemDescriptionsProcessor.addProblemElement(
                    refElement,
                    problemDescriptor
                )

                findingCount += 1
            }
        }

        val fatalExecutionFailure = isFatalCredoExecutionFailure(processOutput.exitCode, combinedOutput)

        return when {
            fatalExecutionFailure && findingCount == 0 -> {
                CredoRunOutcome(
                    findingCount = 0,
                    failureSummary = summarizeCredoOutput(
                        combinedOutput.ifBlank { "Credo failed with exit code ${processOutput.exitCode}." }
                    )
                )
            }

            fatalExecutionFailure -> {
                // Keep findings, but warn that mix/compile errors may have made results incomplete.
                CredoRunOutcome(
                    findingCount = findingCount,
                    warningSummary = summarizeCredoOutput(combinedOutput)
                )
            }

            else -> CredoRunOutcome(findingCount = findingCount)
        }
    }

    private fun registerCredoExecutionFailureProblem(
        manager: InspectionManager,
        globalContext: GlobalInspectionContext,
        problemDescriptionsProcessor: ProblemDescriptionsProcessor,
        issue: CredoExecutionIssue,
    ) {
        val project = issue.module.project
        val mixExsPath = Paths.get(issue.workingDirectory, Project.MIX_EXS).toString()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(mixExsPath) ?: return

        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@runReadAction
            val startElement = psiFile.firstChild ?: return@runReadAction
            val endElement = psiFile.lastChild ?: startElement
            val message = "Credo execution failed for '${issue.workingDirectory}': ${issue.summary}"
            val problemDescriptor = manager.createProblemDescriptor(
                startElement,
                endElement,
                message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                false
            )
            val refElement = globalContext.refManager.getReference(psiFile)

            problemDescriptionsProcessor.addProblemElement(refElement, problemDescriptor)
        }
    }

    private fun isSameOrAncestorPath(ancestor: String, candidate: String): Boolean {
        val ancestorPath = Paths.get(ancestor).normalize()
        val candidatePath = Paths.get(candidate).normalize()

        return candidatePath.startsWith(ancestorPath)
    }

    private fun String.stripColor(): String = this.replace(Regex("\u001B\\[[;\\d]*m"), "")
    private fun String.toHTML(): String = this.replace("\n", "<br/>\n")

    private fun isFatalCredoExecutionFailure(exitCode: Int, output: String): Boolean {
        if (output.isBlank()) return exitCode != 0

        val normalized = output.lowercase()

        // Treat known mix/compile failures as fatal so users get a notification instead of silent zero findings.
        return normalized.contains("the task \"credo\" could not be found") ||
            normalized.contains("could not compile dependency") ||
            normalized.contains("validate_compile_env") ||
            normalized.contains("aborting boot") ||
            (exitCode != 0 && !normalized.contains(": c: ") && !normalized.contains(": r: ") &&
                !normalized.contains(": f: ") && !normalized.contains(": s: ") && !normalized.contains(": w: "))
    }

    private fun notifyCredoIssue(
        project: com.intellij.openapi.project.Project,
        title: String,
        message: String,
        type: NotificationType,
    ) {
        val cleanMessage = message.ifBlank { "Unknown Credo error" }
        LOG.warn("Credo execution issue:\n$cleanMessage")

        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Elixir")
            .createNotification(
                title,
                cleanMessage.toHTML(),
                type
            )
            .addAction(Action(project))
            .notify(project)
    }

    private fun buildCredoIssueMessage(issues: List<CredoExecutionIssue>): String {
        val lines = mutableListOf<String>()

        lines.add("Credo could not produce a complete result set.")
        lines.add("IntelliJ can still show 'did not find anything' because external execution failed.")
        lines.add("")

        for (issue in issues.take(3)) {
            lines.add("- ${issue.workingDirectory}: ${issue.summary}")
        }

        val remaining = issues.size - 3
        if (remaining > 0) {
            lines.add("- ...and $remaining more")
        }

        return lines.joinToString("\n")
    }

    private fun summarizeCredoOutput(output: String, maxLength: Int = 280): String {
        val firstLine = output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?: "Unknown Credo error"

        return if (firstLine.length <= maxLength) {
            firstLine
        } else {
            firstLine.take(maxLength - 3) + "..."
        }
    }

    private fun debugSkip(
        reason: String,
        workingDirectory: String,
        rawLine: String,
        details: String = ""
    ) {
        if (!LOG.isDebugEnabled) return

        val suffix = if (details.isNotBlank()) " details=$details" else ""
        LOG.debug("Skipping Credo finding: $reason workingDirectory='$workingDirectory' rawLine='$rawLine'$suffix")
    }

    override fun isGraphNeeded(): Boolean = true
    override fun isReadActionNeeded(): Boolean = false
    override fun worksInBatchModeOnly(): Boolean = true
    override fun getShortName(): String = SHORT_NAME

    companion object {
        // Referenced by inspection registration via reflection.
        const val SHORT_NAME = "Credo"
    }
}
