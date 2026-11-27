package org.elixir_lang.code_insight.completion.contributor

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import org.elixir_lang.declaration.expanderPathOn
import org.elixir_lang.expander.AttributeValue
import org.elixir_lang.expander.ExpanderThreads
import org.elixir_lang.expander.ExpanderWarmUp
import org.elixir_lang.expander.ExpansionResult
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.expander.decodeUtf8
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.Content
import org.elixir_lang.lowering.Part
import org.elixir_lang.lowering.content
import org.elixir_lang.lowering.sigilPieces
import org.elixir_lang.lowering.utf8
import org.elixir_lang.psi.Body
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.HeredocLineable
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine

/**
 * Inside `~p`, the paths of the routes of the router the caret's module names in `@router`, as its `@phoenix_routes`
 * holds them when its body ends. Only with the expander on, which compiles the router.
 */
@Suppress("CanBeDumbAware")
internal class VerifiedRoute : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withParent(Body::class.java), Provider)
    }

    private object Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            if (!expanderPathOn()) return
            val sigil = sigilOf(parameters.position.parent as Body) ?: return
            if (sigil.sigilName() != "p") return
            val soFar = pathSoFar(sigil, parameters.offset) ?: return
            val paths = paths(sigil, parameters.originalFile) ?: return
            val parts = soFar.parts
            val head = Regex(parts.dropLast(1).joinToString("", "^") { Regex.escape(it) + "[^/]+" })
            val matching = result.withPrefixMatcher(PlainPrefixMatcher(parts.last()))

            paths.mapNotNull { path -> head.find(path)?.let { path.substring(it.range.last + 1) } }
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { matching.addElement(lookup(it, soFar)) }
        }
    }
}

/** The sigil [body] is the text of, `null` where it is another literal's. */
private fun sigilOf(body: Body): Sigil? =
    when (val owner = body.parent) {
        is Sigil -> owner
        is HeredocLineable -> owner.parent as? SigilHeredocLiteral
        else -> null
    }

/**
 * The path so far of a `~p`: its [parts], parted at each interpolation, which stands for one segment, and where in the
 * file the last part starts, which an accepted offer replaces.
 *
 * @property terminator the sigil's terminator, which its text escapes; `null` in a heredoc
 */
private class PathSoFar(val parts: List<String>, val lastStart: Int, val terminator: Char?)

/**
 * The text of [sigil]'s body before [offset], as lowering reads it for `sigil_p`; `null` past a `?`, which starts the
 * query, not the path.
 */
private fun pathSoFar(sigil: Sigil, offset: Int): PathSoFar? {
    val pieces = sigilPieces(sigil, offset) ?: return null
    val parts = mutableListOf(mutableListOf<Int>())
    var lastStart: Int? = null

    when (val content = content(sigil, pieces, ElixirLanguageLevelResolver.languageLevelFor(sigil)) ?: return null) {
        is Content.Empty -> Unit
        is Content.Literal -> parts.last().addAll(content.codePoints)
        is Content.Interpolated -> for (part in content.parts) {
            when (part) {
                is Part.Text -> parts.last().addAll(part.codePoints)
                is Part.Interpolation -> {
                    parts.add(mutableListOf())
                    lastStart = part.interpolation.textRange.endOffset
                }
            }
        }
    }

    val read = parts.map { String(utf8(it), Charsets.UTF_8) }
    if (read.any { '?' in it }) return null
    val start = lastStart ?: when (sigil) {
        is SigilHeredocLiteral -> sigil.heredocLineList.firstOrNull()?.body
        is SigilLine -> sigil.body
        else -> null
    }?.textRange?.startOffset ?: return null

    return PathSoFar(read, start, (sigil as? SigilLine)?.terminator())
}

/**
 * The paths of the routes of the router [sigil]'s module names in `@router`, `null` where they aren't known, as while
 * the router's expansion is not yet held, which this starts, or where the expander may not run on this thread.
 */
private fun paths(sigil: Sigil, asker: PsiFile): List<String>? {
    val file = sigil.containingFile as? ElixirFile ?: return null
    val project = file.project
    if (DumbService.isDumb(project) || !ExpanderThreads.mayExpand()) return null
    val inputs = (SdkInputs.getInstance(project).inputsFor(file) as? SdkInputs.Answer.Ready)?.inputs ?: return null
    val indexed = ProjectModules.getInstance(project).indexed(file, inputs)
    val module = indexed.envs.at(sigil.textRange).module ?: return null
    val compiled = indexed.expansion.resultAt(module, sigil.textRange) ?: return null
    val router = ((compiled.attributes.final["router"] as? AttributeValue.Known)?.term as? Term.Atom)?.name ?: return null
    val located = ExpanderWarmUp.getInstance(project).locateHeld(asker, router, inputs)

    return (located as? ProjectModules.Located.Source)?.result?.let(::routePaths)
}

/** The path of each route [router]'s `@phoenix_routes` holds when its body ends. */
private fun routePaths(router: ExpansionResult): List<String>? =
    ((router.attributes.final["phoenix_routes"] as? AttributeValue.Held)?.value as? OtpErlangList)?.map { route ->
        ((route as? OtpErlangMap)?.get(PATH) as? OtpErlangBinary)?.binaryValue()?.let(::decodeUtf8) ?: return null
    }

private val PATH = OtpErlangAtom("path")

/**
 * [path], shown as the route writes it, inserted as [written] in place of [soFar]'s last part, the caret in its first
 * dynamic part, or at the end where there is none.
 */
private fun lookup(path: String, soFar: PathSoFar): LookupElement {
    val start = soFar.lastStart
    val terminator = soFar.terminator

    return LookupElementBuilder.create(path).withInsertHandler { context, _ ->
        val inserted = written(path, terminator)
        val dynamic = inserted.indexOf(INTERPOLATION)

        context.document.replaceString(start, context.tailOffset, inserted)
        context.editor.caretModel.moveToOffset(start + if (dynamic < 0) inserted.length else dynamic + "#{".length)
    }
}

/** [path] as a `~p` ended by [terminator] writes it: the terminator escaped, each dynamic part an empty interpolation. */
private fun written(path: String, terminator: Char?): String {
    fun escape(text: String) = if (terminator == null) text else text.replace("$terminator", "\\$terminator")

    return path.split('/').joinToString(escape("/")) { segment ->
        when {
            segment.startsWith('*') -> INTERPOLATION
            ':' in segment -> escape(segment.substringBefore(':')) + INTERPOLATION
            else -> escape(segment)
        }
    }
}

private const val INTERPOLATION = "#{}"
