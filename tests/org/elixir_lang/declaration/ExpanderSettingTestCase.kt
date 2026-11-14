package org.elixir_lang.declaration

import org.elixir_lang.PlatformTestCase

/** Base class for tests that pin the walk's answers with Run the expander off, whatever the run's default, unless a test turns it on. */
abstract class ExpanderSettingTestCase : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        disableExpander(testRootDisposable)
    }

    /** Run the expander on, with the leg's SDK as the module's, so the expansion answers rather than the name index. */
    protected fun expanderOn() {
        enableExpander(module, testRootDisposable)
    }

    /** [test], run with the expander on ([expanderOn]). */
    protected fun withTheExpanderOn(test: () -> Unit) {
        expanderOn()
        test()
    }
}
