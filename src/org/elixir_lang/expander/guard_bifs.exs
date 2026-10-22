# Prints GuardBifs.kt: the `:erlang` functions `elixir_rewrite:allowed_guard/2` admits in a guard on the Erlang/OTP
# running it, and the binary operators among them `elixir_utils:guard_op/2` holds for. Run it from the repository root
# on OTP 24:
#
#     mise exec erlang@24.3.4.6 elixir@1.11.4-otp-24 -- elixir src/org/elixir_lang/expander/guard_bifs.exs > src/org/elixir_lang/expander/GuardBifs.kt
#
# The guards later OTP releases add are `ElixirLanguageFeature` entries with `sinceOtp`.
otp =
  [:code.root_dir(), "releases", :erlang.system_info(:otp_release), "OTP_VERSION"]
  |> Path.join()
  |> File.read!()
  |> String.trim()

# `allowed_guard/2`'s first clauses refuse these, which `erl_internal:guard_bif/2` admits.
refused = [is_record: 2, is_record: 3]
# `elixir_utils:guard_op/2` admits these, which `:erlang` doesn't export.
operators = [andalso: 2, orelse: 2]

functions = :erlang.module_info(:exports) ++ operators

admitted =
  for {name, arity} <- functions,
      {name, arity} not in refused,
      :erl_internal.guard_bif(name, arity) or :elixir_utils.guard_op(name, arity),
      uniq: true,
      do: {Atom.to_string(name), arity}

entries =
  admitted
  |> Enum.sort()
  |> Enum.map_join(",\n", fn {name, arity} -> "    NameArity(\"#{name}\", #{arity})" end)

binary_operators =
  for({name, 2} <- functions, :elixir_utils.guard_op(name, 2), uniq: true, do: Atom.to_string(name))
  |> Enum.sort()
  |> Enum.map_join(",\n", &"    \"#{&1}\"")

IO.puts("""
package org.elixir_lang.expander

import org.elixir_lang.NameArity

/**
 * The `:erlang` functions `elixir_rewrite:allowed_guard/2` admits in a guard on Erlang/OTP 24: each that
 * `erl_internal:guard_bif/2` or `elixir_utils:guard_op/2` holds for, less `is_record/2,3`. Generated on OTP #{otp} by
 * `guard_bifs.exs`.
 */
internal val OTP_24_GUARD_BIFS: Set<NameArity> = setOf(
#{entries},
)

/**
 * The binary operators `elixir_utils:guard_op/2` holds for on Erlang/OTP 24, which `elixir_erl_pass` translates to an
 * operator. Generated on OTP #{otp} by `guard_bifs.exs`.
 */
internal val GUARD_OPERATORS: Set<String> = setOf(
#{binary_operators},
)\
""")
