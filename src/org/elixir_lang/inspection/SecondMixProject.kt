package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.elixir_lang.mix.Project
import org.elixir_lang.mix.ProjectKeywords
import org.elixir_lang.mix.ProjectKeywords.UsesMixProject
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.ElixirFile

/** A module of `mix.exs` that uses `Mix.Project` after another one in the file does, which Mix fails to compile. */
internal class SecondMixProject : LocalInspectionTool(), DumbAware {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        if (holder.file.name == Project.MIX_EXS) {
            object : PsiElementVisitor() {
                override fun visitFile(file: PsiFile) {
                    if (file !is ElixirFile) return

                    ProjectKeywords.projectModules(file)
                        .filter { it.usesMixProject == UsesMixProject.YES }
                        .drop(1)
                        .forEach { module -> holder.registerProblem(Alias.firstArgument(module.definition) ?: module.definition, MESSAGE) }
                }
            }
        } else {
            PsiElementVisitor.EMPTY_VISITOR
        }

    private companion object {
        const val MESSAGE = "Mix will fail to compile this project: another module in this file already uses `Mix.Project`"
    }
}
