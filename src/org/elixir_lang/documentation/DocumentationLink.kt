package org.elixir_lang.documentation

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import org.elixir_lang.expander.EscapeMap
import org.elixir_lang.expander.Unescaped
import org.elixir_lang.expander.unescapeString
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.reference.Resolver
import java.util.regex.Pattern

private val LOG = logger<DocumentationLink>()

/** What a `psi_element://` link in rendered documentation opens, whoever follows it. */
internal object DocumentationLink {
    private val relativePattern: Pattern =
        Pattern.compile("((?<kind>[ct]):)?((?<module>.+)\\.)?(?<relative>.+)/(?<arity>\\d+)")

    /** A function named in quotes after its module, as `Mod."a.b"/1`, which is how `InspectAtom.remoteCall` writes a name that is no plain identifier. */
    private val quotedPattern: Pattern =
        Pattern.compile("((?<kind>[ct]):)?((?<module>.+)\\.)?(?<name>\"(?:[^\"\\\\]|\\\\.)*\")/(?<arity>\\d+)")

    /**
     * A link to a function, callback or type, read once for whoever draws it and whoever follows it: its [kind], `c` or
     * `t` for a callback or type and `null` for a function, the [module] it names if it names one, its [name] as written,
     * in quotes where it was quoted, and its [arity], which is `null` when it is too big for an integer and so names no
     * function. [atomName] says which atom the name is.
     */
    class Parsed(val kind: String?, val module: String?, val name: String, val arity: Int?)

    /** [link] read as a link to a function, callback or type; `null` where it is a module's, with no `name/arity`. */
    fun parse(link: String): Parsed? {
        val quoted = quotedPattern.matcher(link)
        val matcher = relativePattern.matcher(link)

        return if (quoted.matches()) {
            Parsed(
                quoted.group("kind"),
                quoted.group("module"),
                quoted.group("name"),
                quoted.group("arity").toIntOrNull()
            )
        } else if (matcher.matches()) {
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

    /**
     * The atom a function or type named [written] in a link is: the text inside quotes, unescaped as Elixir unescapes a
     * quoted atom, else the identifier as Elixir normalizes it at the language level of [context].
     */
    fun atomName(written: String, context: PsiElement): String {
        val level = { languageLevelFor(context) }

        return if (written.length >= 2 && written.startsWith('"') && written.endsWith('"')) {
            when (val unescaped = unescapeString(written.substring(1, written.length - 1).toByteArray(), EscapeMap.DEFAULT, level())) {
                is Unescaped.Text -> String(unescaped.bytes)
                is Unescaped.Failed -> written
            }
        } else {
            identifierAtomName(written, level)
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
                    val relative = atomName(parsed.name, context)

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
