package org.elixir_lang.facet

import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread

/** What the migration of an Elixir facet's stand-in leaves in the module's `.iml`. */
class StandInMigrationHeavyTest : SmallIdeFacetHeavyTestCase() {
    fun testTheSavedModuleFileHasTheNameAndNoStandIn() {
        val sdk = models.standIn(module, "Stand In Heavy Test", testRootDisposable)

        runSuspendOnPooledThread { StandInMigration.migrate(project) }

        val saved = savedModuleFile()
        assertTrue("the facet's configuration saved no sdkName:\n$saved", saved.contains("sdkName=\"${sdk.name}\""))
        assertFalse("the stand-in was saved:\n$saved", saved.contains("module-library"))
    }
}
