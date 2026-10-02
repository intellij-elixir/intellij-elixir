package org.elixir_lang.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.ProcessDeclarationsImpl
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.moduleName

class ElixirFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ElixirLanguage) {
    override fun getFileType(): FileType = ElixirFileType.INSTANCE

    override fun toString(): String = "Elixir File"

    override fun processDeclarations(processor: PsiScopeProcessor,
                                     state: ResolveState,
                                     lastParent: PsiElement?,
                                     place: PsiElement): Boolean =
            ProcessDeclarationsImpl.processDeclarations(this, processor, state, lastParent!!, place)

    // With no view module of its own, an injected Elixir root (e.g. inside a ~H sigil) uses the
    // platform's context - the injection host - so resolution reaches the surrounding module.
    override fun getContext(): PsiElement? = viewFile()?.modulars()?.singleOrNull() ?: super.getContext()

    /**
     * If this file is a LEEx template (`*.html.leex`), then this is the file that should contain the view module.
     */
    fun viewFile(): ElixirFile? =
            // An injected fragment (e.g. the HTML/Elixir roots inside a ~H sigil) is a light window
            // file with no real directory to search - the branches below would run directory lookups
            // against it and either NPE or return nonsense.
            if (virtualFile is com.intellij.injected.editor.VirtualFileWindow) {
                null
            } else when (virtualFile?.fileType) {
                org.elixir_lang.eex.file.Type.INSTANCE -> {
                    containingFile.parent?.let { directory ->
                        directory.parentDirectory?.let { directoryDirectory ->
                            if (directoryDirectory.name == "templates") {
                                val viewFile = directoryDirectory
                                        .parentDirectory
                                        ?.findSubdirectory("views")
                                        ?.findFile("${directory.name}_view.ex") as? ElixirFile

                                viewFile
                            } else {
                                null
                            }
                        } ?: findUsesEExFile(directory.files)
                    }
                }
                org.elixir_lang.leex.file.Type.INSTANCE -> {
                    containingFile.parent?.let { directory ->
                        val nameWithoutExtension = containingFile.name.removeSuffix(".html.leex")
                        val liveName = "${nameWithoutExtension}.ex"

                        directory.findFile(liveName) as? ElixirFile
                    }
                }
                org.elixir_lang.heex.file.Type.INSTANCE -> {
                    containingFile.parent?.let { directory ->
                        heexSiblingFile(directory) ?: heexEmbedTemplatesFile(directory) ?: heexTemplatesViewFile(directory)
                    }
                }
                else -> null
            }

    // `foo_live.html.heex` -> `foo_live.ex` in the same directory (LiveView colocated convention).
    private fun heexSiblingFile(directory: PsiDirectory): ElixirFile? {
        val nameWithoutExtension = containingFile.name.removeSuffix(".html.heex")
        return directory.findFile("${nameWithoutExtension}.ex") as? ElixirFile
    }

    // Phoenix 1.7+ `embed_templates` convention: `controllers/page_html/index.html.heex` ->
    // `controllers/page_html.ex` (the template's parent directory name, in the grandparent directory).
    private fun heexEmbedTemplatesFile(directory: PsiDirectory): ElixirFile? =
            directory.parentDirectory?.findFile("${directory.name}.ex") as? ElixirFile

    // Phoenix 1.6 `templates/` -> `views/` convention, same rule as the `.eex` branch above.
    private fun heexTemplatesViewFile(directory: PsiDirectory): ElixirFile? =
            directory.parentDirectory?.let { directoryDirectory ->
                if (directoryDirectory.name == "templates") {
                    directoryDirectory
                            .parentDirectory
                            ?.findSubdirectory("views")
                            ?.findFile("${directory.name}_view.ex") as? ElixirFile
                } else {
                    null
                }
            }

    private fun findUsesEExFile(files: Array<PsiFile>): ElixirFile? =
            files.filterIsInstance<ElixirFile>().find { elixirFile ->
                usesEExFile(elixirFile)
            }

    private fun usesEExFile(file: ElixirFile): Boolean =
        file.childExpressions().filterIsInstance<Call>().any { call ->
            if (Module.`is`(call)) {
                moduleUsesEExFile(call)
            } else {
                false
            }
        }

    private fun moduleUsesEExFile(module: Call): Boolean =
            CallDefinitionClause.modularChildCalls(module).filterIsInstance<Qualified>().any { call ->
                // `function_from_file(kind, name, file, args \\ [], options \\ [])`
                if (moduleName(call.qualifier())?.name == "EEx" && call.functionName() == "function_from_file" &&
                        call.resolvedFinalArity() in 3..5) {
                    eexFunctionFromFileUsesEExFile(call)
                } else {
                    false
                }
            }

    private fun eexFunctionFromFileUsesEExFile(call: Call): Boolean =
        call.finalArguments()?.let { arguments ->
            eexFunctionFromFileArgumentUsesEExFile(arguments[2])
        } ?: false

    private fun eexFunctionFromFileArgumentUsesEExFile(argument: PsiElement): Boolean =
       when (argument) {
           is Qualified -> {
               moduleName(argument.qualifier())?.name == "Path" &&
                       argument.functionName() == "expand" &&
                       argument.resolvedFinalArity() == 2 &&
                       pathExpandUsesEExFile(argument)
           }
           else -> {
               false
           }
       }

    private fun pathExpandUsesEExFile(pathExpand: Call): Boolean =
        pathExpand.finalArguments()?.let { arguments ->
            arguments[0].text == "\"${virtualFile?.name}\"" && arguments[1].text == "__DIR__"
        } ?: false

    /**
     * @return modulars owned (declared) by this element.
     */
    @RequiresReadLock
    fun modulars(): Array<StubBased<*>> {
        val stubBaseds = PsiTreeUtil.getChildrenOfType(
                this,
                StubBased::class.java
        )
        val modularList: MutableList<StubBased<*>> = ArrayList()
        if (stubBaseds != null) {
            for (stubBased in stubBaseds) {
                ProgressManager.checkCanceled()
                if (Implementation.`is`(stubBased) || Module.`is`(stubBased) || Protocol.`is`(stubBased)) {
                    modularList.add(stubBased)
                }
            }
        }
        return modularList.toTypedArray()
    }
}
