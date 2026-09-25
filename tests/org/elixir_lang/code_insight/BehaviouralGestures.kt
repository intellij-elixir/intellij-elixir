@file:Suppress("UnstableApiUsage")

package org.elixir_lang.code_insight

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.hint.ParameterInfoListener
import com.intellij.find.usages.api.PsiUsage
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageOptions
import com.intellij.find.usages.impl.AllSearchOptions
import com.intellij.find.usages.impl.buildQuery
import com.intellij.find.usages.impl.searchTargets
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.impl.FileUpdates
import com.intellij.refactoring.rename.impl.RenameOptions
import com.intellij.refactoring.rename.impl.TextOptions
import com.intellij.refactoring.rename.impl.buildQuery
import com.intellij.refactoring.rename.impl.prepareRename
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiUtilBase
import com.intellij.testFramework.AutoPopupParameterInfoTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable

/**
 * Reusable behavioural gestures for driving IDE features the way a user would - Ctrl+Click
 * (Go To Declaration / Usage), completion popup, and Find Usages - and asserting on the
 * user-visible outcome rather than hand-resolved PSI references.
 *
 * Shared by the navigation/completion/find-usages test suites so the boilerplate for each gesture
 * lives in exactly one place.
 */

/**
 * One resolved Go-To-Declaration target: the [destination] leaf the navigation actually lands on, resolved
 * from the target's `NavigationRequest`. `null` when the request is not a plain source/PSI one (e.g. a
 * directory target), or - the bug this suite guards against - when the gesture "decided" to navigate but
 * resolved nowhere.
 */
data class GtduTarget(val destination: PsiElement?)

/**
 * The faithful outcome of the real Go-To-Declaration-or-Usages gesture at the caret, carrying the actual
 * navigation targets the platform resolved - not merely which branch it chose.
 *
 * See [gtduNavigationAtCaret] for the full rationale; in short:
 *  - [None] - the gesture resolves to nothing. Ctrl+Click / Ctrl+B does nothing, the caret stays put.
 *  - [GotoDeclaration] - the gesture navigates to one or more [GtduTarget]s (each carrying the [GtduTarget.destination]
 *    leaf the navigation lands on, so "navigated to the real declaration" is distinguishable from "navigated nowhere").
 *  - [ShowUsages] - the gesture opens Show Usages (the caret is on a declaration); `variants` is the
 *    presentable text of each target variant offered.
 */
sealed interface GtduNavigation {
    object None : GtduNavigation
    data class GotoDeclaration(val targets: List<GtduTarget>) : GtduNavigation
    data class ShowUsages(val variants: List<String>) : GtduNavigation
}

/**
 * Reproduces the platform's real `GotoDeclarationOrUsageHandler2` gesture faithfully and returns the
 * **actual navigation the platform resolved** (destination leaf per target, or the Show-Usages branch),
 * wrapped in [GtduNavigation].
 *
 * This is the single faithful foundation for every navigation/Find-Usages *decision* and *destination*
 * assertion in the suite.
 *
 * WHAT THE REAL HANDLER DOES (and why the harness must mirror it): `GotoDeclarationOrUsageHandler2`
 * resolves `fromGTDProviders(project, editor, offset)?.toGTDUActionData() ?: gotoDeclarationOrUsages(file, offset)`
 * - i.e. it consults the `GotoDeclarationHandler` EP providers *first*, and only falls back to the Symbol/
 * target path if no EP contributes. This ordering matters here specifically: the decompiled-`.beam` bug
 * originally aborted the gesture *inside* `fromGTDProviders`, when a third-party `GotoDeclarationHandler`
 * (JS/LESS/...) called `getNode().getElementType()` on the coarse, `getNode()==null` definition leaf and
 * threw. A harness that skipped `fromGTDProviders` would silently diverge from the real IDE. So this
 * front-runs the EP providers exactly as the handler does (an EP result always maps to go-to-declaration,
 * never show-usages - `toGTDUActionData` wraps it as `GTD`).
 *
 * WHY NOT THE PUBLIC `@TestOnly` HOOK: `testGTDUOutcomeInNonBlockingReadAction` is faithful to the handler
 * (it too front-runs the EP providers), but returns only the *branch decision* (`GTD`/`SU`/`null`). It
 * throws away the *resolved destination*, which the beam bug demands: the failure mode is "the gesture
 * decides GTD but resolves onto the coarse self-named definition, i.e. navigates nowhere". The coarse
 * `BeamTypeDefinition`/`BeamCallDefinition` spans the WHOLE definition, so a caret-position read after
 * `performEditorAction` cannot tell "went nowhere (self)" from "landed on the definition" - it stayed
 * green with the fix removed. Only the resolved [GtduTarget.destination] leaf distinguishes broken from
 * fixed; capturing it is why this reaches past the public hook into the internal result tree.
 *
 * WHY REFLECTION: `gotoDeclarationOrUsages` / `fromGTDProviders` and the `GTDActionData` /
 * `GTDUActionData` / `GTDUActionResult` / `NavigationActionResult` result tree (and the `NavigationRequest`
 * we resolve the destination from) are Kotlin-`internal` (and `@ApiStatus.Internal`) - deliberately hidden
 * so JetBrains can evolve them, which is exactly why only the decision enum is exposed publicly. From a
 * plugin's test module the only way to reach the resolved destination is reflectively. The public method
 * names are stable in this platform build (`result`, `getNavigationActionResult`, `getRequestor`,
 * `getTargets`, `navigationRequest`, `getTargetVariants`); `setAccessible(true)` is required because the
 * methods, though `public`, are declared on non-public (`internal`) classes.
 *
 * Runs on a pooled thread under a non-blocking read action (like [psiUsagesAtCaret]) because the real
 * action resolves off the EDT and Show-Usages variant building may touch the index.
 */
fun CodeInsightTestFixture.gtduNavigationAtCaret(): GtduNavigation {
    val targetFile = file
    val offset = caretOffset
    val project = project
    val editor = editor
    return ApplicationManager.getApplication().executeOnPooledThread(Callable {
        ReadAction.nonBlocking(Callable { resolveGtduNavigation(project, editor, targetFile, offset) }).executeSynchronously()
    }).get()
}

private fun resolveGtduNavigation(project: Project, editor: Editor, targetFile: PsiFile, offset: Int): GtduNavigation {
    // Faithful to GotoDeclarationOrUsageHandler2: fromGTDProviders(...)?.toGTDUActionData() ?: gotoDeclarationOrUsages(...).
    // An EP-provider result is always go-to-declaration (toGTDUActionData wraps its NavigationActionResult as GTD);
    // it falls through to the Symbol/target path only when no provider contributes a navigable result.
    fromGtdProviders(project, editor, offset)?.call("result")?.let { epResult ->
        return GtduNavigation.GotoDeclaration(epResult.toGtduTargets(project))
    }

    val actionData = invokeGtdu(targetFile, offset) ?: return GtduNavigation.None
    val result = actionData.call("result") ?: return GtduNavigation.None
    return when (result.javaClass.simpleName) {
        // GTDUActionResult.GTD - carries a NavigationActionResult (Single- or MultipleTargets).
        "GTD" -> {
            val navigationActionResult = result.call("getNavigationActionResult") ?: return GtduNavigation.None
            GtduNavigation.GotoDeclaration(navigationActionResult.toGtduTargets(project))
        }
        // GTDUActionResult.SU - the Show Usages branch; carries the offered target variants.
        "SU" -> GtduNavigation.ShowUsages(
            (result.call("getTargetVariants") as List<*>).map { variant ->
                (variant!!.call("getPresentation") as TargetPresentation).presentableText
            }
        )
        else -> GtduNavigation.None
    }
}

/** Reflectively invokes the internal `GtduKt.gotoDeclarationOrUsages(file, offset)`; returns a `GTDUActionData?`. */
private fun invokeGtdu(targetFile: PsiFile, offset: Int): Any? =
    Class.forName("com.intellij.codeInsight.navigation.impl.GtduKt")
        .getDeclaredMethod("gotoDeclarationOrUsages", PsiFile::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(null, targetFile, offset)

/** Reflectively invokes the internal `GtdKt.gotoDeclaration(file, offset)`; returns a `GTDActionData?`. */
private fun invokeGtd(targetFile: PsiFile, offset: Int): Any? =
    Class.forName("com.intellij.codeInsight.navigation.impl.GtdKt")
        .getDeclaredMethod("gotoDeclaration", PsiFile::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(null, targetFile, offset)

/**
 * Reflectively invokes the internal `GtdProvidersKt.fromGTDProviders(project, editor, offset)` - the
 * `GotoDeclarationHandler` EP-provider stage both real handlers (`GotoDeclarationOrUsageHandler2` and the
 * Ctrl+B [com.intellij.codeInsight.navigation.actions.GotoDeclarationOnlyHandler2]) run before the Symbol
 * path. Returns a `GTDActionData?` (whose `result()` is a `NavigationActionResult?`), or `null` when no EP
 * provider contributes a target at the offset (the common case for Elixir, which registers none).
 */
private fun fromGtdProviders(project: Project, editor: Editor, offset: Int): Any? =
    Class.forName("com.intellij.codeInsight.navigation.impl.GtdProvidersKt")
        .getDeclaredMethod("fromGTDProviders", Project::class.java, Editor::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(null, project, editor, offset)

/**
 * Reproduces the *pure* Go To Declaration gesture (Ctrl+B, `GotoDeclarationOnlyHandler2`) faithfully:
 * `fromGTDProviders(project, editor, offset) ?: gotoDeclaration(file, offset)`, then `.result()`. Returns
 * the resolved declaration targets, or `null` when there is nothing to navigate to.
 *
 * This is a DIFFERENT real gesture from [gtduNavigationAtCaret]: that one is the combined Ctrl+Click that
 * *chooses* between go-to-declaration and show-usages; this always resolves the declaration target, even
 * at a caret the combined gesture answers with Show Usages (e.g. a qualified-alias qualifier that is both
 * a reference and sits on a declaration). Decision assertions therefore use [gtduNavigationAtCaret];
 * destination assertions - "Go To Declaration lands on X" - use this.
 *
 * Shares the EP-provider front-run and internal-result unwrapping (and hence the reflection rationale) of
 * [gtduNavigationAtCaret]; a `GTDActionData.result()` is the `NavigationActionResult` directly (no GTD/SU
 * branch wrapper).
 */
fun CodeInsightTestFixture.gotoDeclarationTargetsAtCaret(): List<GtduTarget>? {
    val targetFile = file
    val offset = caretOffset
    val project = project
    val editor = editor
    return ApplicationManager.getApplication().executeOnPooledThread(Callable {
        ReadAction.nonBlocking(Callable { resolveGotoDeclarationTargets(project, editor, targetFile, offset) }).executeSynchronously()
    }).get()
}

private fun resolveGotoDeclarationTargets(project: Project, editor: Editor, targetFile: PsiFile, offset: Int): List<GtduTarget>? {
    // Faithful to GotoDeclarationOnlyHandler2: fromGTDProviders(...) ?: gotoDeclaration(file, offset), then result().
    val actionData = fromGtdProviders(project, editor, offset) ?: invokeGtd(targetFile, offset) ?: return null
    val navigationActionResult = actionData.call("result") ?: return null
    return navigationActionResult.toGtduTargets(project)
}

/**
 * Unwraps a `NavigationActionResult` (Single- or MultipleTargets) into [GtduTarget]s. Both a `SingleTarget`
 * and each `MultipleTargets`' `LazyTargetWithPresentation` expose `getRequestor()`, whose
 * `navigationRequest()` yields the destination.
 */
private fun Any.toGtduTargets(project: Project): List<GtduTarget> =
    when (javaClass.simpleName) {
        "SingleTarget" -> listOf(toGtduTarget(project))
        "MultipleTargets" -> (call("getTargets") as List<*>).mapNotNull { it?.toGtduTarget(project) }
        else -> emptyList()
    }

/** Builds a [GtduTarget] from a `SingleTarget` / `LazyTargetWithPresentation` (both share `getRequestor()`). */
private fun Any.toGtduTarget(project: Project): GtduTarget =
    GtduTarget(destination = resolveDestination(project, call("getRequestor")))

/**
 * Resolves the leaf a navigation target lands on, from its `NavigationRequestor.navigationRequest()`:
 * a [com.intellij.platform.backend.navigation.impl.SourceNavigationRequest] (or a subclass such as
 * `SharedSourceNavigationRequest`) gives a file + offset (we use the caret offset marker, so we land on
 * the same leaf the caret would move to); a
 * [com.intellij.platform.backend.navigation.impl.RawNavigationRequest] gives a [com.intellij.pom.Navigatable]
 * that is itself the target `PsiElement`. Other request kinds (e.g. directory) are not navigation-to-leaf
 * and resolve to `null`.
 */
private fun resolveDestination(project: Project, requestor: Any?): PsiElement? {
    val request = requestor?.call("navigationRequest") ?: return null
    val navImpl = "com.intellij.platform.backend.navigation.impl."
    return when {
        // SourceNavigationRequest and its subclasses (e.g. SharedSourceNavigationRequest) - file + markers.
        Class.forName("${navImpl}SourceNavigationRequest").isInstance(request) -> {
            val virtualFile = request.call("getFile") as? VirtualFile ?: return null
            val marker = request.call("getOffsetMarker") ?: request.call("getElementRangeMarker") ?: return null
            val startOffset = marker.call("getStartOffset") as Int
            PsiManager.getInstance(project).findFile(virtualFile)?.findElementAt(startOffset)
        }
        // RawNavigationRequest - wraps a Navigatable that is itself the target PsiElement.
        Class.forName("${navImpl}RawNavigationRequest").isInstance(request) ->
            request.call("getNavigatable") as? PsiElement
        else -> null
    }
}

private fun Any.call(methodName: String): Any? =
    javaClass.getMethod(methodName).apply { isAccessible = true }.invoke(this)

/** Asserts the Ctrl+Click / Ctrl+B gesture at the caret resolves to "go to declaration" (a real target). */
fun CodeInsightTestFixture.assertGotoDeclarationChosenAtCaret(message: String? = null) {
    val navigation = gtduNavigationAtCaret()
    assertTrue(
        message ?: "Ctrl+Click should choose go-to-declaration, but resolved to $navigation",
        navigation is GtduNavigation.GotoDeclaration
    )
}

/** Asserts the gesture at the caret resolves to "show usages". */
fun CodeInsightTestFixture.assertShowUsagesChosenAtCaret(message: String? = null) {
    val navigation = gtduNavigationAtCaret()
    assertTrue(
        message ?: "Ctrl+Click should choose show-usages, but resolved to $navigation",
        navigation is GtduNavigation.ShowUsages
    )
}

/** Asserts the gesture at the caret does nothing (no declaration and no usages). */
fun CodeInsightTestFixture.assertNoNavigationAtCaret(message: String? = null) {
    val navigation = gtduNavigationAtCaret()
    assertTrue(
        message ?: "Ctrl+Click should not navigate, but resolved to $navigation",
        navigation === GtduNavigation.None
    )
}

/**
 * The PSI leaf the Go To Declaration gesture actually navigates to at the caret, for the common
 * single-target case - the faithful replacement for the old caret-inspecting `gotoDeclarationDestination` /
 * `gotoDeclarationTargetElement`. Resolves the destination from the target's real `NavigationRequest`
 * (so it follows navigation into other source files or a decompiled `.beam`), rather than reading the
 * caret after `performEditorAction` (which, in a decompiled `.beam`, cannot tell "went nowhere" from
 * "landed on the definition" - see [gtduNavigationAtCaret]). Fails if the gesture did not resolve to
 * exactly one target.
 */
fun CodeInsightTestFixture.gotoDeclarationDestinationAtCaret(): PsiElement? = gotoDeclarationSingleTargetAtCaret().destination

private fun CodeInsightTestFixture.gotoDeclarationSingleTargetAtCaret(): GtduTarget {
    val targets = gotoDeclarationTargetsAtCaret()
        ?: throw AssertionError("Expected Go To Declaration to resolve a target, but it navigated nowhere")
    return targets.singleOrNull()
        ?: throw AssertionError("Expected a single Go To Declaration target, got ${targets.size}")
}

/**
 * The completion candidates offered at the caret: empty when nothing was offered, `null` when a single
 * match was auto-inserted.
 */
fun CodeInsightTestFixture.completionStringsAtCaret(): List<String>? {
    complete(CompletionType.BASIC)
    return lookupElementStrings
}

/**
 * The completion candidates offered at the caret, with a lone match listed rather than auto-inserted, so
 * one matching candidate and several are asked the same question.
 */
fun CodeInsightTestFixture.completionCandidatesAtCaret(): List<String> {
    val settings = CodeInsightSettings.getInstance()
    val autocompleteWas = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
    settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false

    try {
        complete(CompletionType.BASIC)

        return lookupElementStrings
            ?: throw AssertionError("Expected the completion lookup to open, but a candidate was auto-inserted")
    } finally {
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteWas
    }
}

/**
 * Drives completion at the caret for the **single-candidate** case: with a non-empty prefix a lone
 * match auto-inserts instead of opening the popup. Asserts no multi-candidate popup was shown (i.e.
 * [complete] returned `null`) and returns the resulting document text, so callers can assert what the
 * sole completion inserted.
 *
 * Complements [completionStringsAtCaret], which covers the multi-candidate popup case (it also returns
 * `null` on auto-insert, but discards the inserted text this helper exposes).
 */
fun CodeInsightTestFixture.completeSoleCandidateAtCaret(): String {
    assertNull(
        "Expected a single auto-inserted completion, but a multi-candidate popup was shown",
        complete(CompletionType.BASIC)
    )
    return file.text
}

/**
 * What one completion gesture did: the candidates offered ([candidates]; empty when nothing was
 * offered, `null` when a lone candidate auto-inserted) and the document [text] afterwards, which says
 * what that lone candidate inserted.
 */
data class CompletionAttempt(val candidates: List<String>?, val text: String)

/**
 * Drives completion at the caret and reports both what was offered and what the document became, for
 * callers that must know what a lone auto-inserted candidate wrote.
 * Assertions that only care about one half want [completionStringsAtCaret] or
 * [completeSoleCandidateAtCaret] instead.
 */
fun CodeInsightTestFixture.completionAttemptAtCaret(): CompletionAttempt {
    complete(CompletionType.BASIC)

    return CompletionAttempt(lookupElementStrings, file.text)
}

/**
 * Accepts the completion candidate named [lookupString] at the caret with [completionChar] and returns
 * the resulting document text - the whole user action, popup through to insertion.
 *
 * Asserting on the offered strings stops one step short of what the user sees: a candidate can be
 * offered under a lookup string that inserts something else, or over a replacement range that does not
 * cover what it replaces, and no assertion over [completionStringsAtCaret] can catch either.
 */
fun CodeInsightTestFixture.completeCandidateAtCaret(
    lookupString: String,
    completionChar: Char = '\t'
): String {
    acceptCompletionCandidate(lookupString, completionChar)

    return file.text
}

/**
 * [completeCandidateAtCaret] for a fixture that may offer only one candidate. When a lone match
 * auto-inserts, the document is returned as it stands and the caller must assert on the text to know
 * which match that was. When nothing is offered it throws, as [completeCandidateAtCaret] does.
 */
fun CodeInsightTestFixture.completeCandidateOrSoleMatchAtCaret(
    lookupString: String,
    completionChar: Char = '\t',
    matches: (String) -> Boolean = { it == lookupString }
): String {
    completeBasic()?.let { candidates ->
        val candidate = candidates.firstOrNull { matches(it.lookupString) }?.lookupString ?: lookupString
        acceptCompletionCandidate(candidates, candidate, completionChar)
    }

    return file.text
}

/**
 * Selects the candidate named [lookupString] from an open lookup and finishes it with [completionChar].
 *
 * The lookup is finished directly rather than by typing the character, because that is what the editor
 * does: an open lookup consumes the keystroke, so `TypedHandler` never runs. The candidate is selected
 * explicitly rather than relying on the lookup's ordering, so a test pins the popup rather than which
 * candidate happened to sort first. The fixture must offer more than one candidate, or the lookup
 * auto-inserts and there is nothing to accept.
 */
private fun CodeInsightTestFixture.acceptCompletionCandidate(lookupString: String, completionChar: Char) {
    val candidates = completeBasic()
        ?: throw AssertionError("Expected a completion lookup to open, but a single candidate was auto-inserted")
    acceptCompletionCandidate(candidates, lookupString, completionChar)
}

private fun CodeInsightTestFixture.acceptCompletionCandidate(
    candidates: Array<LookupElement>,
    lookupString: String,
    completionChar: Char
) {
    val candidate = candidates.firstOrNull { it.lookupString == lookupString }
        ?: throw AssertionError(
            "Expected a '$lookupString' completion candidate, got ${candidates.map { it.lookupString }}"
        )

    (lookup as LookupImpl).currentItem = candidate

    finishLookup(completionChar)
}

/**
 * The [PsiUsage]s the Find Usages tool window would display for the search target resolved at the
 * caret (including the declaration usage; empty when the caret resolves to no unambiguous target).
 *
 * Drives the real Find Usages pipeline - `caret -> searchTargets -> buildQuery -> PsiUsage`. The symbol
 * search runs off the EDT under a read action because it executes a name-anchored word/index search
 * (`SearchService.searchWord`) that the platform runs on a background thread; driving it there mirrors
 * the real action and, unlike a direct EDT `findAll()`, actually visits the indexed files.
 */
fun CodeInsightTestFixture.psiUsagesAtCaret(project: Project): List<PsiUsage> =
    psiUsagesAtCaret(project) { it.singleOrNull() }

/**
 * Like [psiUsagesAtCaret] but *asserts* the caret resolves to exactly one search target (failing if
 * there are zero or several), for callers whose fixtures are constructed to have a single unambiguous
 * target.
 */
fun CodeInsightTestFixture.singleTargetPsiUsagesAtCaret(project: Project): List<PsiUsage> =
    psiUsagesAtCaret(project) { it.single() }

@Suppress("UnstableApiUsage")
private fun CodeInsightTestFixture.psiUsagesAtCaret(
    project: Project,
    selectTarget: (List<SearchTarget>) -> SearchTarget?
): List<PsiUsage> {
    val targetFile = symbolResolutionFile(project)
    val offset = caretOffset
    val allOptions = AllSearchOptions(
        UsageOptions.createOptions(GlobalSearchScope.allScope(project)),
        textSearch = false
    )
    return ApplicationManager.getApplication().executeOnPooledThread(Callable {
        ReadAction.nonBlocking(Callable {
            val target = selectTarget(searchTargets(targetFile, offset)) ?: return@Callable emptyList()
            buildQuery(project, target, allOptions).findAll().filterIsInstance<PsiUsage>()
        }).executeSynchronously()
    }).get()
}

/**
 * Find Usages from every search target at the caret, together: where the IDE offers a chooser of several targets, what
 * a user can reach is each target's usages in turn, so these are all of them, each position once.
 */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.everyTargetPsiUsagesAtCaret(project: Project): List<PsiUsage> {
    val targetFile = symbolResolutionFile(project)
    val offset = caretOffset
    val allOptions = AllSearchOptions(
        UsageOptions.createOptions(GlobalSearchScope.allScope(project)),
        textSearch = false
    )
    return ApplicationManager.getApplication().executeOnPooledThread(Callable {
        ReadAction.nonBlocking(Callable {
            searchTargets(targetFile, offset)
                .flatMap { target -> buildQuery(project, target, allOptions).findAll().filterIsInstance<PsiUsage>() }
                .distinctBy { Triple(it.file, it.range, it.declaration) }
        }).executeSynchronously()
    }).get()
}

/** Number of Find Usages results at the caret that are *not* the declaration itself. */
fun CodeInsightTestFixture.nonDeclarationUsageCountAtCaret(project: Project): Int =
    psiUsagesAtCaret(project).filterNot { it.declaration }.size

/** Number of Find Usages search targets resolved at the caret. */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.searchTargetCountAtCaret(): Int =
    searchTargets(symbolResolutionFile(project), caretOffset).size

/** The text each Find Usages search target at the caret presents, as the usages view and target chooser show it. */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.searchTargetPresentableTextsAtCaret(): List<String> =
    searchTargets(symbolResolutionFile(project), caretOffset).map { it.presentation().presentableText }

/**
 * Renames the symbol at the caret the way the user-facing Rename refactoring (Shift+F6) does.
 *
 * The rename target is resolved from the caret exactly as the platform's Symbol rename handler
 * resolves it: [targetSymbols] is the same resolution that feeds the `SYMBOLS` data key the
 * handler reads, and every renameable Elixir symbol implements
 * [com.intellij.refactoring.rename.api.RenameTarget] directly. The target is then handed to
 * [CodeInsightTestFixture.renameTarget] - the platform's test entry point for the
 * `RenameTarget`-based rename pipeline (the same drive JetBrains' own new-rename-API tests use),
 * which runs the production search (the registered `RenameUsageSearcher` -> queries -> file updates
 * in a write command) headlessly and unmocked. Callers assert on the resulting document text.
 */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.renameTargetAtCaret(newName: String) {
    val targets = renameTargetsAtCaret()
    val target = targets.singleOrNull()
        ?: throw AssertionError("Expected exactly one rename target at the caret, got ${targets.size}: $targets")
    renameTarget(target, newName)
}

/**
 * Renames [target] to [newName] through the same production steps as [CodeInsightTestFixture.renameTarget],
 * without that entry point's wait: `renameAndWait` launches the rename as a coroutine and then polls it from the
 * EDT, dispatching events and sleeping 10 ms per turn, so each rename costs several such turns (measured at
 * 55-60 ms against about 3 ms of work). Here the same [buildQuery] search runs to completion on a pooled thread
 * under a read action, the same [prepareRename] turns the usages into file updates, and the same
 * [FileUpdates.doUpdate] applies them in a write command, so the document ends up as the platform's drive
 * leaves it. What is left out is the progress title, the undoable action and the preview, none of which touch
 * the text. Worth it only for a test that renames thousands of times; elsewhere prefer [renameTargetAtCaret].
 */
fun CodeInsightTestFixture.renameTargetDirectly(target: RenameTarget, newName: String) {
    val application = ApplicationManager.getApplication()
    val options = RenameOptions(
        TextOptions(commentStringOccurrences = true, textOccurrences = true),
        runReadActionBlocking { target.maximalSearchScope } ?: GlobalSearchScope.projectScope(project)
    )
    // prepareRename insists on running outside a read action, and its inner read actions need a pooled thread
    val (fileUpdates, modelUpdate) = application.executeOnPooledThread(Callable {
        val usages = ReadAction.nonBlocking(Callable {
            buildQuery(project, target, options).findAll()
        }).executeSynchronously()
        runBlocking { prepareRename(usages, newName) }
    }).get()
    WriteCommandAction.writeCommandAction(project).run<Throwable> {
        modelUpdate?.updateModel(newName)
        fileUpdates?.doUpdate()
        // committing under the same write lock forestalls the background commit the change would otherwise queue
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
}

/**
 * The distinct [RenameTarget]s [targetSymbols] resolves at the caret, without asserting there is
 * exactly one - for callers (like negative-case tests) that want to inspect the count/contents
 * themselves rather than drive an actual rename.
 */
@Suppress("UnstableApiUsage")
fun CodeInsightTestFixture.renameTargetsAtCaret(): List<RenameTarget> =
    targetSymbols(symbolResolutionFile(project), caretOffset).filterIsInstance<RenameTarget>().distinct()

/**
 * The `PsiFile` production actually resolves a caret's `targetSymbols`/`searchTargets` lookup
 * against: `PsiUtilBase.getPsiFileInEditor(editor, project)`, the same call
 * `com.intellij.find.usages.ide.actions.targetsFromEditor` and
 * `com.intellij.lang.documentation.ide.actions.targetsFromEditor` make from the real `EDITOR` data
 * key. For a single-language file this is [file] itself (a no-op). For a HEEx multi-root view
 * provider it is not: [file] is the base-language (HEEx) root, whose leaves are coarse `Data` tokens
 * with no `XmlTag` ancestor, so a caret on a component tag would falsely report no target if [file]
 * were used directly here - `targetSymbols`/`searchTargets` never leave the `PsiFile` they are given.
 */
@Suppress("UnstableApiUsage")
private fun CodeInsightTestFixture.symbolResolutionFile(project: Project): PsiFile =
    PsiUtilBase.getPsiFileInEditor(editor, project) ?: file



/**
 * What the parameter-info popup holds once the IDE has decided to show one: one entry per signature
 * offered, and which parameter the caret is on.
 *
 * A `null` [ParameterInfoPopup] means the IDE never got as far as building a popup at all - the outcome
 * these gestures exist to tell apart from "the IDE offered me a choice".
 */
data class ParameterInfoPopup(val signatures: List<String>, val currentParameterIndex: Int)

/**
 * Runs [gesture] and returns the parameter-info popup the IDE builds in response, or `null` when it
 * builds none.
 *
 * WHAT THIS CAN AND CANNOT SEE. It cannot see the popup on screen: a headless fixture paints no Swing, so
 * `ParameterInfoControllerBase.existsWithVisibleHintForEditor` is `false` even for a gesture that
 * unquestionably shows a hint in a real IDE - measured, not assumed. What it observes instead is that the
 * platform built and populated a `ParameterInfoController` for the editor, the last step before
 * `HintManagerImpl.showEditorHint`. A sandbox trace of these same gestures confirmed that remaining step
 * follows within milliseconds whenever a controller is built, so this is a faithful proxy for the
 * *decision* to show a hint - but it is a proxy, and a regression that suppressed only the final paint
 * would not be caught here.
 *
 * Both halves are needed, because neither alone is honest. `ParameterInfoListener.hintUpdated` carries the
 * signatures, but `ParameterInfoController` fires it under `|| isHeadlessEnvironment()`, which
 * short-circuits the very visibility check the listener is otherwise gated on - so on its own it reports
 * models the user would never see. `existsForEditor` says a controller was built but not what is in it.
 *
 * WHY NOT [org.elixir_lang.code_insight.ParameterInfo] DIRECTLY: the handler-level tests in
 * `ParameterInfoTest` drive `findElementForParameterInfo`/`showParameterInfo` against a
 * `MockCreateParameterInfoContext`, whose `showHint` is a no-op. That pins which signatures the handler
 * *resolves*, a different question from whether the IDE shows anything: the handler resolves the same
 * signature whether the caret arrived by typing, by accepting a completion, or by being moved back, and
 * only one of those asks for a popup. The asking happens upstream of the handler - the platform's typed
 * handler does it for `(` and `,`, and a completion insert handler must do it for an accepted lookup - so
 * only the real chain tells them apart.
 *
 * The chain is asynchronous by design - an alarm, then a pooled-thread read action, then
 * `performLaterWhenAllCommitted` - so the delay is set to zero and the event queue pumped until the
 * controller reports. A gesture that asks for no popup never reports, so the wait running out is an
 * expected outcome rather than a failure, and costs [POPUP_TIMEOUT_SECONDS].
 */
fun CodeInsightTestFixture.parameterInfoPopupAfter(gesture: CodeInsightTestFixture.() -> Unit): ParameterInfoPopup? {
    val settings = CodeInsightSettings.getInstance()
    val autoPopupWas = settings.AUTO_POPUP_PARAMETER_INFO
    val delayWas = settings.PARAMETER_INFO_DELAY

    val model = arrayOfNulls<ParameterInfoPopup>(1)
    val reported = booleanArrayOf(false)

    val listener = object : ParameterInfoListener {
        override fun hintUpdated(result: ParameterInfoControllerBase.Model) {
            model[0] = ParameterInfoPopup(
                result.signatures.map { signature ->
                    (signature as? ParameterInfoControllerBase.SignatureItem)?.text ?: signature.toString()
                },
                result.current
            )
            reported[0] = true
        }

        override fun hintHidden(project: Project) {
            reported[0] = true
        }
    }

    ParameterInfoListener.EP_NAME.point.registerExtension(listener, testRootDisposable)

    try {
        settings.AUTO_POPUP_PARAMETER_INFO = true
        settings.PARAMETER_INFO_DELAY = 0

        gesture()

        /* Each stage is waited on with the platform's own helper where one exists, and the queue pumped
           between them: `AutoPopupParameterInfoTestUtil` alone returns before the hint is built, because
           the `performLaterWhenAllCommitted` between the alarm and the controller needs the queue pumped
           to run at all - measured, every case reported nothing without it. */
        AutoPopupParameterInfoTestUtil.waitForAutoPopup(project)

        try {
            PlatformTestUtil.waitWithEventsDispatching("", { reported[0] }, POPUP_TIMEOUT_SECONDS)
        } catch (_: AssertionError) {
            // a gesture that asks for no popup never reports, which is an outcome rather than a failure
        }

        AutoPopupParameterInfoTestUtil.waitForParameterInfoUpdate(editor)
    } finally {
        settings.AUTO_POPUP_PARAMETER_INFO = autoPopupWas
        settings.PARAMETER_INFO_DELAY = delayWas
    }

    return if (ParameterInfoControllerBase.existsForEditor(editor)) model[0] else null
}

private const val POPUP_TIMEOUT_SECONDS = 5

/** Types [charTyped] at the caret, as the user typing an opening parenthesis or a comma does. */
fun CodeInsightTestFixture.parameterInfoPopupAfterTyping(charTyped: Char): ParameterInfoPopup? =
    parameterInfoPopupAfter { type(charTyped) }

/**
 * Accepts the completion candidate named [lookupString] with [completionChar], the way a user finishes a
 * name from the lookup rather than typing it out.
 *
 * Shares [acceptCompletionCandidate] with [completeCandidateAtCaret], so the popup is finished the way
 * the editor finishes it: an open lookup consumes the keystroke, so `TypedHandler` - and with it the
 * platform's own `(`-and-`,` auto-popup - never runs. Typing the character into the fixture instead
 * reaches `TypedHandler` and quietly exercises the path that already works, which is the difference
 * between a test that catches this and one that does not.
 */
fun CodeInsightTestFixture.parameterInfoPopupAfterAcceptingCompletion(
    lookupString: String,
    completionChar: Char = '\t'
): ParameterInfoPopup? = parameterInfoPopupAfter {
    acceptCompletionCandidate(lookupString, completionChar)
}

/**
 * Moves the caret out of the argument list and back to where it started, the way a user who looked
 * elsewhere and returned does. Nothing is typed, so nothing asks the platform for a popup.
 */
fun CodeInsightTestFixture.parameterInfoPopupAfterLeavingAndReturning(): ParameterInfoPopup? {
    val argumentOffset = caretOffset

    return parameterInfoPopupAfter {
        editor.caretModel.moveToOffset(0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        editor.caretModel.moveToOffset(argumentOffset)
    }
}

/**
 * What the editor is telling the developer about the thing under the caret: the descriptions of the inspection
 * problems whose squiggle they would be pointing at.
 *
 * Bounded twice, and both bounds were measured rather than guessed. A problem must *cover the caret*, because a
 * complaint about the qualifier of `M.f(a)` is about `M` and answers a different question than one about the
 * call. And it must not reach beyond the caret's own line, because a complaint can be registered on an element
 * that contains everything: `defmodule Foo do ... end` is itself a call, and an inspection that finds its
 * reference unresolved marks the whole file, which would otherwise be reported at every caret in it.
 */
fun CodeInsightTestFixture.inspectionDescriptionsAtCaret(): List<String> {
    val document = editor.document
    val line = document.getLineNumber(caretOffset)
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)

    return doHighlighting(HighlightSeverity.WARNING)
        .filter { it.startOffset >= start && it.endOffset <= end && caretOffset in it.startOffset..it.endOffset }
        .mapNotNull { it.description }
        .distinct()
        .sorted()
}
