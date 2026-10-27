package org.elixir_lang.unicode_util

/** Whether [codePoint] is a Unicode scalar value: a code point that isn't a surrogate, the only ones UTF-8 encodes. */
fun isScalarValue(codePoint: Long): Boolean = codePoint in 0L..0x10FFFFL && codePoint !in 0xD800L..0xDFFFL
