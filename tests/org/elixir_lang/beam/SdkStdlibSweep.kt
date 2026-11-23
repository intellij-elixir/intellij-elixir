package org.elixir_lang.beam

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LoggedErrorProcessor
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.AbstractCodeCompileOptions
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.beam.decompiler.signatureParameters
import org.elixir_lang.beam.psi.impl.CallDefinitionImpl
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.code.sanitizeErlangVariableName
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.operation.Prefix
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * One decompile pass over a `.beam`, answering the four questions [SdkStdlibSweepTest] asks of it. The [Result] holds
 * only strings and counts, never PSI.
 */
object SdkStdlibSweep {
    data class Result(
        val parseFailures: List<String>,
        val mirrorExported: Int,
        val mirrorMisses: List<String>,
        val stubCompared: Int,
        val stubExportedWithParameters: Int,
        val stubExportedGenerated: Int,
        val stubMismatches: List<String>,
        val decompilerWarnings: List<String>
    )

    fun sweep(project: Project, beam: SdkBeam): Result {
        val beamLabel = beam.label
        val state = ResolveState.initial()

        val parseFailures = mutableListOf<String>()
        val mirrorMisses = mutableListOf<String>()
        val stubMismatches = mutableListOf<String>()

        var mirrorExported = 0
        var stubCompared = 0
        var stubExportedWithParameters = 0
        var stubExportedGenerated = 0
        val clausesCache = mutableMapOf<PsiElement, ModuleClauses?>()
        val decompilerWarnings = mutableListOf<String>()
        val sweepThread = Thread.currentThread()
        // The beam code reports a term it cannot render with `LOG.warn` and emits a placeholder that still parses. The
        // processor is JVM-wide, so another thread's beam warning would not be about this beam.
        val warnings = object : GuardedLoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean =
                if (Thread.currentThread() === sweepThread && category.contains("org.elixir_lang.beam")) {
                    decompilerWarnings += "$beamLabel: ${message.replace(Regex("\\s+"), " ").take(300)}"
                    false
                } else {
                    super.processWarn(category, message, t)
                }
        }

        fun result() = Result(
            parseFailures = parseFailures,
            mirrorExported = mirrorExported,
            mirrorMisses = mirrorMisses,
            stubCompared = stubCompared,
            stubExportedWithParameters = stubExportedWithParameters,
            stubExportedGenerated = stubExportedGenerated,
            stubMismatches = stubMismatches,
            decompilerWarnings = decompilerWarnings
        )

        LoggedErrorProcessor.executeWith(warnings).use {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(beam.file)
            if (virtualFile == null) {
                parseFailures += "$beamLabel: no VirtualFile"
                return result()
            }

            val psiFile = try {
                PsiManager.getInstance(project).findFile(virtualFile)
            } catch (t: ProcessCanceledException) {
                throw t
            } catch (t: Throwable) {
                recordBeamFailure(beamLabel, t, parseFailures, mirrorMisses, stubMismatches)
                return result()
            }

            if (psiFile !is PsiCompiledFile) {
                parseFailures += "$beamLabel: not a PsiCompiledFile (${psiFile?.javaClass?.simpleName})"
                return result()
            }

            val decompiled = try {
                psiFile.decompiledPsiFile
            } catch (t: ProcessCanceledException) {
                throw t
            } catch (t: Throwable) {
                recordBeamFailure(beamLabel, t, parseFailures, mirrorMisses, stubMismatches)
                return result()
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
                return result()
            }

            val erlang = BeamReader.read(virtualFile) { it.debugInfo is AbstractCodeCompileOptions } == true

            for (module in modules) {
                val moduleMirror = try {
                    (module as ModuleImpl<*>).mirror as? Call
                } catch (t: ProcessCanceledException) {
                    throw t
                } catch (t: Throwable) {
                    stubMismatches += "$beamLabel: ${t.javaClass.simpleName}: ${t.message}"
                    null
                }

                val headsByDefinition = (module as ModuleImpl<*>).callDefinitions().associate {
                    Triple(it.stub.resolvedFunctionName(), it.stub.name, it.stub.callDefinitionClauseHeadArity()) to
                        it.stub.head()
                }

                for (callDefinition in module.callDefinitions()) {
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
                        val head = stub.head()

                        if (head.isNotEmpty()) {
                            for (covered in ParameterText.arities(head)) {
                                val coveredHead = headsByDefinition[Triple(stub.resolvedFunctionName(), name, covered)]

                                // An empty head is a definition of its own, as `Enum.max/2` beside `max/3`'s defaults.
                                if (!coveredHead.isNullOrEmpty() && coveredHead != head) {
                                    stubMismatches += "$beamLabel: ${stub.resolvedFunctionName()} $name/$arity " +
                                        "head=$head covers $covered, whose stub has head=$coveredHead"
                                }
                            }
                        }

                        if (stub.isExported && arity > 0) {
                            stubExportedWithParameters++

                            if (stub.isAutoGeneratedName) {
                                stubExportedGenerated++
                            }
                        }

                        val mirror = callDefinition.mirror as? Call
                        if (mirror != null && moduleMirror != null) {
                            // null means the module's map build itself failed and was already logged once -
                            // a stub in that module gets no comparison at all, rather than every one of them
                            // separately reporting the fallout as its own, misleading "mirror=null" mismatch.
                            val clauses =
                                moduleClausesOrNull(moduleMirror, erlang, clausesCache, beamLabel, stubMismatches)

                            if (clauses != null) {
                                stubCompared++
                                val firstClause = clauses.first(name, arity)

                                // firstClause must come from an independent traversal: mirror was set using
                                // ModuleImpl.callDefinitionClauseByArityByName, so comparing it against that same
                                // function's own output could never disagree.
                                if (firstClause != null && !mirror.isEquivalentTo(firstClause)) {
                                    stubMismatches += "$beamLabel: ${stub.resolvedFunctionName()} $name/$arity " +
                                        "mirror is not the first matching clause"
                                }

                                if (clauses.specIsUndecidable(name, arity)) continue

                                val expected = if (erlang) {
                                    clauses.erlangParameters(name, arity)
                                } else {
                                    firstClause?.let(::clauseParameters)
                                }
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

        return result()
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
     * The module's clauses, or `null` if building them threw. Cached either way, so a bad clause costs exactly one log
     * entry per module, not one per query against it.
     */
    private fun moduleClausesOrNull(
        moduleMirror: Call,
        erlang: Boolean,
        cache: MutableMap<PsiElement, ModuleClauses?>,
        beamLabel: String,
        stubMismatches: MutableList<String>
    ): ModuleClauses? {
        // A cached failure is a real, meaningful null, so presence must be checked with containsKey - a null
        // value looks the same as an absent key to any check that reads the value itself.
        if (!cache.containsKey(moduleMirror)) {
            cache[moduleMirror] = try {
                moduleClauses(moduleMirror, erlang)
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
                    if (head is Prefix) argument.stripAccessExpressionAndParentheses().text else argument.text
                }
            }

    /**
     * The clauses of each `(name, arity)` in document order among the module's own clauses, and, for an [erlang]
     * module, the arguments of each `@spec` line, which the decompiler writes once per spec clause.
     *
     * [moduleMirror] must be the module's own top-level mirror `Call`, the same starting point
     * [org.elixir_lang.beam.psi.impl.ModuleImpl]'s own traversal uses - not any specific clause's own parent.
     */
    private fun moduleClauses(moduleMirror: Call, erlang: Boolean): ModuleClauses {
        val clauses = mutableMapOf<String, MutableMap<Int, MutableList<Call>>>()

        for (call in Modular.callDefinitionClauseCallSequence(moduleMirror)) {
            CallDefinitionClause.nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                val byArity = clauses.getOrPut(name) { mutableMapOf() }
                arityInterval.closed().forEach { byArity.getOrPut(it) { mutableListOf() } += call }
            }
        }

        val specs = mutableMapOf<String, MutableMap<Int, MutableList<List<String>>>>()

        if (erlang) {
            for (match in SPEC.findAll(moduleMirror.text)) {
                val (unquoted, plain, rest) = match.destructured
                val arguments = signatureParameters("f($rest")
                val byArity = specs.getOrPut(unquoted.ifEmpty { plain }) { mutableMapOf() }
                byArity.getOrPut(arguments.size) { mutableListOf() } += arguments
            }
        }

        return ModuleClauses(clauses, specs)
    }

    private class ModuleClauses(
        private val clauses: Map<String, Map<Int, List<Call>>>,
        private val specs: Map<String, Map<Int, List<List<String>>>>
    ) {
        fun first(name: String, arity: Int): Call? = clausesOf(name, arity)?.first()

        /**
         * The decompiler writes an anonymous spec variable as `any()`, alone or annotated as `any() :: T`, so the
         * decompiled spec cannot tell whether it names the parameter.
         */
        fun specIsUndecidable(name: String, arity: Int): Boolean =
            loneSpec(name, arity)?.any { it == "any()" || it.startsWith("any() :: ") } == true

        /**
         * An Erlang function's names as OTP's documentation chooses them, read from what the decompiler shows: a lone
         * spec's variables, else a lone clause's variables with any leading `_` trimmed, else the first clause's head.
         */
        fun erlangParameters(name: String, arity: Int): List<String>? {
            val nameArityClauses = clausesOf(name, arity) ?: return null
            loneSpec(name, arity)?.let(::specNames)?.let { return it }

            val parameters = clauseParameters(nameArityClauses.first()) ?: return null

            if (nameArityClauses.size != 1) return parameters

            return parameters.map { parameter ->
                VARIABLE.matchEntire(parameter)?.groupValues?.get(1)?.takeIf { it != "_" }
                    ?.let { sanitizeErlangVariableName(it.trimStart('_')) }
                    ?: return parameters
            }
        }

        private fun clausesOf(name: String, arity: Int): List<Call>? = clauses[name]?.get(arity)

        private fun loneSpec(name: String, arity: Int): List<String>? = specs[name]?.get(arity)?.singleOrNull()

        private fun specNames(arguments: List<String>): List<String>? =
            arguments.map { SPEC_ARGUMENT.matchEntire(it)?.groupValues?.get(1) ?: return null }
    }

    private val SPEC = Regex("""^  @spec (?:unquote\(:"?(.+?)"?\)|([^\s(]+))\((.*)$""", RegexOption.MULTILINE)

    // A repeated variable is pinned where it repeats, in a spec as in a clause.
    // Elixir reads a bare `true`, `false` or `nil` as an atom, not a variable.
    private val VARIABLE = Regex("""\^?(?!(?:true|false|nil)\b)([a-z_][A-Za-z0-9_]*)""")
    private val SPEC_ARGUMENT = Regex("""${VARIABLE.pattern}(?: :: .+)?""")
}
