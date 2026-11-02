package org.elixir_lang.ui.code_intelligence

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ide.IDETestContext
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.addSdkFromDisk
import org.elixir_lang.ui.framework.core.assignElixirSdk
import org.elixir_lang.ui.framework.core.ensureIdeInForeground
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.openProjectAt
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Where declarations come from: the walk, with no property, or the expander. */
enum class DeclarationSource { WALK, EXPANDER }

/** The compiled subject a run opens, from `-PtestUiSubject`. Subjects stay outside the repository. */
object Subject {
    val root: Path by lazy {
        val path = System.getProperty("testUi.subject")
            ?: error("no subject: generate one with testUI/subjects/generate-own.sh <root> and pass -PtestUiSubject=<root>/own")
        Path(path).also { check(it.isDirectory()) { "no subject at $it: generate it with testUI/subjects/generate-own.sh" } }
    }
    val name: String get() = root.fileName.toString()
}

fun IDETestContext.withDeclarationSource(source: DeclarationSource): IDETestContext = when (source) {
    DeclarationSource.WALK -> this
    DeclarationSource.EXPANDER -> applyVMOptionsPatch { addSystemProperty("org.elixir_lang.declaration.source", "expander") }
}

/** With no project open, as `addSdkFromDisk` needs the welcome screen. Its compiled output stays: the expander reads it. */
fun Driver.openSubject(indexing: Duration = 15.minutes) = step("Open ${Subject.name}") {
    ensureWelcomeScreenInForeground()
    welcomeScreen {
        addSdkFromDisk(
            "Add Erlang SDK for Elixir SDK from disk",
            IdeTestContext.erlangSdkPath,
            "Select Home Directory for Erlang SDK for Elixir SDK",
        )
        addSdkFromDisk("Add Elixir SDK from disk", IdeTestContext.elixirSdkPath, "Select Home Directory for Elixir SDK")
    }
    openProjectAt(Subject.root.toString(), keepBuild = true, ready = indexing)
    ensureIdeInForeground()
    assignElixirSdk()
    waitForIndicators(indexing)
}
