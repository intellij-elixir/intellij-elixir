package org.elixir_lang.psi.impl

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.annotator.Parameter
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.navigation.item_presentation.KeywordKey
import org.elixir_lang.psi.ElixirIdentifier
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.__module__.AliasPresentation
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Function.ALIAS
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.outerMostQualifiableAlias
import org.elixir_lang.structure_view.element.CallDefinitionClause
import java.io.File
import javax.swing.Icon

object PresentationImpl {
    @RequiresReadLock
    @JvmStatic
    fun getPresentation(call: Call): ItemPresentation =
            when {
                org.elixir_lang.psi.CallDefinitionClause.`is`(call) ->
                    CallDefinitionClause.fromCall(call)?.presentation ?: getDefaultPresentation(call)
                org.elixir_lang.psi.Module.`is`(call) -> {
                    val modular = CallDefinitionClause.enclosingModular(call)
                    org.elixir_lang.structure_view.element.modular.Module(modular, call).presentation
                }
                call.isCalling(KERNEL, Function.__MODULE__, 0) -> {
                    if (call.parent?.parent?.let { it as? Call }?.isCalling(KERNEL, ALIAS) == true) {
                        AliasPresentation(call)
                    } else {
                        getDefaultPresentation(call)
                    }
                }
                else -> getDefaultPresentation(call)
            }

    @JvmStatic
    fun getPresentation(identifier: ElixirIdentifier): ItemPresentation? {
        val parameterizedParameter = Parameter(identifier).let { Parameter.putParameterized(it) }

        return if ((parameterizedParameter.type == Parameter.Type.FUNCTION_NAME ||
                        parameterizedParameter.type == Parameter.Type.MACRO_NAME) &&
                parameterizedParameter.parameterized != null) {
            (parameterizedParameter.parameterized as? Call)?.let {
                CallDefinitionClause.fromCall(it)?.presentation
            }
        } else {
            null
        }
    }

    @JvmStatic
    fun getPresentation(keywordKey: ElixirKeywordKey): ItemPresentation = KeywordKey(keywordKey)

    @JvmStatic
    fun getPresentation(qualifiableAlias: QualifiableAlias): ItemPresentation =
        qualifiableAlias
                .outerMostQualifiableAlias()
                .let { org.elixir_lang.psi.impl.qualifiable_alias.ItemPresentation(it) }

    private fun getDefaultPresentation(call: Call): ItemPresentation {
        val text = UsageViewUtil.createNodeText(call)

        return object : ItemPresentation {
            private val _locationString by lazy { call.locationString() }

            override fun getIcon(b: Boolean): Icon? = call.getIcon(0)
            override fun getLocationString(): String? = _locationString
            override fun getPresentableText(): String? = text
        }
    }
}

fun Call.locationString(): String =
    if (isValid) {
        containingFile?.locationString(this.project) ?: "?"
    } else {
        "?"
    }

fun PsiFile.locationString(project: Project): String {
    val originalFile = originalFile

    return when (originalFile) {
        is BeamFileImpl -> originalFile.virtualFile.locationString(project).let { "$it.decompiled.ex" }
        else -> virtualFile?.locationString(project)
    } ?: name
}

private fun VirtualFile.locationString(project: Project): String {
    val basePath = project.basePath!!
    val path = path

    /* relativeTo always works on unix-like system and ends up with `../../../usr/local/...` for paths that should be
       absolute */
    return if (path.startsWith(basePath)) {
        File(path).relativeToOrSelf(File(basePath)).path
    } else {
        path
    }
}
