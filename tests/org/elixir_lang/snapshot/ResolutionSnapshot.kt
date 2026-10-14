package org.elixir_lang.snapshot

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.model.psi.generic_server.GenServerHandlerTarget
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub

/**
 * What the references in a set of files resolve to, one line per reference, from both of the plugin's reference
 * systems: `psi` for [PsiReference]s and `symbol` for [com.intellij.model.psi.PsiSymbolReference]s. Each module
 * gets a `module` line and a `definition` line per clause that the resolver sees in it.
 */
@Suppress("UnstableApiUsage")
class ResolutionSnapshot(private val root: VirtualFile) {
    sealed interface Row {
        val path: String
        val offset: Int
        val line: String
    }

    data class Inventory(override val path: String, override val offset: Int, override val line: String) : Row

    /**
     * A reference's line and what it was built from. [file] is the view-provider root the reference was found in,
     * [symbols] is empty for a `psi` reference, and [label] is the line's `system  class  \`text\`` part.
     */
    data class Reference(
        override val path: String,
        override val offset: Int,
        override val line: String,
        val file: PsiFile,
        val range: TextRange,
        val label: String,
        val resolves: Boolean,
        val symbols: List<Symbol>,
    ) : Row

    fun lines(files: Iterable<PsiFile>): List<String> = rows(files).map(Row::line)

    fun rows(files: Iterable<PsiFile>): List<Row> =
        files
            .flatMap { file -> file.viewProvider.allFiles.flatMap(::rows) }
            .sortedWith(compareBy(Row::path, Row::offset, Row::line))

    private fun rows(file: PsiFile): List<Row> {
        val rows = mutableListOf<Row>()

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                for (reference in PsiReferenceService.getService().getReferences(element, NO_HINTS)) {
                    rows += row(file, "psi", reference, reference.absoluteRange, targets(reference), emptyList())
                }

                for (reference in PsiSymbolReferenceService.getService().getReferences(element)) {
                    val symbols = reference.resolveReference().toList()
                    val targets = symbols.map(::describe).sorted()
                    rows += row(file, "symbol", reference, reference.absoluteRange, targets, symbols)
                }

                if (element is Call && Stub.isModular(element)) {
                    rows += inventory(file, element)
                }
            }
        })

        return rows
    }

    private fun row(
        file: PsiFile,
        system: String,
        reference: Any,
        range: TextRange,
        targets: List<String>,
        symbols: List<Symbol>,
    ): Reference {
        val label = "$system  ${reference.javaClass.simpleName}  `${text(file, range)}`"
        val resolved = targets.joinToString(" | ").ifEmpty { "nothing" }
        val line = "${location(file, range)}  $label  ->  $resolved"

        return Reference(path(file), range.startOffset, line, file, range, label, targets.isNotEmpty(), symbols)
    }

    private fun inventory(file: PsiFile, modular: Call): List<Inventory> {
        val (names, clauses) = definitions(modular)
        val module = names.joinToString(", ").ifEmpty { "?" }
        val definitions = clauses.map { (clause, declaration) ->
            val description = declaration?.let(::describe) ?: "(no declaration)  `${firstLine(clause)}`"

            inventory(file, clause.textRange, "definition  $module  $description")
        }

        return listOf(inventory(file, modular.textRange, "module  $module")) + definitions
    }

    private fun describe(declaration: Declaration): String {
        val arity = declaration.arity.text()
        val form = when (val declared = declaration.declared) {
            is Declared.Source -> declared.form.name.lowercase()
            is Declared.Compiled -> "compiled"
        }
        val capabilities = declaration.capabilities
        val time = if (capabilities.compileTime) "compile-time" else "runtime"

        return "${declaration.name}/$arity  $form  " +
            "${capabilities.visibility.name.lowercase()} ${capabilities.presentation.name.lowercase()} $time"
    }

    private fun inventory(file: PsiFile, range: TextRange, description: String): Inventory =
        Inventory(path(file), range.startOffset, "${location(file, range)}  $description")

    private fun targets(reference: PsiReference): List<String> =
        if (reference is PsiPolyVariantReference) {
            val (valid, invalid) = reference.multiResolve(false).partition { it.isValidResult }

            valid.map { describe(it.element) }.sorted() + invalid.map { "${describe(it.element)} (invalid)" }.sorted()
        } else {
            listOfNotNull(reference.resolve()).map(::describe)
        }

    private fun describe(element: PsiElement?): String {
        val file = element?.containingFile ?: return element.toString()
        val path = path(file)

        return "$path:${position(file, element.textRange.startOffset)} `${firstLine(element)}`"
    }

    fun describe(symbol: Symbol): String =
        when (symbol) {
            is ElixirSymbolWithUsages -> "${location(symbol.file, symbol.range)} $symbol"
            is GenServerHandlerTarget -> "${location(symbol.file, symbol.range)} $symbol"
            else -> symbol.toString()
        }

    fun location(file: PsiFile, range: TextRange): String = "${path(file)}:${position(file, range.startOffset)}"

    fun path(file: PsiFile): String {
        val virtualFile = file.viewProvider.virtualFile

        return VfsUtilCore.getRelativePath(virtualFile, root)
            ?: throw AssertionError("${virtualFile.path} is outside the inputs, so its path differs between machines")
    }

    private fun position(file: PsiFile, offset: Int): String {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)!!
        val line = document.getLineNumber(offset)

        return "${line + 1}:${offset - document.getLineStartOffset(line) + 1}"
    }

    fun text(file: PsiFile, range: TextRange): String =
        oneLine(range.subSequence(file.viewProvider.contents).toString())

    private fun firstLine(element: PsiElement): String = oneLine(element.text.lineSequence().first().trim())

    private fun oneLine(text: String): String {
        val escaped = text.replace("\n", "\\n")

        return if (escaped.length > MAX_TEXT) escaped.take(MAX_TEXT) + "..." else escaped
    }

    companion object {
        private const val MAX_TEXT = 80
    }
}
