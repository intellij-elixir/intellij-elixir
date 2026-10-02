package org.elixir_lang.expander

import org.junit.Assert.assertEquals
import org.junit.Test

/** [ErrorKinds]' `super` and local-capture patterns against the messages 1.11.4 and 1.20.4 give. */
class SuperCaptureErrorKindsTest {
    @Test
    fun `each message matches its kind`() {
        val messages = listOf(
            "invalid_expr_in_scope" to "cannot invoke super outside function",
            "invalid_expr_in_scope" to "cannot invoke super outside module",
            "undefined_local_capture" to "undefined function foo/1",
            "undefined_local_capture" to "undefined function foo/1 (there is no such import)",
        )

        assertEquals(
            emptyList<String>(),
            messages.filterNot { (kind, message) -> ErrorKinds.pattern(kind).containsMatchIn(message) }
                .map { (kind, message) -> "$kind: $message" }
        )
    }
}
