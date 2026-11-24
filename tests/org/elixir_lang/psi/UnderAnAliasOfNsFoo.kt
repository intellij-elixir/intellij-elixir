package org.elixir_lang.psi

/**
 * A top-level [defines] and an `NS.`[defines], each defining `f`, then a `User` whose body is `alias NS.Foo` followed by
 * [body]: the file where a name that `alias NS.Foo` doesn't apply to reaches the top-level [defines].
 */
internal fun underAnAliasOfNsFoo(body: String, defines: String = "Foo"): String =
    "defmodule $defines do\n  def f, do: :top\nend\n\n" +
        "defmodule NS.$defines do\n  def f, do: :ns\nend\n\n" +
        "defmodule User do\n  alias NS.Foo\n" +
        body.lines().joinToString("") { if (it.isEmpty()) "\n" else "  $it\n" } +
        "end"
