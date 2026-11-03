package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import org.elixir_lang.ui.framework.core.clickButtonText
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * On PyCharm's Internal Erlang SDKs and SDKs pages, in modal Settings, a Model's home changed with the home field's
 * browse button and applied: the SDK table then holds the new home, its library paths and its version.
 */
class SdkHomeBrowseTest {
    @Test
    fun `browsing to a new home keeps it on both SDK pages`() {
        val rows = Rows()
        launchPyCharm("SdkHomeBrowseTest") { homes ->
            closeInternalErrors("setup")
            // Erlang first, so the Elixir Model pairs with the only Erlang Model and nothing asks which.
            val erlang = addOnPage(SdkPage.ERLANG, homes.erlang.a)
            val elixir = addOnPage(SdkPage.ELIXIR, homes.elixir.a)
            browseRow(rows, SdkPage.ERLANG, erlang, homes.erlang)
            browseRow(rows, SdkPage.ELIXIR, elixir, homes.elixir)
        }
        rows.assertAllHeld()
    }

    private fun Driver.browseRow(rows: Rows, sdkPage: SdkPage, model: String, home: Home) {
        val page = sdkPage.label
        val homeA = home.a
        val homeB = home.b
        closeInternalErrors("$page-start")
        val before = stored(model)
        val versionA = before.version
        rows.check(page, "before Browse a CLASSES root lies under A '$homeA'", before.classesRoots.any { under(it, homeA) })
        runCatching {
            inSettings(sdkPage) {
                val browsed = driver.errorsDuring("$page-browsed") { browseHome(model, sdkPage.chooserTitle, homeB) }
                rows.check(page, "the IDE reported no error on Browse: $browsed", browsed.isEmpty())
                val applied = driver.errorsDuring("$page-applied") {
                    clickButtonText("Apply")
                    Thread.sleep(1_000)
                }
                rows.check(page, "the IDE reported no error on Apply: $applied", applied.isEmpty())
                clickButtonText("OK")
            }
            val stored = stored(model)
            rows.check(page, "home '${stored.home}' is B '$homeB'", stored.home?.let(Path::of) == Path.of(homeB))
            rows.check(page, "a CLASSES root lies under B: ${stored.classesRoots}", stored.classesRoots.any { under(it, homeB) })
            rows.check(page, "no CLASSES root lies under A '$homeA'", stored.classesRoots.none { under(it, homeA) })
            rows.check(
                page,
                "version '${stored.version}' is not A's '$versionA' and names B's '${home.bVersion}'",
                stored.version != versionA && stored.version.orEmpty().contains(home.bVersion),
            )
        }.onFailure { failure ->
            rows.fail(page, failure)
            closeInternalErrors("$page-failed")
            cancelSettings()
        }
    }
}
