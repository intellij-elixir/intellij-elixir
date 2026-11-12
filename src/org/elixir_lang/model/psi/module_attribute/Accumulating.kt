package org.elixir_lang.model.psi.module_attribute

import org.elixir_lang.language_level.ElixirLanguageFeature.AFTER_VERIFY_ACCUMULATES
import org.elixir_lang.language_level.ElixirLanguageFeature.NIFS_ACCUMULATES
import org.elixir_lang.language_level.ElixirLanguageLevel

/** `typespec?/1`. */
val TYPESPECS = setOf("type", "typep", "opaque", "spec", "callback", "macrocallback")

/** The attributes `Module` accumulates every write of at [level], with no `Module.register_attribute/3` call. */
fun accumulating(level: ElixirLanguageLevel): List<String> =
    ALWAYS_ACCUMULATING +
        listOfNotNull(
            "after_verify".takeIf { AFTER_VERIFY_ACCUMULATES.isSufficient(level) },
            "nifs".takeIf { NIFS_ACCUMULATES.isSufficient(level) },
        )

/** Whether `Module` accumulates every write of the attribute [name] at [level], with no `Module.register_attribute/3` call. */
fun accumulates(name: String, level: ElixirLanguageLevel): Boolean = name in accumulating(level)

private val ALWAYS_ACCUMULATING =
    listOf(
        "after_compile", "before_compile", "behaviour", "compile", "derive", "dialyzer", "external_resource",
        "on_definition", "optional_callbacks",
    ) + TYPESPECS
