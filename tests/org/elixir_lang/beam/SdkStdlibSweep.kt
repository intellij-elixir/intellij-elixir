package org.elixir_lang.beam

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LoggedErrorProcessor
import org.elixir_lang.beam.psi.impl.CallDefinitionImpl
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.operation.Prefix
import org.elixir_lang.structure_view.element.CallDefinitionHead
import java.util.concurrent.ConcurrentHashMap

/**
 * One decompile pass per resolved SDK, answering the four questions [SdkStdlibSweepTest] asks.
 * Whichever of its test methods runs first for a given (root, tag) pays the decompile cost and caches the
 * plain-data [Result]; the rest read it back and pass or fail on their own. Safe to share across the `Project`
 * instances the test methods each stand up, because the cached [Result] holds only strings and counts, never PSI.
 * The cache is per JVM, so only tests in one class, which Gradle keeps on one fork, are sure to share it.
 */
object SdkStdlibSweep {
    data class Result(
        val beamCount: Int,
        val parseFailures: List<String>,
        val mirrorExported: Int,
        val mirrorMisses: List<String>,
        val stubCompared: Int,
        val stubExportedWithParameters: Int,
        val stubExportedGenerated: Int,
        val stubBeamsWithExportedGenerated: Int,
        val stubMismatches: List<String>,
        val decompilerWarnings: List<String>
    )

    private val cache = ConcurrentHashMap<Pair<String, String>, Result>()

    fun forSdk(project: Project, testRootDisposable: Disposable, root: String, tag: String): Result =
        cache.getOrPut(root to tag) { sweep(project, testRootDisposable, root, tag) }

    private fun sweep(project: Project, testRootDisposable: Disposable, root: String, tag: String): Result {
        VfsRootAccess.allowRootAccess(testRootDisposable, root)

        val beams = SdkBeams.forSdk(root, tag)
        val state = ResolveState.initial()

        val parseFailures = mutableListOf<String>()
        val mirrorMisses = mutableListOf<String>()
        val stubMismatches = mutableListOf<String>()

        var mirrorExported = 0
        var stubCompared = 0
        var stubExportedWithParameters = 0
        var stubExportedGenerated = 0
        val beamsWithExportedGenerated = mutableSetOf<String>()
        val firstClauseCache = mutableMapOf<PsiElement, Map<String, Map<Int, Call>>?>()
        val decompilerWarnings = mutableListOf<String>()
        var currentBeam = ""
        val sweepThread = Thread.currentThread()
        // The beam code reports a term it cannot render with `LOG.warn` and emits a placeholder that still parses. The
        // processor is JVM-wide, so another thread's beam warning would not be about this beam.
        val warnings = object : GuardedLoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean =
                if (Thread.currentThread() === sweepThread && category.contains("org.elixir_lang.beam")) {
                    decompilerWarnings += "$currentBeam: ${message.replace(Regex("\\s+"), " ").take(300)}"
                    false
                } else {
                    super.processWarn(category, message, t)
                }
        }

        LoggedErrorProcessor.executeWith(warnings).use {
            for ((beamLabel, file) in beams) {
                currentBeam = beamLabel
                val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                if (virtualFile == null) {
                    parseFailures += "$beamLabel: no VirtualFile"
                    continue
                }

                val psiFile = try {
                    PsiManager.getInstance(project).findFile(virtualFile)
                } catch (t: ProcessCanceledException) {
                    throw t
                } catch (t: Throwable) {
                    recordBeamFailure(beamLabel, t, parseFailures, mirrorMisses, stubMismatches)
                    continue
                }

                if (psiFile !is PsiCompiledFile) {
                    parseFailures += "$beamLabel: not a PsiCompiledFile (${psiFile?.javaClass?.simpleName})"
                    continue
                }

                val decompiled = try {
                    psiFile.decompiledPsiFile
                } catch (t: ProcessCanceledException) {
                    throw t
                } catch (t: Throwable) {
                    recordBeamFailure(beamLabel, t, parseFailures, mirrorMisses, stubMismatches)
                    continue
                }

                try {
                    PsiTreeUtil.findChildOfType(decompiled, PsiErrorElement::class.java)?.let { error ->
                        parseFailures += "$beamLabel: ${error.errorDescription}"
                    }
                } catch (t: ProcessCanceledException) {
                    throw t
                } catch (t: Throwable) {
                    parseFailures += "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
                }

                val modules = try {
                    PsiTreeUtil.findChildrenOfType(psiFile, ModuleImpl::class.java)
                        .ifEmpty { psiFile.children.filterIsInstance<ModuleImpl<*>>() }
                } catch (t: ProcessCanceledException) {
                    throw t
                } catch (t: Throwable) {
                    recordBeamFailure(beamLabel, t, mirrorMisses, stubMismatches)
                    continue
                }

                for (module in modules) {
                    val moduleMirror = try {
                        (module as ModuleImpl<*>).mirror as? Call
                    } catch (t: ProcessCanceledException) {
                        throw t
                    } catch (t: Throwable) {
                        stubMismatches += "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
                        null
                    }

                    for (callDefinition in (module as ModuleImpl<*>).callDefinitions()) {
                        try {
                            if (callDefinition.isExported) {
                                mirrorExported++
                                if (callDefinition.mirror == null) {
                                    mirrorMisses += "$beamLabel: ${nameArity(callDefinition, state)}"
                                }
                            }
                        } catch (t: ProcessCanceledException) {
                            throw t
                        } catch (t: Throwable) {
                            mirrorMisses += "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
                        }

                        try {
                            val stub = callDefinition.stub
                            val name = stub.name
                            val arity = stub.callDefinitionClauseHeadArity()

                            if (stub.isExported && arity > 0) {
                                stubExportedWithParameters++

                                if (stub.isAutoGeneratedName) {
                                    stubExportedGenerated++
                                    beamsWithExportedGenerated += beamLabel
                                }
                            }

                            val mirror = callDefinition.mirror as? Call
                            if (mirror != null && moduleMirror != null) {
                                // null means the module's map build itself failed and was already logged once -
                                // a stub in that module gets no comparison at all, rather than every one of them
                                // separately reporting the fallout as its own, misleading "mirror=null" mismatch.
                                val byArityByName =
                                    firstClauseByArityByNameOrNull(moduleMirror, firstClauseCache, beamLabel, stubMismatches)

                                if (byArityByName != null) {
                                    stubCompared++
                                    val firstClause = byArityByName[name]?.get(arity)

                                    // firstClause must come from an independent traversal: mirror was set using
                                    // ModuleImpl.callDefinitionClauseByArityByName, so comparing it against that same
                                    // function's own output could never disagree.
                                    if (firstClause != null && !mirror.isEquivalentTo(firstClause)) {
                                        stubMismatches += "$beamLabel: ${stub.resolvedFunctionName()} $name/$arity " +
                                            "mirror is not the first matching clause"
                                    }

                                    val expected = firstClause?.let { clauseParameters(it) }
                                    val actual = stub.parameters()

                                    if (expected != actual) {
                                        stubMismatches += "$beamLabel: ${stub.resolvedFunctionName()} $name/$arity " +
                                            "mirror=$expected stub=$actual"
                                    }
                                }
                            }
                        } catch (t: ProcessCanceledException) {
                            throw t
                        } catch (t: Throwable) {
                            stubMismatches += "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
                        }
                    }
                }
            }
        }

        return Result(
            beamCount = beams.size,
            parseFailures = parseFailures,
            mirrorExported = mirrorExported,
            mirrorMisses = mirrorMisses,
            stubCompared = stubCompared,
            stubExportedWithParameters = stubExportedWithParameters,
            stubExportedGenerated = stubExportedGenerated,
            stubBeamsWithExportedGenerated = beamsWithExportedGenerated.size,
            stubMismatches = stubMismatches,
            decompilerWarnings = decompilerWarnings
        )
    }

    private fun recordBeamFailure(beamLabel: String, t: Throwable, vararg lists: MutableList<String>) {
        val message = "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
        lists.forEach { it += message }
    }

    private fun nameArity(callDefinition: CallDefinitionImpl<*>, state: ResolveState): String =
        try {
            "${callDefinition.exportedName()}/${callDefinition.exportedArity(state)}"
        } catch (t: ProcessCanceledException) {
            throw t
        } catch (_: Throwable) {
            callDefinition.exportedName()
        }

    /**
     * The module's name/arity -> first-clause map, or `null` if building it threw. Cached either way, so a bad
     * clause costs exactly one log entry per module, not one per query against it.
     */
    private fun firstClauseByArityByNameOrNull(
        moduleMirror: Call,
        cache: MutableMap<PsiElement, Map<String, Map<Int, Call>>?>,
        beamLabel: String,
        stubMismatches: MutableList<String>
    ): Map<String, Map<Int, Call>>? {
        // A cached failure is a real, meaningful null, so presence must be checked with containsKey - a null
        // value looks the same as an absent key to any check that reads the value itself.
        if (!cache.containsKey(moduleMirror)) {
            cache[moduleMirror] = try {
                firstClauseByArityByName(moduleMirror)
            } catch (t: ProcessCanceledException) {
                throw t
            } catch (t: Throwable) {
                stubMismatches += "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
                null
            }
        }

        return cache[moduleMirror]
    }

    private fun clauseParameters(clause: Call): List<String>? =
        CallDefinitionClause.head(clause)
            ?.let { CallDefinitionHead.strip(it) as? Call }
            ?.let { head ->
                // `def not(value)` parses as `not` applied to the parenthesized operand `(value)`.
                head.finalArguments()?.map { argument ->
                    if (head is Prefix) CallDefinitionHead.stripAllOuterParentheses(argument).text else argument.text
                }
            }

    /**
     * Keeps the first clause per `(name, arity)` in document order, among the module's own clauses.
     *
     * [moduleMirror] must be the module's own top-level mirror `Call`, the same starting point
     * [org.elixir_lang.beam.psi.impl.ModuleImpl]'s own traversal uses - not any specific clause's own parent.
     */
    private fun firstClauseByArityByName(moduleMirror: Call): Map<String, Map<Int, Call>> {
        val byArityByName = mutableMapOf<String, MutableMap<Int, Call>>()

        // Each clause by its own name and arities, not by the function it belongs to, so this stays independent of
        // CallDefinitionClause.firstClauseByArityByName, which ModuleImpl uses.
        for (call in Modular.callDefinitionClauseCallSequence(moduleMirror)) {
            val nameArityInterval = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: continue
            val byArity = byArityByName.getOrPut(nameArityInterval.name) { mutableMapOf() }
            nameArityInterval.arityInterval.closed().forEach { arity -> byArity.putIfAbsent(arity, call) }
        }

        return byArityByName
    }
}
