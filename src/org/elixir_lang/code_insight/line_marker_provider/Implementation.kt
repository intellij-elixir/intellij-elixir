package org.elixir_lang.code_insight.line_marker_provider

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPsiElementPointer
import org.elixir_lang.Icons
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.call.Call
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.Icon

class Implementation : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarkerProvider contract: only return info for leaf elements
        if (element.firstChild != null) return null

        // Walk up from leaf to find the nearest enclosing Call.
        // markerAnchor(call) places the leaf at most 2 levels below the Call
        // (Call -> functionNameElement -> IDENTIFIER_TOKEN), so we bound the search.
        val call = generateSequence(element.parent) { it.parent }
            .take(2)
            .filterIsInstance<Call>()
            .firstOrNull()
            ?: return null

        // Verify this leaf is the marker anchor for the call
        if (element != markerAnchor(call)) return null
        if (isDocumentationSample(element)) return null

        return getLineMarkerInfo(call)
    }

    private fun getLineMarkerInfo(call: Call): LineMarkerInfo<*>? =
        if (Implementation.`is`(call)) {
            val anchor = markerAnchor(call) ?: return null
            val targets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.createValue {
                val protocols = mutableListOf<PsiElement>()

                Implementation.processProtocols(call) { protocol ->
                    protocols.add(protocol)
                }

                protocols
            }

            ProtocolsGutterIconBuilder()
                .setTargets(targets)
                .createLineMarkerInfo(anchor)
        } else if (CallDefinitionClause.`is`(call)) {
            enclosingModularMacroCall(call)?.let { modularCall ->
                CallDefinitionClause.nameArityInterval(call, ResolveState.initial())?.let { implNameArityInterval ->
                    if (Implementation.`is`(modularCall)) {
                        val anchor = markerAnchor(call) ?: return null
                        val targets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.createValue {
                            val protocols = mutableListOf<PsiElement>()

                            Implementation.processProtocols(modularCall) { defprotocol ->
                                when (defprotocol) {
                                    is Call ->
                                        for (defprotocolChild in org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(defprotocol)) {
                                            if (CallDefinitionClause.`is`(defprotocolChild)) {
                                                CallDefinitionClause
                                                    .nameArityInterval(defprotocolChild, ResolveState.initial())
                                                    ?.let { protocolNameArityInterval ->
                                                        if (protocolNameArityInterval.name == implNameArityInterval.name &&
                                                            protocolNameArityInterval.arityInterval.overlaps(
                                                                implNameArityInterval.arityInterval
                                                            )
                                                        ) {
                                                            protocols.add(defprotocolChild)
                                                        }
                                                    }
                                            }
                                        }

                                    is BeamModule ->
                                        for (callDefinition in defprotocol.callDefinitions()) {
                                            val protocolNameArityInterval = callDefinition.nameArityInterval

                                            if (protocolNameArityInterval.name == implNameArityInterval.name &&
                                                protocolNameArityInterval.arityInterval.overlaps
                                                    (implNameArityInterval.arityInterval)
                                            ) {
                                                protocols.add(callDefinition)
                                            }
                                        }
                                }

                                true
                            }

                            protocols
                        }


                        ProtocolsGutterIconBuilder()
                            .setTargets(targets)
                            .createLineMarkerInfo(anchor)
                    } else {
                        null
                    }
                }
            }
        } else {
            null
        }

    private class ProtocolsGutterIconBuilder :
        NavigationGutterIconBuilder<PsiElement>(
            Icons.Implementation.GoToProtocols,
            DEFAULT_PSI_CONVERTOR,
            PSI_GOTO_RELATED_ITEM_PROVIDER
        ) {
        override fun createGutterIconRenderer(
            pointers: NotNullLazyValue<out MutableList<SmartPsiElementPointer<*>>>,
            renderer: Computable<out PsiElementListCellRenderer<*>>,
            empty: Boolean,
            navigationHandler: GutterIconNavigationHandler<PsiElement>?
        ): NavigationGutterIconRenderer {
            return ProtocolsNavigationGutterIconRenderer(
                popupTitle = myPopupTitle,
                emptyText = myEmptyText,
                pointers = pointers,
                cellRenderer = renderer,
                alignment = myAlignment,
                icon = myIcon,
                tooltipText = myTooltipText,
                empty = empty
            )
        }
    }

    private class ProtocolsNavigationGutterIconRenderer(
        popupTitle: String?,
        emptyText: String?,
        pointers: NotNullLazyValue<out MutableList<SmartPsiElementPointer<*>>>,
        cellRenderer: Computable<out PsiElementListCellRenderer<*>>,
        private val alignment: Alignment,
        private val icon: Icon,
        private val tooltipText: String?,
        private val empty: Boolean
    ) : NavigationGutterIconRenderer(popupTitle, emptyText, cellRenderer, pointers) {
        override fun isNavigateAction(): Boolean = !empty
        override fun getIcon(): Icon = icon
        override fun getTooltipText(): String? = tooltipText
        override fun getAlignment(): Alignment = alignment

        override fun navigate(event: MouseEvent?, elt: PsiElement?) {
            if (event == null || elt == null) return

            val targets = targetElements.filterIsInstance<NavigatablePsiElement>().toTypedArray()
            val renderer = myCellRenderer.compute()

            Arrays.sort(targets, Comparator.comparing(renderer::getComparingObject))
            val name = escapedName(elt)

            PsiElementListNavigator.openTargets(
                event,
                targets,
                "<html><body>Choose Protocols of <b>${name}</b> (${targets.size} found)</body></html>",
                "Protocols of $name",
                renderer
            )
        }
    }
}
