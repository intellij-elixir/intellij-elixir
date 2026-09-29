package org.elixir_lang.beam.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.util.IncorrectOperationException
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.stubs.CallDefinitionStub
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.Definition
import org.elixir_lang.structure_view.element.Timed
import org.jetbrains.annotations.Contract
import org.jetbrains.annotations.NonNls

class CallDefinitionImpl<T : CallDefinitionStub<*>>(private val stub: T) : ModuleElementImpl(), CallDefinition,
                                                                           StubBasedPsiElement<T> {
    override fun getProject(): Project = parent.project

    /**
     * Returns the array of children for the PSI element.
     * Important: In some implementations children are only composite elements, i.e. not a leaf elements
     *
     * @return the array of child elements.
     */
    override fun getChildren(): Array<PsiElement> = emptyArray()

    /**
     * Returns the parent of the PSI element.
     *
     * @return the parent of the element, or null if the element has no parent.
     */
    override fun getParent(): ModuleImpl<*> = stub.parentStub.psi as ModuleImpl<*>

    @Deprecated("Deprecated in platform - use getIElementType()", replaceWith = ReplaceWith("getIElementType()"))
    override fun getElementType(): IStubElementType<*, *> = stub.elementType as IStubElementType<*, *>

    override fun getStub(): T = stub

    override fun appendMirrorText(buffer: StringBuilder, indentLevel: Int) {}

    override fun setMirror(element: TreeElement) = setMirrorCheckingType(element, null)

    /**
     * @return `null` if it does not have a canonical name OR if it has more than one canonical name
     */
    override fun canonicalName(): String? = null

    /**
     * @return empty set if no canonical names
     */
    override fun canonicalNameSet(): Set<String> = emptySet()

    override fun getNameIdentifier(): PsiElement = this

    /**
     * Renames the element.
     *
     * @param name the new element name.
     * @return the element corresponding to this element after the rename (either `this`
     * or a different element if the rename caused the element to be replaced).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    override fun setName(@NonNls name: String): PsiElement =
        throw IncorrectOperationException("Cannot modify module name in Beam files")

    // `ModuleImpl.setMirror` leaves the mirror unset for any definition the decompiled source lacks, and
    // `getMirror()` is unannotated Java that Kotlin reads as non-null. `this` is `PsiElementBase`'s default.
    override fun getNavigationElement(): PsiElement = mirror ?: this

    override fun getNode(): ASTNode? = null

    /**
     * @return `true`
     */
    @Contract(pure = true)
    override fun isExported(): Boolean = stub.isExported

    /**
     * The arity of the function or macro that was exported into the compiled .beam file
     */
    override fun exportedArity(state: ResolveState): Int = stub.callDefinitionClauseHeadArity()

    /**
     * The name that was exported into the compiled .beam file
     */
    override fun exportedName(): String = stub.name

    override val time: Timed.Time by lazy {
        when (stub.definition) {
            Definition.PUBLIC_MACRO, Definition.PRIVATE_MACRO -> Timed.Time.COMPILE
            else -> Timed.Time.RUN
        }
    }
    override val nameArityInterval: NameArityInterval by lazy {
        val arity = stub.callDefinitionClauseHeadArity()

        NameArityInterval(stub.name, ArityInterval(arity, arity))
    }

    override val parameters: List<String>
        get() = stub.parameters()

    override val isGuard: Boolean
        get() = stub.isGuard
}
