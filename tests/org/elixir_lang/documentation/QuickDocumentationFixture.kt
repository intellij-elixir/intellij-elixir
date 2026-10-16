package org.elixir_lang.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.platform.backend.documentation.impl.resolveLinkToTarget
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * Renders Quick Documentation for the fixture's caret exactly as pressing Ctrl+Q would, or returns
 * `null` when the IDE would show no documentation (no target resolved, or no docs for the resolved
 * target).
 *
 * Uses the v2 documentation-target API ([IdeDocumentationTargetProvider] +
 * [computeDocumentationBlocking]) - the same entry point the platform's own Quick Doc tests use
 * (e.g. Kotlin's `AbstractFirQuickDocTest`) - rather than the removed-for-`v2` `DocumentationManager`.
 *
 * An extension on the fixture rather than a method on [QuickDocumentationTestCase] so tests that
 * need a different base class - [org.elixir_lang.heex.reference.HeexHostTestCase], which enables
 * `~H` injection - can drive the same pipeline without duplicating it.
 */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.quickDocumentationAtCaret(project: Project): String? =
    IdeDocumentationTargetProvider.getInstance(project)
        .documentationTargets(editor, file, editor.caretModel.offset)
        .firstOrNull()
        ?.let(::renderedDocumentation)

/**
 * The documentation completion's popup shows for [lookupElement], as the platform asks for it: symbol targets, then
 * lookup-element targets, then the lookup's own PSI element.
 */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.quickDocumentationOf(project: Project, lookupElement: LookupElement): String? =
    IdeDocumentationTargetProvider.getInstance(project)
        .documentationTargets(editor, file, lookupElement)
        .firstOrNull()
        ?.let(::renderedDocumentation)

/** The Ctrl+hover text for the target at the caret. */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.documentationHintAtCaret(project: Project): String? =
    IdeDocumentationTargetProvider.getInstance(project)
        .documentationTargets(editor, file, editor.caretModel.offset)
        .firstOrNull()
        ?.let { target -> ReadAction.compute<String?, RuntimeException> { target.computeDocumentationHint() } }

/**
 * The documentation a click on the link [url] in the rendered documentation at the caret opens, as the platform follows
 * it: through the registered link handlers, not through a provider the test picks. `null` when nothing is reached.
 */
@Suppress("UnstableApiUsage", "OverrideOnly")
fun CodeInsightTestFixture.followedLinkAtCaret(project: Project, url: String): String? =
    followedDocumentation(project, url)?.html

/** The anchor the documentation a click on [url] opens is scrolled to, or `null` when nothing is reached or it has none. */
@Suppress("UnstableApiUsage", "OverrideOnly")
fun CodeInsightTestFixture.followedAnchorAtCaret(project: Project, url: String): String? =
    followedDocumentation(project, url)?.anchor

@Suppress("UnstableApiUsage", "OverrideOnly")
private fun CodeInsightTestFixture.followedDocumentation(project: Project, url: String): DocumentationData? {
    val target = IdeDocumentationTargetProvider.getInstance(project)
        .documentationTargets(editor, file, editor.caretModel.offset)
        .firstOrNull()
        ?: return null
    val pointer = pointerTo(target)
    val linked = runBlockingMaybeCancellable { resolveLinkToTarget(pointer, url) } ?: return null

    return computeDocumentationBlocking(linked)
}

// DocumentationTarget is @ApiStatus.OverrideOnly, so calling createPointer() on the
// interface-typed target trips the OverrideOnly inspection. The call is unavoidable and
// sanctioned: it dispatches to the concrete platform target's override, and the @TestOnly
// helper's contract is `computeDocumentationBlocking(target.createPointer())` - the exact
// form the platform's own Quick Doc tests (Kotlin AbstractFirQuickDocTest, DevKit
// XmlDescriptorDocumentationProviderTest) use.
@Suppress("OverrideOnly", "UnstableApiUsage")
private fun renderedDocumentation(target: DocumentationTarget): String? =
    computeDocumentationBlocking(pointerTo(target))?.html

@Suppress("OverrideOnly", "UnstableApiUsage")
private fun pointerTo(target: DocumentationTarget): Pointer<out DocumentationTarget> =
    ReadAction.compute<Pointer<out DocumentationTarget>, RuntimeException> { target.createPointer() }

/** The anchor the documentation at the caret opens at, as the platform reads it from the rendered result. */
@Suppress("OverrideOnly", "UnstableApiUsage")
fun CodeInsightTestFixture.documentationAnchorAtCaret(project: Project): String? =
    documentationTargetAtCaret(project)?.let { computeDocumentationBlocking(pointerTo(it))?.anchor }

/** The target the platform offers first at the caret. */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.documentationTargetAtCaret(project: Project): DocumentationTarget? =
    IdeDocumentationTargetProvider.getInstance(project)
        .documentationTargets(editor, file, editor.caretModel.offset)
        .firstOrNull()

/** [target] dereferenced from its pointer in a later read action, once [between] has run. */
@Suppress("OverrideOnly", "UnstableApiUsage")
fun dereferencedAfterPointerRoundTrip(target: DocumentationTarget, between: () -> Unit = {}): DocumentationTarget? {
    val pointer = pointerTo(target)

    between()

    return ReadAction.compute<DocumentationTarget?, RuntimeException> { pointer.dereference() }
}

/** [target]'s documentation after its pointer is dereferenced in a later read action, once [between] has run. */
@Suppress("OverrideOnly", "UnstableApiUsage")
fun renderedAfterPointerRoundTrip(target: DocumentationTarget, between: () -> Unit = {}): String? =
    dereferencedAfterPointerRoundTrip(target, between)?.let { computeDocumentationBlocking(pointerTo(it))?.html }
