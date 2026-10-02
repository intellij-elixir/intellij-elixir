package org.elixir_lang.beam.decompiler

/** The names `Macro.generate_arguments/2` gives a definition whose source names are not known. */
fun generatedArguments(arity: Int): List<String> = List(arity) { "arg${it + 1}" }
