package org.elixir_lang.facet

import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.ProcessOutput

/** A light test on a small IDE, whose Elixir SDKs come from [models] and are removed after it. */
abstract class SmallIdeFacetTestCase : PlatformTestCase() {
    internal val models = FacetModelFixtures()

    override fun setUp() {
        super.setUp()
        ProcessOutput.isSmallIdeOverride = true
        SdksService.getInstance()!!.resetForTests()
    }

    override fun tearDown() {
        runAll(
            { models.leaveSmallIde(project) },
            { super.tearDown() },
        )
    }

    /** Lets the deferred roots change run, and the indexing it starts finish. */
    protected fun settle() = FacetModelFixtures.settle(project)
}
