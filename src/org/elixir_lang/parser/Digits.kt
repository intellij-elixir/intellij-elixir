package org.elixir_lang.parser

fun isBinaryDigit(character: Char): Boolean = character == '0' || character == '1'

fun isOctalDigit(character: Char): Boolean = character in '0'..'7'

fun isDecimalDigit(character: Char): Boolean = character in '0'..'9'

fun isHexadecimalDigit(character: Char): Boolean =
    isDecimalDigit(character) || character in 'a'..'f' || character in 'A'..'F'
