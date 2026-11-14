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
import org.elixir_lang.expander.ExpansionResult
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.expander.decodeUtf8
import org.elixir_lang.psi.Bodied
import org.elixir_lang.psi.Body
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.HeredocLineable
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral

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
            val parts = pathSoFar(sigil, parameters.offset) ?: return
            val paths = paths(sigil, parameters.originalFile) ?: return
            val head = Regex(parts.dropLast(1).joinToString("", "^") { Regex.escape(it) + "[^/]+" })
            val matching = result.withPrefixMatcher(PlainPrefixMatcher(parts.last()))

            paths.mapNotNull { path -> head.find(path)?.let { path.substring(it.range.last + 1) } }
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { matching.addElement(lookup(it)) }
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
 * The text of [sigil]'s body before [offset], parted at each interpolation, which stands for one segment; `null` past a
 * `?`, which starts the query, not the path.
 */
private fun pathSoFar(sigil: Sigil, offset: Int): List<String>? {
    val bodies = when (sigil) {
        is SigilHeredocLiteral -> sigil.heredocLineList
        is Bodied -> listOf(sigil)
        else -> return null
    }.map { it.body }
    val parts = mutableListOf(StringBuilder())

    for ((index, body) in bodies.withIndex()) {
        if (body.textRange.startOffset >= offset) break
        if (index > 0) parts.last().append('\n')

        for (child in generateSequence(body.firstChild) { it.nextSibling }) {
            if (child.textRange.startOffset >= offset) break

            if (child is ElixirInterpolation) {
                parts.add(StringBuilder())
            } else {
                parts.last().append(child.text, 0, minOf(child.textLength, offset - child.textRange.startOffset))
            }
        }
    }

    return parts.map(StringBuilder::toString).takeIf { it.none { part -> '?' in part } }
}

/** The paths of the routes of the router [sigil]'s module names in `@router`, `null` where they aren't known. */
private fun paths(sigil: Sigil, asker: PsiFile): List<String>? {
    val file = sigil.containingFile as? ElixirFile ?: return null
    val project = file.project
    if (DumbService.isDumb(project)) return null
    val inputs = (SdkInputs.getInstance(project).inputsFor(file) as? SdkInputs.Answer.Ready)?.inputs ?: return null
    val holder = ProjectModules.getInstance(project)
    val module = holder.indexed(file, inputs).envs.placed(sigil.textRange).env.module ?: return null
    val compiled = holder.expansion(file, inputs).resultAt(module, sigil.textRange) ?: return null
    val router = ((compiled.attributes.final["router"] as? AttributeValue.Known)?.term as? Term.Atom)?.name ?: return null

    return (holder.locate(asker, router, inputs) as? ProjectModules.Located.Source)?.result?.let(::routePaths)
}

/** The path of each route [router]'s `@phoenix_routes` holds when its body ends. */
private fun routePaths(router: ExpansionResult): List<String>? =
    ((router.attributes.final["phoenix_routes"] as? AttributeValue.Held)?.value as? OtpErlangList)?.map { route ->
        ((route as? OtpErlangMap)?.get(PATH) as? OtpErlangBinary)?.binaryValue()?.let(::decodeUtf8) ?: return null
    }

private val PATH = OtpErlangAtom("path")

/** [path], shown as the route writes it, inserted with each dynamic part as an empty interpolation, the caret in the first. */
private fun lookup(path: String): LookupElement {
    val inserted = path.split('/').joinToString("/") { segment ->
        when {
            segment.startsWith('*') -> INTERPOLATION
            ':' in segment -> segment.substringBefore(':') + INTERPOLATION
            else -> segment
        }
    }
    val builder = LookupElementBuilder.create(path)

    return if (inserted == path) builder else builder.withInsertHandler { context, _ ->
        context.document.replaceString(context.startOffset, context.tailOffset, inserted)
        context.editor.caretModel.moveToOffset(context.startOffset + inserted.indexOf(INTERPOLATION) + INTERPOLATION.length - 1)
    }
}

private const val INTERPOLATION = "#{}"
