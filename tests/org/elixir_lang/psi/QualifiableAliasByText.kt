package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/** The one alias in the file written as [text]: for a dotted [text], the outermost node of its chain. */
internal fun CodeInsightTestFixture.qualifiableAlias(text: String): QualifiableAlias =
    PsiTreeUtil.findChildrenOfType(file, QualifiableAlias::class.java).single {
        it.text == text && (it is QualifiedAlias || text.none { c -> c == '.' })
    }
