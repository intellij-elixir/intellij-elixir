package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL
import com.intellij.codeInsight.documentation.DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL_REF_SEPARATOR
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult

/** Follows a link in Elixir documentation; a link only another language's provider resolves is not followed. */
internal class ElixirDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is ElixirDocumentationTarget || !url.startsWith(PSI_ELEMENT_PROTOCOL)) {
            return null
        }

        val withoutProtocol = url.removePrefix(PSI_ELEMENT_PROTOCOL)
        val separator = withoutProtocol.lastIndexOf(PSI_ELEMENT_PROTOCOL_REF_SEPARATOR)
        val link = if (separator >= 0) withoutProtocol.substring(0, separator) else withoutProtocol
        val anchor =
            if (separator >= 0) withoutProtocol.substring(separator + PSI_ELEMENT_PROTOCOL_REF_SEPARATOR.length) else null

        return DocumentationLink
            .element(link, target.targetElement)
            ?.let { ElixirDocumentationTarget.of(it, null, anchor) }
            ?.let { LinkResolveResult.resolvedTarget(it) }
    }
}
