package org.elixir_lang.beam.psi

import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import com.intellij.codeInsight.multiverse.CodeInsightContextManagerImpl
import com.intellij.codeInsight.multiverse.isSharedSourceSupportEnabled
import com.intellij.lang.FileASTNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.FileViewProvider
import com.intellij.psi.impl.PsiFileEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.PsiFileWithStubSupport
import com.intellij.psi.impl.source.SourceTreeToPsiMap
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.stubs.*
import com.intellij.reference.SoftReference
import com.intellij.util.ArrayUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.ThreadingAssertions
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.beam.*
import org.elixir_lang.beam.chunk.Atoms
import org.elixir_lang.beam.chunk.CallDefinitions
import org.elixir_lang.beam.chunk.debug_info.TypeDefinitions
import org.elixir_lang.beam.decompiler.clauseSource
import org.elixir_lang.beam.psi.impl.*
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.beam.psi.stubs.TypeDefinitionStub
import org.elixir_lang.beam.type.VisibilityNameArity
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.stub.impl.ElixirFileStubImpl
import org.elixir_lang.type.Visibility
import org.jetbrains.annotations.NonNls
import java.lang.ref.SoftReference as JavaSoftReference

// See com.intellij.psi.impl.compiled.ClsFileImpl
class BeamFileImpl private constructor(
    private val fileViewProvider: FileViewProvider,
    private val isForDecompiling: Boolean
) : ModuleElementImpl(), PsiCompiledFile, PsiFileWithStubSupport, PsiFileEx {
    /**
     * NOTE: you absolutely MUST NOT hold PsiLock under the mirror lock
     */
    private val mirrorLock = Any()
    private val stubLock = Any()

    @Volatile
    private var mirrorFileElement: TreeElement? = null

    @Volatile
    private var stub: JavaSoftReference<StubTree>? = null

    constructor(fileViewProvider: FileViewProvider) : this(fileViewProvider, false)

    override fun getDecompiledPsiFile(): PsiFile = mirror as PsiFile

    /**
     * The mirror [getMirror] already built, without triggering decompilation.
     *
     * **Must stay overridden.** `DaemonCodeAnalyzerImpl.queuePassesCreation` swaps a `PsiCompiledFile` for
     * this before scheduling highlighting passes. The inherited default returns `null`, which costs more
     * than the highlighting: the daemon then falls back to `renewFile()`, which builds the mirror, returns
     * non-null, and so triggers a restart - which asks for the cached mirror again. Measured on an idle
     * editor with one decompiled file open, that loops every ~300 ms indefinitely.
     *
     * `@ApiStatus.Internal` with no public equivalent, and `getMirror()` is not a stand-in - the cached and
     * building calls are what separate the EDT path from the background one -
     * [IJPL-252078](https://youtrack.jetbrains.com/issue/IJPL-252078).
     */
    override fun getCachedMirror(): PsiFile? = mirrorFileElement?.psi as PsiFile?

    /**
     * Returns the virtual file corresponding to the PSI file.
     *
     * @return the virtual file, or null if the file exists only in memory.
     */
    override fun getVirtualFile(): VirtualFile = fileViewProvider.virtualFile

    override fun getName(): String = virtualFile.name

    /**
     * Renames the element.
     *
     * @param name the new element name.
     * @return the element corresponding to this element after the rename (either `this`
     * or a different element if the rename caused the element to be replaced).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    override fun setName(@NonNls name: String): PsiElement = throw IncorrectOperationException(CAN_NOT_MODIFY_MESSAGE)

    override fun processChildren(processor: PsiElementProcessor<in PsiFileSystemItem>): Boolean = true

    /**
     * Returns the directory containing the file.
     *
     * @return the containing directory, or null if the file exists only in memory.
     */
    override fun getContainingDirectory(): PsiDirectory = virtualFile.parent!!.let { manager.findDirectory(it)!! }

    override fun isDirectory(): Boolean = false

    override fun getProject(): Project = manager.project

    /**
     * Returns the PSI manager for the project to which the PSI element belongs.
     *
     * @return the PSI manager instance.
     */
    override fun getManager(): PsiManager = fileViewProvider.manager

    /**
     * Returns the array of children for the PSI element.
     * Important: In some implementations children are only composite elements, i.e. not a leaf elements
     *
     * @return the array of child elements.
     */
    override fun getChildren(): Array<PsiElement> = arrayOf(module())

    private fun module(): Module {
        val stub = getStub().childrenStubs.singleOrNull()
            ?: throw IllegalStateException("Expected single module stub for ${virtualFile.presentableUrl}")
        val moduleStub = stub as? ModuleStub<*>
            ?: throw IllegalStateException("Expected ModuleStub, got ${stub.javaClass.name}")
        val psi = moduleStub.psi
        return psi ?: throw IllegalStateException("Expected Module PSI, got null ${moduleStub.javaClass.name}")
    }

    private fun getStub(): PsiFileStub<*> = stubTree.root

    override fun getStubTree(): StubTree {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val unsynchronizedStubTree = SoftReference.dereference(stub)

        return if (unsynchronizedStubTree != null) {
            unsynchronizedStubTree
        } else {
            // build newStub out of lock to avoid deadlock
            val indexStubTree = StubTreeLoader
                .getInstance()
                .readOrBuild(project, virtualFile, this) as StubTree?

            val newStubTree = if (indexStubTree == null) {
                if (LOGGER.isDebugEnabled) {
                    LOGGER.debug("No stub for BEAM file in index: " + virtualFile.presentableUrl)
                }

                val rootStub = ElixirFileStubImpl()
                val moduleName =
                    BeamReader.readResult(virtualFile) { it.atoms?.moduleName() }
                        ?.valueOrNull
                        ?: virtualFile.nameWithoutExtension
                val name = defmoduleArgument(moduleName)
                LOGGER.warn("Building minimal stub tree for ${virtualFile.presentableUrl} (module $name)")
                ModuleStubImpl<ModuleImpl<*>>(rootStub, name)
                StubTree(rootStub)
            } else {
                indexStubTree
            }

            synchronized(stubLock) {
                // recheck with lock held, in case another thread built and set while we were building
                val synchronizedStubTree = SoftReference.dereference(stub)

                if (synchronizedStubTree != null) {
                    synchronizedStubTree
                } else {
                    newStubTree
                        .root
                        .let {
                            @Suppress("UNCHECKED_CAST")
                            it as PsiFileStubImpl<PsiFile>
                        }.psi = this

                    stub = JavaSoftReference(newStubTree)

                    newStubTree
                }
            }
        }
    }

    override fun getParent(): PsiDirectory = containingDirectory

    /**
     * Returns the first child of the PSI element.
     *
     * @return the first child, or null if the element has no children.
     */
    override fun getFirstChild(): PsiElement? = getStub().childrenStubs.firstOrNull()?.psi

    /**
     * Returns the last child of the PSI element.
     *
     * @return the last child, or null if the element has no children.
     */
    override fun getLastChild(): PsiElement? = getStub().childrenStubs.lastOrNull()?.psi

    /**
     * Returns the next sibling of the PSI element.
     *
     * @return the next sibling, or null if the node is the last in the list of siblings.
     */
    override fun getNextSibling(): PsiElement? {
        val siblings = parent.children
        val i = ArrayUtil.indexOf(siblings, this)

        return if (i < 0 || i >= siblings.size - 1) {
            null
        } else {
            siblings[i + 1]
        }
    }

    /**
     * Returns the previous sibling of the PSI element.
     *
     * @return the previous sibling, or null if the node is the first in the list of siblings.
     */
    override fun getPrevSibling(): PsiElement? {
        val siblings = parent.children
        val i = ArrayUtil.indexOf(siblings, this)

        return if (i < 1) {
            null
        } else {
            siblings[i - 1]
        }
    }

    /**
     * Returns the file containing the PSI element.
     *
     * @return the file instance, or null if the PSI element is not contained in a file (for example,
     * the element represents a package or directory).
     * @throws PsiInvalidElementAccessException if this element is invalid
     */
    override fun getContainingFile(): PsiFile {
        if (!isValid) {
            throw PsiInvalidElementAccessException(this)
        }
        return this
    }

    override fun appendMirrorText(buffer: StringBuilder, indentLevel: Int) {
        buffer.append(BANNER)
        getStub().childrenStubs.single().psi.let { it as ModuleElementImpl }.appendMirrorText(buffer, 0)
    }

    /**
     * Creates a copy of the file containing the PSI element and returns the corresponding
     * element in the created copy. Resolve operations performed on elements in the copy
     * of the file will resolve to elements in the copy, not in the original file.
     *
     * @return the element in the file copy corresponding to this element.
     */
    override fun copy(): PsiElement = this

    /**
     * Checks if this PSI element is valid. Valid elements and their hierarchy members
     * can be accessed for reading and writing. Valid elements can still correspond to
     * underlying documents whose text is different, when those documents have been changed
     * and not yet committed ([PsiDocumentManager.commitDocument]).
     * (In this case an attempt to change PSI will result in an exception).
     *
     *
     * Any access to invalid elements results in [PsiInvalidElementAccessException].
     *
     *
     * Once invalid, elements can't become valid again.
     *
     *
     * Elements become invalid in following cases:
     *
     *  * They have been deleted via PSI operation ([.delete])
     *  * They have been deleted as a result of an incremental reparse (document commit)
     *  * Their containing file has been changed externally, or renamed so that its PSI had to be rebuilt from scratch
     *
     *
     * @return true if the element is valid, false otherwise.
     * @see com.intellij.psi.PsiElement.isValid
     */
    override fun isValid(): Boolean = isForDecompiling || virtualFile.isValid

    /**
     * Checks if the contents of the element can be modified (if it belongs to a
     * non-read-only source file.)
     *
     * @return true if the element can be modified, false otherwise.
     */
    override fun isWritable(): Boolean = false

    /**
     * Passes the declarations contained in this PSI element and its children
     * for processing to the specified scope processor.
     *
     * @param processor  the processor receiving the declarations.
     * @param lastParent the child of this element has been processed during the previous
     * step of the tree up walk (declarations under this element do not need
     * to be processed again)
     * @param place      the original element from which the tree up walk was initiated.   @return true if the declaration processing should continue or false if it should be stopped.
     */
    override fun processDeclarations(
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement?,
        place: PsiElement
    ): Boolean = processor.execute(module(), state)

    /**
     * Returns the element which should be used as the parent of this element in a tree up
     * walk during a resolve operation. For most elements, this returns `getParent()`,
     * but the context can be overridden for some elements like code fragments (see
     * [com.intellij.psi.JavaCodeFragmentFactory.createCodeBlockCodeFragment] [com.intellij.psi.PsiElement.getContext]).
     * @return the resolve context element.
     */
    override fun getContext(): PsiElement? = FileContextUtil.getFileContext(this)

    /**
     * Checks if an actual source or class file corresponds to the element. Non-physical elements include,
     * for example, PSI elements created for the watch expressions in the debugger.
     * Non-physical elements do not generate tree change event.
     * Also, [PsiDocumentManager.getDocument] returns null for non-physical elements.
     * Not to be confused with [FileViewProvider.isPhysical].
     *
     * @return true if the element is physical, false otherwise.
     */
    override fun isPhysical(): Boolean = true

    override fun getNode(): FileASTNode? = null

    /**
     * Gets the modification stamp value. Modification stamp is a value changed by any modification
     * of the content of the file. Note that it is not related to the file modification time.
     *
     * @return the modification stamp value
     * @see VirtualFile.getModificationStamp
     */
    override fun getModificationStamp(): Long = virtualFile.modificationStamp

    /**
     * Returns the same file.
     *
     * @return the same file
     */
    override fun getOriginalFile(): PsiFile = this

    /**
     * The PSI file type. This is deliberately the **non-binary** [org.elixir_lang.ElixirFileType], NOT the
     * binary [org.elixir_lang.beam.FileType] used by the VFS to drive decompilation.
     *
     * The distinction matters for the Find Usages tool window. `UsageInfo2UsageAdapter` computes a usage's
     * displayed line and navigation offset from `PsiDocumentManager.getDocument(usage.file)`, but it
     * short-circuits to line `-1` / offset `0` whenever `usage.file.getFileType().isBinary()` is true. Because
     * `BeamFileImpl` is a physical file whose decompiled document is fully reachable (via
     * `FileDocumentManager.getCachedDocument(virtualFile)`), reporting a non-binary PSI file type lets the
     * usage view map every within-`.beam` usage to its true decompiled line instead of collapsing them all onto
     * line 1. The VFS-level type (and thus the `filetype.decompiler`/read-only binary handling) is unaffected:
     * decompiler wiring keys off [VirtualFile.getFileType], and the beam editor keys off it in
     * [org.elixir_lang.beam.file_editor.Provider].
     *
     * @return [org.elixir_lang.ElixirFileType.INSTANCE]
     */
    override fun getFileType(): FileType = org.elixir_lang.ElixirFileType.INSTANCE

    /**
     * This file.
     *
     * @return a single-element array containing `this`
     */
    @Deprecated("Use FileViewProvider#getAllFiles instead.")
    override fun getPsiRoots(): Array<PsiFile> = arrayOf(this)

    override fun getViewProvider(): FileViewProvider = fileViewProvider

    /**
     * Called by the PSI framework when the contents of the file changes. Can be used to invalidate
     * file-level caches. If you override this method, you **must** call the base class implementation.
     */
    override fun subtreeChanged() {
        /* .beam files are assumed not to change internally without the file itself changing, which the file event
           system will pickup */
    }

    /**
     * Checks if it is possible to rename the element to the specified name,
     * and throws an exception if the rename is not possible. Does not actually modify anything.
     *
     * @param name the new name to check the renaming possibility for.
     * @throws IncorrectOperationException if the rename is not supported or not possible for some reason.
     */
    override fun checkSetName(name: String): Unit = throw IncorrectOperationException(CAN_NOT_MODIFY_MESSAGE)

    /**
     * Returns the corresponding PSI element in a decompiled file created by IDEA from
     * the library element.
     *
     * @return the counterpart of the element in decompiled file.
     */
    override fun getMirror(): PsiElement {
        var mirrorTreeElement = mirrorFileElement
        if (mirrorTreeElement == null) {
            synchronized(mirrorLock) {
                mirrorTreeElement = mirrorFileElement
                if (mirrorTreeElement == null) {
                    val file = virtualFile
                    val fileName = file.name + ".decompiled.ex"
                    val document = FileDocumentManager.getInstance().getDocument(file)
                    assert(document != null) { file.url }
                    val mirrorText = document!!.immutableCharSequence
                    val factory = PsiFileFactory.getInstance(manager.project)
                    val mirror = factory.createFileFromText(
                        fileName,
                        ElixirLanguage,
                        mirrorText,
                        false,
                        false,
                        // noSizeLimit, as ClsFileImpl.getMirror does. PsiFileFactoryImpl discards the language
                        // passed here (`language = viewProvider.getBaseLanguage()`), and calcBaseLanguage
                        // answers PlainTextLanguage for text over `idea.max.intellisense.filesize`, which
                        // makes setMirror below throw InvalidMirrorException. No fixture decompiles large
                        // enough to cover it.
                        true
                    )
                    propagateCodeInsightContextTo(mirror)
                    mirrorTreeElement = SourceTreeToPsiMap.psiToTreeNotNull(mirror)
                    try {
                        val finalMirrorTreeElement = mirrorTreeElement
                        LOGGER.debug("Setting mirror for $fileName")
                        ProgressManager.getInstance().executeNonCancelableSection {
                            setMirror(finalMirrorTreeElement)
                            putUserData(MODULE_DOCUMENT_LINK_KEY, document)
                        }
                        LOGGER.debug("Mirror for $fileName")
                    } catch (e: InvalidMirrorException) {
                        LOGGER.error(file.url, e)
                    }
                    (mirror as PsiFileImpl).originalFile = this
                    mirrorFileElement = mirrorTreeElement
                }
            }
        }
        return mirrorTreeElement!!.psi
    }

    /**
     * Copies this file's [com.intellij.codeInsight.multiverse.CodeInsightContext] onto [mirror]'s view
     * provider, as [com.intellij.psi.impl.compiled.ClsFileImpl.getMirror] does.
     *
     * The platform never does this itself - a [PsiFileFactory]-built mirror always reports `DefaultContext` -
     * so without it the daemon logs `PsiFile's context does not match the context of the editor` on every
     * decompiled `.beam`, which reaches users as an error report. Shared-source support is on by default, so
     * this is the normal path.
     *
     * Only the setter needs the `@ApiStatus.Internal` `CodeInsightContextManagerImpl`; the service, reading a
     * context and [isSharedSourceSupportEnabled] are all public on `CodeInsightContextManager`, so they go
     * through that. The cast is what `CodeInsightContextManagerImpl.getInstanceImpl` does internally, against
     * the single registered implementation, and using it directly keeps the plugin verifier's internal-API
     * findings to the two that are unavoidable - naming the type, and the call - instead of five. The whole
     * core-api `codeInsight.multiverse` package is `@ApiStatus.Experimental` regardless -
     * [IJPL-252078](https://youtrack.jetbrains.com/issue/IJPL-252078).
     */
    @Suppress("UnstableApiUsage")
    private fun propagateCodeInsightContextTo(mirror: PsiFile) {
        val project = manager.project

        if (isSharedSourceSupportEnabled(project)) {
            val contextManager = CodeInsightContextManager.getInstance(project)
            // Read before the cast: a smart-cast `contextManager` would bind this to the impl instead.
            val context = contextManager.getCodeInsightContext(viewProvider)

            (contextManager as CodeInsightContextManagerImpl).setCodeInsightContext(mirror.viewProvider, context)
        }
    }

    override fun setMirror(element: TreeElement) {
        val mirrorElement = SourceTreeToPsiMap.treeToPsiNotNull<PsiElement>(element)
        if (mirrorElement !is ElixirFile) {
            throw InvalidMirrorException("Unexpected mirror file: $mirrorElement")
        }
        setMirrors(arrayOf(module()), mirrorElement.modulars())
    }

    override fun isContentsLoaded(): Boolean = cachedMirror != null

    override fun onContentReload() {
        ThreadingAssertions.assertWriteAccess()

        synchronized(stubLock) {
            val stubTree = SoftReference.dereference(stub)
            stub = null
            (stubTree?.root as PsiFileStubImpl<*>?)?.clearPsi("beam onContentReload")
        }

        synchronized(mirrorLock) {
            putUserData(MODULE_DOCUMENT_LINK_KEY, null)
            mirrorFileElement = null
        }
    }

    // Validity stays tied to the VirtualFile: re-evaluating it after invalidation, as PsiBinaryFileImpl does, needs
    // the @ApiStatus.Internal FileManagerEx.evaluateValidity.
    override fun markInvalidated() {}

    companion object {
        private val LOGGER = Logger.getInstance(BeamFileImpl::class.java)
        private const val CAN_NOT_MODIFY_MESSAGE = "Cannot modify decompiled Beam files"
        private const val BANNER = "# Source code recreated from a .beam file by IntelliJ Elixir\n" +
                "# Function clause bodies is not available\n"
        private val MODULE_DOCUMENT_LINK_KEY = Key.create<Document>("module.document.link")

        @JvmStatic
        fun buildFileStub(bytes: ByteArray?, path: String): Stub? =
            bytes
                ?.let { BeamReader.read(it, path) { reader -> buildModuleStub(reader) } }
                ?.parentStub

        private fun buildModuleStub(reader: BeamReader): ModuleStub<*>? = reader
            .atoms
            ?.let { atoms ->
                atoms
                    .moduleName()
                    ?.let { moduleName ->
                        val name = defmoduleArgument(moduleName)
                        val parentStub = ElixirFileStubImpl()
                        val moduleStub: ModuleStub<*> = ModuleStubImpl<ModuleImpl<*>>(parentStub, name)
                        buildCallDefinitions(moduleStub, reader)
                        buildTypeDefinitions(moduleStub, reader, atoms)

                        moduleStub
                    }
            }

        private fun buildTypeDefinitions(parentStub: ModuleStub<*>, reader: BeamReader, atoms: Atoms) {
            TypeDefinitions.visibilityNameAritySortedSetByVisibility(parentStub, reader, atoms)
                .forEach { (_, visibilityNameAritySortedSet) ->
                    visibilityNameAritySortedSet.forEach { visibilityNameArity ->
                        buildTypeDefinition(parentStub, visibilityNameArity)
                    }
                }
        }

        private fun buildTypeDefinition(parentStub: ModuleStub<*>, visibilityNameArity: VisibilityNameArity):
                TypeDefinitionStub<*> =
            buildTypeDefinition(
                parentStub,
                visibilityNameArity.visibility,
                visibilityNameArity.name,
                visibilityNameArity.arity
            )

        private fun buildTypeDefinition(parentStub: ModuleStub<*>, visibility: Visibility, name: String, arity: Int):
                TypeDefinitionStub<*> =
            TypeDefinitionStubImpl<TypeDefinitionImpl<*>>(parentStub, visibility, name, arity)

        private fun buildCallDefinitions(parentStub: ModuleStub<*>, reader: BeamReader) {
            val macroNameAritySortedSetByMacro = CallDefinitions.macroNameAritySortedSetByMacro(reader)
            val options = decompilerOptions(macroNameAritySortedSetByMacro)

            var unreadableReported = false

            val docs = reader.documentation?.docs

            macroNameAritySortedSetByMacro.forEach { (_, macroNameAritySortedSet) ->
                macroNameAritySortedSet.forEach { macroNameArity ->
                    val signature = if (options.decompileMacro(macroNameArity.macro)) {
                        val result = signature(reader, macroNameArity)

                        if (result is ReadResult.Unreadable && !unreadableReported) {
                            unreadableReported = true
                            result.orReported(reader.path)
                        }

                        result.valueOrNull
                    } else {
                        null
                    }
                    val (parameters, isAutoGeneratedName) =
                        signature ?: (emptyList<String>() to (macroNameArity.arity > 0))

                    CallDefinitionStubImpl<CallDefinitionImpl<*>>(
                        parentStub,
                        macroNameArity.macro,
                        macroNameArity.name,
                        macroNameArity.arity,
                        parameters,
                        isAutoGeneratedName,
                        docs?.guard(macroNameArity) == true
                    )
                }
            }
        }

        // Rendering one definition must not cost the module its stub.
        private fun signature(
            reader: BeamReader,
            macroNameArity: MacroNameArity
        ): ReadResult<Pair<List<String>, Boolean>> =
            contain("parameters of ${macroNameArity.macro} ${macroNameArity.name}/${macroNameArity.arity}") {
                clauseSource(macroNameArity, reader.debugInfo) { reader.documentation }
                    ?.let { it.parameters to it.isAutoGeneratedName }
            }
    }
}
