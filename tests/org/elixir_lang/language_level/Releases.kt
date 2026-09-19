package org.elixir_lang.language_level

/** The language level of an Elixir release, for tests that pin one. */
fun elixir(version: String): ElixirLanguageLevel = ElixirLanguageLevel.of(version)
