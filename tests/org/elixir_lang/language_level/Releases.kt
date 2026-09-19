package org.elixir_lang.language_level

/** The language level of an Elixir release running on [otp], for tests that pin one. */
fun elixir(version: String, otp: String? = null): ElixirLanguageLevel = ElixirLanguageLevel.of(version, otp)
