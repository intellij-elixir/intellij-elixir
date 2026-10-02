package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** [ErrorKinds]' struct patterns against the messages 1.11.4 and 1.20.4 give, without the file and line. */
class StructErrorKindsTest {
    @Test
    fun `each struct message matches its kind`() {
        val messages = listOf(
            "undefined_struct" to
                "NoSuchStruct.__struct__/1 is undefined, cannot expand struct NoSuchStruct. Make sure the struct name " +
                "is correct. If the struct name exists and is correct but it still cannot be found, you likely have " +
                "cyclic module usage in your code",
            "undefined_struct" to
                "NoSuchStruct.__struct__/0 is undefined, cannot expand struct NoSuchStruct. Make sure the struct name " +
                "is correct. If the struct name exists and is correct but it still cannot be found, you likely have " +
                "cyclic module usage in your code",
            "undefined_struct" to ":lists.__struct__/1 is undefined, cannot expand struct :lists. Make sure",
            "inaccessible_struct" to
                "cannot access struct Case, the struct was not yet defined or the struct is being accessed in the " +
                "same context that defines it",
            "invalid_struct_name" to "expected struct name to be a compile time atom or alias, got: x",
            "invalid_struct_name_in_match" to
                "expected struct name in a match to be a compile time atom, alias or a variable, got: \"a\"",
            "invalid_key_for_struct" to "invalid key \"host\" for struct, struct keys must be atoms, got: ",
            "unknown_key_for_struct" to "unknown key :nope for struct URI",
            "unknown_key_for_struct" to "unknown key \"host\" for struct URI",
            "struct_unknown_key" to "key :nope not found",
            "struct_unknown_key" to "key \"host\" not found",
            "struct_missing_enforced_keys" to
                "the following keys must also be given when building struct Version: [:major, :minor, :patch]",
        )

        assertEquals(
            emptyList<String>(),
            messages.filterNot { (kind, message) -> ErrorKinds.pattern(kind).containsMatchIn(message) }
                .map { (kind, message) -> "$kind: $message" }
        )
    }

    @Test
    fun `the struct function's raises have no line`() =
        listOf("1.11.4", "1.20.4").map(ElixirLanguageLevel::of).forEach { level ->
            assertEquals(false, ErrorKinds.hasLine("struct_unknown_key", level))
            assertEquals(false, ErrorKinds.hasLine("struct_missing_enforced_keys", level))
            assertEquals(true, ErrorKinds.hasLine("undefined_struct", level))
        }
}
