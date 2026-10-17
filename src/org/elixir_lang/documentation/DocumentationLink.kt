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

    /**
     * A link to a function, callback or type, read once for whoever draws it and whoever follows it: its [kind], `c` or
     * `t` for a callback or type and `null` for a function, the [module] it names if it names one, its [name] and its
     * [arity], which is `null` when it is too big for an integer and so names no function.
     */
    class Parsed(val kind: String?, val module: String?, val name: String, val arity: Int?)

    /** [link] read as a link to a function, callback or type; `null` where it is a module's, with no `name/arity`. */
    fun parse(link: String): Parsed? {
        val matcher = relativePattern.matcher(link)

        return if (matcher.matches()) {
            Parsed(
                matcher.group("kind"),
                matcher.group("module"),
                matcher.group("relative"),
                matcher.group("arity").toIntOrNull()
            )
        } else {
            null
        }
    }

    /** The element [link] opens when followed from [context]: a function, callback or type, else a module. */
    fun element(link: String, context: PsiElement): PsiElement? {
        val parsed = parse(link)

        return if (parsed != null) {
            val module = parsed.module
            val arity = parsed.arity ?: return null

            when (val kind = parsed.kind) {
                null, "c" -> FunctionLink.target(
                    context,
                    module,
                    parsed.name,
                    arity,
                    callback = kind == "c"
                )

                "t" -> {
                    val relative = identifierAtomName(parsed.name) { languageLevelFor(context) }

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
