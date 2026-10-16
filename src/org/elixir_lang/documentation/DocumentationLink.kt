package org.elixir_lang.documentation

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.reference.Resolver
import java.util.regex.Pattern

private val LOG = logger<DocumentationLink>()

/** What a `psi_element://` link in rendered documentation opens, whoever follows it. */
internal object DocumentationLink {
    private val relativePattern: Pattern =
        Pattern.compile("((?<kind>[ct]):)?((?<module>.+)\\.)?(?<relative>.+)/(?<arity>\\d+)")

    /** The element [link] opens when followed from [context]: a function, callback or type, else a module. */
    fun element(link: String, context: PsiElement): PsiElement? {
        val matcher = relativePattern.matcher(link)

        return if (matcher.matches()) {
            val module = matcher.group("module")
            val arity = matcher.group("arity").toIntOrNull() ?: return null

            when (val kind = matcher.group("kind")) {
                null, "c" -> FunctionLink.target(
                    context,
                    module,
                    matcher.group("relative"),
                    arity,
                    callback = kind == "c"
                )

                "t" -> {
                    val relative = identifierAtomName(matcher.group("relative")) { languageLevelFor(context) }

                    val resolveResults = if (module != null) {
                        MarkdownFlavourDescriptor
                            .modulars(context, module)
                            .flatMap { modular ->
                                org.elixir_lang.psi.scope.type.MultiResolve.resolveResults(
                                    relative,
                                    arity,
                                    false,
                                    modular
                                )
                            }
                    } else {
                        org.elixir_lang.psi.scope.type.MultiResolve.resolveResults(relative, arity, false, context)
                    }

                    resolveResults
                        .let { Resolver.preferred(context, false, it) }
                        .firstNotNullOfOrNull { it.element }
                }

                else -> {
                    LOG.warn("Don't know how to find element for link ($link) of kind ($kind) for ${context.javaClass.name}")

                    null
                }
            }
        } else {
            MarkdownFlavourDescriptor.modulars(context, link).firstOrNull()
        }
    }
}
