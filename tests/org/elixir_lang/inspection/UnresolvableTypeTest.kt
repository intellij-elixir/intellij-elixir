package org.elixir_lang.inspection

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.model.psi.config.withSigilInjection

class UnresolvableTypeTest : BeamLibraryTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(UnresolvableType::class.java)
    }

    fun testMissingTypeOnResolvedModuleFlagged() {
        myFixture.configureByFiles("missing_type_on_resolved_module_flagged.ex", "other.ex")
        myFixture.checkHighlighting()
    }

    fun testResolvedTypeNotFlagged() {
        myFixture.configureByFiles("resolved_type_not_flagged.ex", "other.ex")
        myFixture.checkHighlighting()
    }

    fun testMissingLocalTypeFlagged() {
        myFixture.configureByFiles("missing_local_type_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testTypeVariableNotFlagged() {
        myFixture.configureByFiles("type_variable_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testSpecBareMissingTypeFlagged() {
        myFixture.configureByFiles("spec_bare_missing_type_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testTypeBodyUnboundVariableFlagged() {
        myFixture.configureByFiles("type_body_unbound_variable_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testSpecWhenBoundVariableNotFlagged() {
        myFixture.configureByFiles("spec_when_bound_variable_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testLocalTypeInReturnNotFlagged() {
        myFixture.configureByFiles("local_type_in_return_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testBuiltinTypeNotFlagged() {
        myFixture.configureByFiles("builtin_type_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testUnresolvableModuleNotFlaggedByTypeInspection() {
        myFixture.configureByFiles("unresolvable_module_not_flagged_by_type.ex")
        myFixture.checkHighlighting()
    }

    fun testNamedTypeLabelNotFlagged() {
        myFixture.configureByFiles("named_type_label_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    /**
     * A named spec argument whose label happens to match the type it annotates, e.g. `my_id :: my_id()`, must still
     * resolve the `my_id()` on the right to the `@type my_id`. The label on the left must not shadow the type.
     */
    fun testSpecNamedArgLabelSameNameAsTypeNotFlagged() {
        myFixture.configureByFiles("spec_named_arg_label_same_name_as_type_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testSelfQualifiedTypeNotFlagged() {
        myFixture.configureByFiles("self_qualified_type_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testSpecHeadTypeReferenceNotFlagged() {
        myFixture.configureByFiles("spec_head_type_reference_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    /**
     * `required(...)` and `optional(...)` are map-field optionality markers in a map type, not type usages
     * (they wrap the key type of a `%{... => ...}` association), so they must not be flagged as undefined types.
     */
    fun testMapFieldOptionalityNotFlagged() {
        myFixture.configureByFiles("map_field_optionality_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    /**
     * A qualified type from a decompiled BEAM module (`:queue.queue()` from the Erlang stdlib) resolves to a
     * [org.elixir_lang.beam.psi.impl.TypeDefinitionImpl] built from the beam's type data, not to a source
     * `@type` [org.elixir_lang.psi.call.Call], so the inspection must treat it as resolved via
     * [org.elixir_lang.model.psi.type.TypeReference.resolvesToType] rather than the source-only
     * [org.elixir_lang.model.psi.type.TypeReference.resolveSymbols].
     */
    fun testBeamQualifiedTypeNotFlagged() {
        myFixture.configureByFiles("beam_qualified_type_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    /** `queue.beam`'s types are read from its Erlang abstract code, so one it doesn't define is reported. */
    fun testBeamQualifiedMissingTypeFlagged() {
        myFixture.configureByFiles("beam_qualified_missing_type_flagged.ex")
        myFixture.checkHighlighting()
    }

    /** `no_debug_info.beam` was compiled without `debug_info`, so its types aren't known: none is reported. */
    fun testBeamWithoutDebugInfoTypeNotFlagged() {
        myFixture.configureByFiles("beam_without_debug_info_type_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    /** `from_core.beam` was compiled from Core Erlang, so its abstract code has no forms: none of its types is reported. */
    fun testBeamFromCoreErlangTypeNotFlagged() {
        myFixture.configureByFiles("beam_from_core_erlang_type_not_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testTemplateMissingTypeOnResolvedModuleFlagged() {
        myFixture.withSigilInjection {
            myFixture.configureByFiles("template_missing_type_on_resolved_module_flagged.ex", "other.ex")
            myFixture.checkHighlighting()
        }
    }

    fun testDocCodeBlockTypeNotFlagged() {
        myFixture.withSigilInjection {
            myFixture.configureByFiles("doc_code_block_type_not_flagged.ex", "other.ex")
            myFixture.checkHighlighting()
        }
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/inspection/unresolvable_type"
}
