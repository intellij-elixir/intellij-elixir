package org.elixir_lang.code

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject

typealias Precedence = Int

// https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/code/identifier.ex
object Identifier {
    enum class Associativity {
        LEFT,
        NON_ASSOCIATIVE,
        RIGHT
    }


    data class AssociativityPrecedence(val associativity: Associativity, val precedence: Precedence)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/code/identifier.ex#L23-L53
    fun binaryOperator(atom: OtpErlangAtom) = binaryOperator(atom.atomValue())

    fun binaryOperator(term: OtpErlangObject) =
        when (term) {
            is OtpErlangAtom -> binaryOperator(term)
            else -> null
        }

    fun binaryOperator(atomValue: String): AssociativityPrecedence? =
        when(atomValue) {
            "->" -> AssociativityPrecedence(Associativity.RIGHT, 10)
            "<-", "\\\\" -> AssociativityPrecedence(Associativity.LEFT, 40)
            "when" -> AssociativityPrecedence(Associativity.RIGHT, 50)
            "::" -> AssociativityPrecedence(Associativity.RIGHT, 60)
            "|" -> AssociativityPrecedence(Associativity.RIGHT, 70)
            "=" -> AssociativityPrecedence(Associativity.RIGHT, 100)
            "||", "|||", "or" -> AssociativityPrecedence(Associativity.LEFT, 130)
            "&&", "&&&", "and" -> AssociativityPrecedence(Associativity.LEFT, 140)
            "==", "!=", "=~", "===", "!==" -> AssociativityPrecedence(Associativity.LEFT, 150)
            "<", "<=", ">=", ">" -> AssociativityPrecedence(Associativity.LEFT, 160)
            "|>", "<<<", ">>>", "<~", "~>", "<<~", "~>>", "<~>", "<|>" -> AssociativityPrecedence(Associativity.LEFT, 170)
            "in" -> AssociativityPrecedence(Associativity.LEFT, 180)
            "^^^" -> AssociativityPrecedence(Associativity.LEFT, 190)
            "++", "--", "..", "<>" -> AssociativityPrecedence(Associativity.RIGHT, 200)
            "+", "-" -> AssociativityPrecedence(Associativity.LEFT, 210)
            "*", "/" -> AssociativityPrecedence(Associativity.LEFT, 220)
            "." -> AssociativityPrecedence(Associativity.LEFT, 310)
            else -> null
        }

    /** The name of a remote call, or with [local] of a local call or definition head. */
    fun inspectAsFunction(atom: OtpErlangAtom, local: Boolean = false): String =
            inspectAsFunction(atom.atomValue(), local)

    fun inspectAsFunction(string: String, local: Boolean = false): String =
            if (local) InspectAtom.localCall(string) else InspectAtom.remoteCall(string)

    fun inspectAsKey(atom: OtpErlangAtom): String = inspectAsKey(atom.atomValue())

    fun inspectAsKey(atomValue: String): String = InspectAtom.key(atomValue)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/code/identifier.ex#L4-L21
    fun unaryOperator(atom: OtpErlangAtom) = unaryOperator(atom.atomValue())

    fun unaryOperator(term: OtpErlangObject) =
            when (term) {
                is OtpErlangAtom -> unaryOperator(term)
                else -> null
            }

    fun unaryOperator(atomValue: String): AssociativityPrecedence? =
        when (atomValue) {
            "&" -> AssociativityPrecedence(Associativity.NON_ASSOCIATIVE, 90)
            "!", "^", "not", "+", "-", "~~~" -> AssociativityPrecedence(Associativity.NON_ASSOCIATIVE, 300)
            "@" -> AssociativityPrecedence(Associativity.NON_ASSOCIATIVE, 320)
            else -> null
        }
}
