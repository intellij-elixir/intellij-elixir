package org.elixir_lang.code_insight.line_marker_provider

import com.intellij.codeInsight.CodeInsightBundle
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
import org.elixir_lang.navigation.ElixirImplementationCellRenderer
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.Icon

internal class Protocol : LineMarkerProvider {
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
        if (Protocol.`is`(call)) {
            val anchor = markerAnchor(call) ?: return null
            val targets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.createValue {
                val implementations = mutableListOf<PsiElement>()

                Protocol.processImplementations(call) { implementation ->
                    implementations.add(implementation)
                }

                implementations
            }

            ImplsGutterIconBuilder()
                .setTargets(targets)
                .createLineMarkerInfo(anchor)
        } else if (CallDefinitionClause.`is`(call)) {
            enclosingModularMacroCall(call)?.let { modularCall ->
                CallDefinitionClause.nameArityInterval(call, ResolveState.initial())?.let { protocolNameArityInterval ->
                    if (Protocol.`is`(modularCall)) {
                        val anchor = markerAnchor(call) ?: return null
                        val targets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.createValue {
                            val implementations = mutableListOf<PsiElement>()

                            Protocol.processImplementations(modularCall) { defimpl ->
                                when (defimpl) {
                                    is Call ->
                                        for (defimplChild in org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(defimpl)) {
                                            if (CallDefinitionClause.`is`(defimplChild)) {
                                                CallDefinitionClause
                                                    .nameArityInterval(defimplChild, ResolveState.initial())
                                                    ?.let { implNameArityInterval ->
                                                        if (implNameArityInterval.name == protocolNameArityInterval.name &&
                                                            implNameArityInterval.arityInterval.overlaps(
                                                                protocolNameArityInterval.arityInterval
                                                            )
                                                        ) {
                                                            implementations.add(defimplChild)
                                                        }
                                                    }
                                            }
                                        }

                                    is BeamModule ->
                                        for (callDefinition in defimpl.callDefinitions()) {
                                            val implNameArityInterval = callDefinition.nameArityInterval

                                            if (implNameArityInterval.name == protocolNameArityInterval.name &&
                                                implNameArityInterval.arityInterval.overlaps
                                                    (protocolNameArityInterval.arityInterval)
                                            ) {
                                                implementations.add(callDefinition)
                                            }
                                        }
                                }

                                true
                            }

                            implementations
                        }


                        ImplsGutterIconBuilder()
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

    private class ImplsGutterIconBuilder :
        NavigationGutterIconBuilder<PsiElement>(
            Icons.Protocol.GoToImplementations,
            DEFAULT_PSI_CONVERTOR,
            PSI_GOTO_RELATED_ITEM_PROVIDER
        ) {
        init {
            // Render each implementing clause the same way as the Go To Implementation (Ctrl+Alt+B) popup
            // instead of the default location-string rows, which are too wide to be usable.
            setCellRenderer { ElixirImplementationCellRenderer() }
        }

        override fun createGutterIconRenderer(
            pointers: NotNullLazyValue<out MutableList<SmartPsiElementPointer<*>>>,
            renderer: Computable<out PsiElementListCellRenderer<*>>,
            empty: Boolean,
            navigationHandler: GutterIconNavigationHandler<PsiElement>?
        ): NavigationGutterIconRenderer {
            return ImplsNavigationGutterIconRenderer(
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

    private class ImplsNavigationGutterIconRenderer(
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
                CodeInsightBundle.message("goto.implementation.chooserTitle", name, targets.size, ""),
                CodeInsightBundle.message("goto.implementation.findUsages.title", name, targets.size),
                renderer
            )
        }
    }
}
