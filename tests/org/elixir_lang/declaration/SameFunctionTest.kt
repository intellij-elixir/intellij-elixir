package org.elixir_lang.declaration

import org.elixir_lang.declaration.ArityKnowledge.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SameFunctionTest {
    @Test
    fun theSameNameAtAnOverlappingArityIsTheSameFunction() {
        assertEquals(SameFunction.YES, declaration("f", Exact(1)).sameFunction(declaration("f", Exact(1))))
        assertEquals(SameFunction.YES, declaration("f", Range(1, 3)).sameFunction(declaration("f", Exact(3))))
        assertEquals(SameFunction.YES, declaration("f", Open(2)).sameFunction(declaration("f", Range(0, 2))))
    }

    @Test
    fun theSameNameAtDisjointAritiesIsAnother() {
        assertEquals(SameFunction.NO, declaration("f", Exact(1)).sameFunction(declaration("f", Exact(2))))
        assertEquals(SameFunction.NO, declaration("f", Range(0, 1)).sameFunction(declaration("f", Open(2))))
    }

    @Test
    fun anotherNameIsAnotherWhateverTheArities() {
        assertEquals(SameFunction.NO, declaration("f", Exact(1)).sameFunction(declaration("g", Exact(1))))
        assertEquals(SameFunction.NO, declaration("f", Unknown).sameFunction(declaration("g", Exact(1))))
        assertEquals(SameFunction.NO, declaration("f", Exact(1)).sameFunction(declaration("g", Unknown)))
    }

    @Test
    fun theSameNameWithAnUnknownArityIsUnknown() {
        assertEquals(SameFunction.UNKNOWN, declaration("f", Unknown).sameFunction(declaration("f", Exact(1))))
        assertEquals(SameFunction.UNKNOWN, declaration("f", Exact(1)).sameFunction(declaration("f", Unknown)))
        assertEquals(SameFunction.UNKNOWN, declaration("f", Unknown).sameFunction(declaration("f", Unknown)))
    }
}
