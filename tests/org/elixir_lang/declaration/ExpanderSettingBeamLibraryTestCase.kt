package org.elixir_lang.declaration

import org.elixir_lang.beam.BeamLibraryTestCase

/** A [BeamLibraryTestCase] that pins the walk's answers with Run the expander off, whatever the run's default, as [ExpanderSettingTestCase] does. */
abstract class ExpanderSettingBeamLibraryTestCase : BeamLibraryTestCase() {
    override fun setUp() {
        super.setUp()
        disableExpander(testRootDisposable)
    }
}
