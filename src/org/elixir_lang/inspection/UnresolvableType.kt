package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.elixir_lang.annotator.Injection
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.QualifiedModule
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.model.psi.type.TypeBuiltins
import org.elixir_lang.model.psi.type.TypeReference
import org.elixir_lang.model.psi.type.isTypeNameUsage
import org.elixir_lang.psi.ElixirVisitor
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified

internal class UnresolvableType : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession
    ): PsiElementVisitor {
        // Only inspect files under Source or Test Source roots.  Files in unmarked
        // directories (e.g. config/, priv/) are not indexed as source and their type
        // resolution scope is narrower -- flagging errors there produces false positives.
        val virtualFile = holder.file.virtualFile
        if (virtualFile == null ||
            !ProjectRootManager.getInstance(holder.project).fileIndex.isInSourceContent(virtualFile)
        ) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        if (Injection.of(holder.file) == Injection.UNCOMPILED) return PsiElementVisitor.EMPTY_VISITOR

        val source = sourceFor(Feature.REFERENCES_INSPECTION)

        return object : ElixirVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is Call) {
                    checkTypeName(element)
                }
            }

            private fun checkTypeName(call: Call) {
                if (!isTypeNameUsage(call)) return

                val name = call.functionName() ?: return
                val nameElement = call.functionNameElement() ?: return
                val arity = call.resolvedFinalArity()

                if (call is Qualified) {
                    val modulars = when (val module = source.qualifiedModule(call)) {
                        // Past where the env is known, an alias it misses may name another module.
                        is QualifiedModule.Held -> if (module.uncertain == null) source.modulars(module) else return
                        QualifiedModule.Absent, QualifiedModule.Unknown -> return
                    }
                    // Nothing written defines it, as where `Module.create` makes it, so it has no types to read.
                    if (modulars.isEmpty()) return
                    if (modulars.any { it is BeamModule && !it.typesRead() }) return
                    if (TypeReference.resolvesToType(call, modulars)) return

                    holder.registerProblem(
                        nameElement,
                        "Type '${call.qualifier().text}.$name/$arity' is not defined",
                        ProblemHighlightType.ERROR
                    )
                } else {
                    if (TypeReference.unqualifiedResolvesToType(call)) return
                    if (TypeReference.resolveTypeVariableSymbols(call).isNotEmpty()) return
                    // Built-in types (e.g. integer(), term()) resolve to BEAM :erlang definitions
                    // that are not indexed in every context, so guard against them explicitly.
                    if (TypeBuiltins.BUILTIN_ARITY_BY_NAME[name]?.contains(arity) == true) return

                    holder.registerProblem(
                        nameElement,
                        "Type '$name/$arity' is not defined",
                        ProblemHighlightType.ERROR
                    )
                }
            }
        }
    }
}
