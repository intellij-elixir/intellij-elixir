package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.EXPANDER
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.Callable

/** How the expander's tests write what [ExpanderSource] finds, and run a feature with it answering. */
internal object ExpanderSourceRows {
    /**
     * [found], one a line, as `<declaring module>.<name>/<arity> <reach> <applicability> via [<calls>]`, then ` line <n>`
     * for a source declaration and its uncertainty where it has one.
     */
    fun render(found: List<Found>): String =
        read {
            found.joinToString("\n") { found ->
                val declaration = found.candidate.declaration
                val via = found.via.joinToString(" | ") { it.text.lines().first() }
                val line = when (declaration.declared) {
                    is Declared.Source -> " line ${lineOf(found.element)}"
                    is Declared.Compiled -> ""
                }

                "${found.declaringModuleName()}.${declaration.name}/${arity(declaration.arity)} ${found.candidate.reach} " +
                    "${found.candidate.applicability} via [$via]$line${found.uncertain?.let { " $it" }.orEmpty()}"
            }
        }

    /** [visible], one per line in name order, each with its arity and the line that declares it. */
    fun renderVisible(visible: List<Visible>): String =
        read {
            visible
                .map { visible -> "${visible.lookupName}/${arity(visible.declaration!!.arity)} line ${lineOf(visible.element)}" }
                .sorted()
                .joinToString("\n")
        }

    fun arity(arity: ArityKnowledge): String =
        when (arity) {
            is ArityKnowledge.Exact -> "${arity.arity}"
            is ArityKnowledge.Range -> "${arity.minimum}..${arity.maximum}"
            is ArityKnowledge.Open -> "${arity.minimum}+"
            ArityKnowledge.Unknown -> "?"
        }

    /** The path of the file that declares [found], relative to [root] where it is under it, else the file's name. */
    fun pathOf(found: Found, root: File): String {
        val file = found.element.containingFile
        val under = LocalFileSystem.getInstance().findFileByIoFile(root) ?: return file.name

        return file.originalFile.virtualFile?.let { VfsUtilCore.getRelativePath(it, under) } ?: file.name
    }

    /**
     * Where [found] is declared, as `declaredAt` writes Elixir's answer: `path:line`, or the path alone where [placed]
     * is false for it, each once, in order, space-separated. Run in a read action.
     */
    fun declaredAt(found: List<Found>, root: File, placed: (path: String) -> Boolean = { true }): String =
        found.map { found ->
            val path = pathOf(found, root)

            if (placed(path)) "$path:${lineOf(found.element)}" else path
        }.distinct().sorted().joinToString(" ")

    /** The 1-based line of [element] in its own file. */
    fun lineOf(element: PsiElement): Int = StringUtil.offsetToLineNumber(element.containingFile.text, element.textRange.startOffset) + 1

    /** The expander's candidates for the call [call], which occurs once in [file], rendered one per line. */
    fun candidates(file: PsiFile, call: String): String = candidates(use(file, call, call))

    /** The expander's candidates for [use], rendered one per line. */
    fun candidates(use: Use): String = render(read { ExpanderSource.candidates(use, false) })

    /** The use that is the call [call], found in the only occurrence of [context], or its last with [last]. */
    fun use(file: PsiFile, context: String, call: String, last: Boolean = false): Use.OfCall =
        read { Use.of(call(file, context, call, last))!! }

    /** The call [call], found in the only occurrence of [context], or its last with [last]. */
    fun call(file: PsiFile, context: String, call: String, last: Boolean = false): Call {
        val text = file.text
        val at = if (last) text.lastIndexOf(context) else text.indexOf(context)
        assertTrue("`$context` in the file", at >= 0)
        if (!last) assertEquals("`$context` once in the file", at, text.lastIndexOf(context))
        val offset = text.indexOf(call, at)

        return generateSequence(file.findElementAt(offset)) { it.parent }
            .filterIsInstance<Call>()
            .first { it.textRange.startOffset == offset && it.text == call }
    }

    /** `label: rendered` pairs, one block each, so one comparison shows every row that differs. */
    fun rows(vararg rows: Pair<String, String>): String = rows.joinToString("\n\n") { (label, rendered) -> "$label:\n$rendered" }

    fun <T> read(block: () -> T): T = ReadAction.nonBlocking(Callable { block() }).executeSynchronously()

    /** [block] with a library [name] of [module], a temporary `ebin` holding a copy of [beam], removed afterwards. */
    fun <T> withBeamLibrary(
        project: Project,
        module: Module,
        disposable: Disposable,
        name: String,
        beam: File,
        block: (VirtualFile) -> T,
    ): T {
        val ebin = FileUtil.createTempDirectory("${name}_ebin", null, true)
        beam.copyTo(File(ebin, beam.name))

        return WalkTestSupport.withLibrary(project, module, disposable, name, ebin, block)
    }

    /** [block] with the features answered by [ExpanderSource]. */
    fun <T> withExpanderSwitch(block: () -> T): T {
        val switch = System.setProperty(SOURCE_PROPERTY, EXPANDER)

        return try {
            block()
        } finally {
            if (switch == null) System.clearProperty(SOURCE_PROPERTY) else System.setProperty(SOURCE_PROPERTY, switch)
        }
    }
}
