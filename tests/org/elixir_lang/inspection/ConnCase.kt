package org.elixir_lang.inspection

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/** `MyApp.ConnCase`, whose `using` aliases `MyApp.Repo`. */
internal const val CONN_CASE =
    "defmodule MyApp.ConnCase do\n  use ExUnit.CaseTemplate\n\n  using do\n    quote do\n      alias MyApp.Repo\n    end\n  end\nend\n"

/** `MyApp.Repo`. */
internal fun CodeInsightTestFixture.addRepo() {
    addFileToProject("lib/repo.ex", "defmodule MyApp.Repo do\n  def insert!(x), do: x\nend\n")
}

/** [CONN_CASE] and the `MyApp.Repo` it aliases. */
internal fun CodeInsightTestFixture.addConnCase() {
    addRepo()
    addFileToProject("lib/conn_case.ex", CONN_CASE)
}
