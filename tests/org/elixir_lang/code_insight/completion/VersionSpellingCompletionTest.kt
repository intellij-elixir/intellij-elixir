package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.lookup.Lookup
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.declaration.eachExpander
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.model.psi.config.disableMixDepsCheck

/**
 * Completion writes a name as the project's Elixir parses it: bare where that release reads it bare, else quoted. On the
 * leg's SDK, with the expander off and then on, since the spelling is the SDK's, not the source's.
 *
 * `hello_привет` mixes Latin and Cyrillic: Elixir reads it bare before 1.14, which checks no identifier's scripts, and
 * from 1.18, and rejects it on 1.14 to 1.17, where it is quoted. `µ`, which 1.14 normalizes to `μ`, is read bare only
 * before 1.14. `:**` is read from 1.13, `:..//` from 1.12.
 *
 * `ƀar`, a letter UTS #39 restricts, is read bare only before 1.14 too, but nothing is offered in it on any release: the
 * caret's context is the newest Elixir's, which reads none there. Its rows assert that only from 1.14, where it is
 * Elixir's answer.
 */
class VersionSpellingCompletionTest : ExpanderSettingTestCase() {
    fun testAMixedScriptNameIsOfferedUnqualifiedOnlyWhereItIsReadBare() = eachExpander(module, testRootDisposable) {
        configure("def g, do: hel<caret>")

        val offered = myFixture.shownLookupStrings()

        if (mixedReadBare()) {
            assertContainsElements(offered, MIXED)
        } else {
            assertDoesntContain(offered, MIXED)
        }
    }

    fun testARestrictedLetterNameIsNotOfferedUnqualifiedWhereScriptsAreChecked() =
        eachExpander(module, testRootDisposable) {
            configure("def g, do: ƀ<caret>")

            if (!beforeScriptsAreChecked()) assertDoesntContain(myFixture.shownLookupStrings(), RESTRICTED)
        }

    fun testARestrictedLetterNameIsNotOfferedAfterAModuleWhereScriptsAreChecked() =
        eachExpander(module, testRootDisposable) {
            configure("def g, do: Q.ƀ<caret>")

            if (!beforeScriptsAreChecked()) assertDoesntContain(myFixture.shownLookupStrings(), RESTRICTED)
        }

    fun testTheMicroSignNameIsOfferedUnqualifiedOnlyWhereItIsReadBare() = eachExpander(module, testRootDisposable) {
        configure("def g, do: µ<caret>")

        val offered = myFixture.shownLookupStrings()

        if (beforeScriptsAreChecked()) {
            assertContainsElements(offered, MICRO)
        } else {
            assertDoesntContain(offered, MICRO)
        }
    }

    fun testTheMicroSignNameAfterAModuleIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: Q.µ<caret>")

        choose(micro())

        checkResult("def g, do: Q.${micro()}(x)")
    }

    fun testAMixedScriptNameAfterAModuleIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: Q.hel<caret>")

        choose(MIXED)

        checkResult("def g, do: Q.${mixed()}(x)")
    }

    fun testAMixedScriptNameInACaptureIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: &Q.hel<caret>/1")

        choose(MIXED)

        checkResult("def g, do: &Q.${mixed()}<caret>/1")
    }

    fun testAMixedScriptFunctionAtomIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: apply(Q, :hel<caret>, [1])")

        choose(MIXED)

        checkResult("def g, do: apply(Q, :${mixed()}<caret>, [1])")
    }

    fun testAMixedScriptModuleAtomIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: :hello_п<caret>")

        choose(MIXED)

        checkResult("def g, do: :${mixed()}<caret>")
    }

    fun testTheStepModuleAtomIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: :<caret>")

        choose(STEP)

        checkResult("def g, do: :${spelled(STEP, ElixirLanguageFeature.STEP_ATOM)}<caret>")
    }

    fun testThePowerOperatorAfterAModuleIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configure("def g, do: Q.<caret>")

        choose(POWER)

        checkResult("def g, do: Q.${spelled(POWER, ElixirLanguageFeature.POWER_OPERATOR)}(left, right)")
    }

    fun testAMixedScriptConfigKeyInAConfigCallIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configureConfig("config :my_app, hel<caret>")

        choose(MIXED)

        myFixture.checkResult(config("config :my_app, ${mixed()}<caret>: "))
    }

    fun testAQuotedConfigKeyInAConfigCallIsWrittenWithTheCaretBeforeItsColon() = eachExpander(module, testRootDisposable) {
        configureConfig("config :my_app, rel<caret>")

        choose(RELEASE)

        myFixture.checkResult(config("config :my_app, \"$RELEASE\"<caret>: "))
    }

    fun testAMixedScriptConfigKeyBeforeItsColonIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configureConfig("config :my_app, hel<caret>: 1")

        choose(MIXED)

        myFixture.checkResult(config("config :my_app, ${mixed()}<caret>: 1"))
    }

    fun testAMixedScriptConfigKeyReadIsWrittenAsTheSdkReadsIt() = eachExpander(module, testRootDisposable) {
        configureConfig(null)
        configure("def g, do: Application.get_env(:my_app, :hel<caret>)")

        choose(MIXED)

        checkResult("def g, do: Application.get_env(:my_app, :${mixed()}<caret>)")
    }

    private fun mixed(): String = if (mixedReadBare()) MIXED else "\"$MIXED\""

    /** Whether the SDK's Elixir reads [MIXED] bare: before 1.14, which first checks an identifier's scripts, or by chunk. */
    private fun mixedReadBare(): Boolean =
        beforeScriptsAreChecked() || available(ElixirLanguageFeature.MIXED_SCRIPT_BY_UNDERSCORE_CHUNK)

    /** The name `def µ` defines: [MICRO] before 1.14, which normalizes it to [MU]. */
    private fun micro(): String = if (available(ElixirLanguageFeature.NORMALIZED_IDENTIFIERS)) MU else MICRO

    /** Whether the SDK's Elixir is older than 1.14, the first release that checks an identifier's scripts. */
    private fun beforeScriptsAreChecked(): Boolean =
        !ElixirLanguageLevelResolver.languageLevelFor(myFixture.file).elixir.isGreaterOrEqualThan(1, 14, 0)

    /** [name] bare where [feature] reads it so, else quoted. */
    private fun spelled(name: String, feature: ElixirLanguageFeature): String =
        if (available(feature)) name else "\"$name\""

    private fun available(feature: ElixirLanguageFeature): Boolean =
        ElixirLanguageLevelResolver.isAvailable(feature, myFixture.file)

    /** Chooses the item [lookupString] with Tab. */
    private fun choose(lookupString: String) {
        myFixture.chooseItem(null, lookupString, Lookup.REPLACE_SELECT_CHAR)
    }

    private fun configure(body: String) {
        myFixture.configureByText("spelling.ex", source(body))
    }

    private fun checkResult(body: String) {
        myFixture.checkResult(source(body))
    }

    /** The config files [configureConfig] has opened, each named apart. */
    private var typed = 0

    /**
     * A Mix project `:my_app` whose config sets the keys `hello_привет` and `release-12.x`, and, unless [typing] is `null`,
     * a config file holding it.
     */
    private fun configureConfig(typing: String?) {
        if (myFixture.findFileInTempDir("mix.exs") == null) {
            disableMixDepsCheck(testRootDisposable)
            myFixture.addFileToProject("mix.exs", "defmodule MyApp.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :my_app]\nend\n")
            myFixture.addFileToProject("config/config.exs", config("config :my_app, $MIXED: 1, \"$RELEASE\": 2"))
        }

        typing?.let {
            val text = config(it)
            val file = myFixture.addFileToProject("config/typing${typed++}.exs", text.replace(CARET, ""))
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            myFixture.editor.caretModel.moveToOffset(text.indexOf(CARET))
        }
    }

    private companion object {
        const val MIXED = "hello_привет"
        const val RESTRICTED = "ƀar"
        const val MICRO = "µ"
        const val MU = "μ"
        const val STEP = "..//"
        const val POWER = "**"
        const val RELEASE = "release-12.x"
        const val CARET = "<caret>"

        fun config(call: String): String = "import Config\n\n$call\n"

        /** [body] in a module beside `Q`, which defines the non-ASCII functions and `**`, and modules named by atoms. */
        fun source(body: String): String =
            """
            defmodule Q do
              def hello_привет(x), do: x
              def ƀar(x), do: x
              def µ(x), do: x
              def left ** right, do: left * right
            end

            defmodule :hello_привет do
            end

            defmodule :"..//" do
            end

            defmodule Caller do
              def hello_привет(x), do: x
              def ƀar(x), do: x
              def µ(x), do: x
              $body
            end
            """.trimIndent()
    }
}
