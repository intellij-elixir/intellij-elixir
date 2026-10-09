# Regenerates <leg>/A.txt beside this script: `Macro.expand_once/2` of each `use` and sigil case below, for every Elixir/OTP
# pair .github/ci-versions.json declares, and for the pairs @extra_pairs names, whose directory is
# `<elixir>-otp-<major>`: the output of `~r` depends on the OTP release as well as the Elixir one. Run it from the
# repository root with the mise.toml pins (1.18 or later, for JSON):
#
#     mise exec -- elixir testData/org/elixir_lang/expander/sigil_output/generate.exs
#
# It runs itself once per pair under that pair's Elixir and OTP (`mise exec elixir@<elixir>-otp-<major>
# erlang@<otp>`), so every pair must be installed in mise. `SigilOutputTest` renders the expander's output with the
# same rules as `render/1`.
#
# Each case is a header, `## <label>: <source>`, or `## <label> (<context>): <source>` when it is expanded in a match
# or a guard, then one of
#
# - the output's quoted term, with `line` and `column` dropped from every node's metadata and `counter` written `:N`,
#   then `meta: ` and the sorted, deduplicated metadata of every node, each value shortened as `short/1` does. A
#   `Regex` struct is written `REGEX{source: <binary>, opts: <opts>, pattern: <pattern>, keys: <keys>}`, the keys in the
#   order the release writes them, and the compiled pattern, which is PCRE's, by its shape: `pattern` is `:escaped` for
#   the compiled tuple and `{:import, <metadata>, <source>, <options>}` for a call that imports it, whose header and
#   compiled bytes are left out, and the PCRE version is left out too;
# - `RAISE <exception module> <message>`, or `THROW :error <message>` for the `{:error, charlist}` Elixir before 1.12
#   throws, or `<KIND> <value>` for any other throw or exit;
# - `SKIP`, on a release whose parser lacks the case's syntax.
defmodule SigilOutput do
  @cases [
    # label, source, context, the first release that parses it
    {"use_alias", "use Foo", nil, "1.11.0"},
    {"use_alias_opts", "use Foo, a: 1", nil, "1.11.0"},
    {"use_atom", "use :foo", nil, "1.11.0"},
    {"use_erlang_atom_opts", "use :foo, [1, 2]", nil, "1.11.0"},
    {"use_nested_alias", "use Foo.Bar", nil, "1.11.0"},
    {"use_multi", "use Foo.{A, B}", nil, "1.11.0"},
    {"use_multi_opts", "use Foo.{A, B}, x", nil, "1.11.0"},
    {"use_multi_atom", "use Foo.{:a, :b}", nil, "1.11.0"},
    {"use_multi_other", "use Foo.{A, x}", nil, "1.11.0"},
    {"use_multi_nested", "use Foo.{A.B, C}", nil, "1.11.0"},
    {"use_multi_single", "use Foo.{A}", nil, "1.11.0"},
    {"use_multi_module_base", "use __MODULE__.{A, B}", nil, "1.11.0"},
    {"use_module", "use __MODULE__", nil, "1.11.0"},
    {"use_var", "use x", nil, "1.11.0"},
    {"use_integer", "use 1", nil, "1.11.0"},
    {"use_call", "use f()", nil, "1.11.0"},
    {"use_dir", "use __DIR__", nil, "1.11.0"},
    {"sigil_S_text", ~S|~S(a\nb#{c})|, nil, "1.11.0"},
    {"sigil_S_empty", ~S|~S()|, nil, "1.11.0"},
    {"sigil_S_modifier", ~S|~S(a)x|, nil, "1.11.0"},
    {"sigil_S_call", ~S|sigil_S(x, [])|, nil, "1.11.0"},
    {"sigil_S_call_modifier", ~S|sigil_S(<<"a">>, 'x')|, nil, "1.11.0"},
    {"sigil_s_text", ~S|~s(a\nb)|, nil, "1.11.0"},
    {"sigil_s_empty", ~S|~s()|, nil, "1.11.0"},
    {"sigil_s_interp", ~S|~s(a#{x}b)|, nil, "1.11.0"},
    {"sigil_s_interp_escape", ~S|~s(a\n#{x}\tb)|, nil, "1.11.0"},
    {"sigil_s_escaped_interp", ~S|~s(a\#{x})|, nil, "1.11.0"},
    {"sigil_s_modifier", ~S|~s(a)x|, nil, "1.11.0"},
    {"sigil_s_call", ~S|sigil_s(x, [])|, nil, "1.11.0"},
    {"sigil_s_call_line_continuation", ~S|sigil_s(<<"a\\\nb">>, [])|, nil, "1.11.0"},
    {"sigil_s_call_crlf_continuation", ~S|sigil_s(<<"a\\\r\nb">>, [])|, nil, "1.11.0"},
    {"sigil_s_escapes", ~S|~s(\0\a\b\d\e\f\n\r\s\t\v\q)|, nil, "1.11.0"},
    {"sigil_s_hex", ~S|~s(\x41\xfF)|, nil, "1.11.0"},
    {"sigil_s_unicode", ~S|~s(\u0041\u{1F600}\u{41})|, nil, "1.11.0"},
    {"sigil_s_heredoc", "~s\"\"\"\n  a\n   b\n  \"\"\"", nil, "1.11.0"},
    {"sigil_s_heredoc_interp", "~s\"\"\"\n  a\#{x}\n  \"\"\"", nil, "1.11.0"},
    {"sigil_S_heredoc", "~S\"\"\"\n  a\#{x}\n  \"\"\"", nil, "1.11.0"},
    {"sigil_c_heredoc_interp", "~c\"\"\"\n  a\#{x}\n  \"\"\"", nil, "1.11.0"},
    {"sigil_w_heredoc_interp", "~w\"\"\"\n  a \#{x}\n  \"\"\"", nil, "1.11.0"},
    {"sigil_s_line_continuation","~s(a\\\nb)", nil, "1.11.0"},
    {"sigil_s_interp_line_continuation", "~s(a\\\n\#{x})", nil, "1.11.0"},
    {"sigil_s_bad_hex", ~S|~s(\xZZ)|, nil, "1.11.0"},
    {"sigil_s_short_hex", ~S|~s(\x1)|, nil, "1.11.0"},
    {"sigil_s_braced_hex", ~S|~s(\x{41})|, nil, "1.11.0"},
    {"sigil_s_interp_bad_hex", ~S|~s(\xZZ#{x})|, nil, "1.11.0"},
    {"sigil_s_interp_short_hex", ~S|~s(\x1#{x})|, nil, "1.11.0"},
    {"sigil_s_bad_unicode", ~S|~s(\uZZ)|, nil, "1.11.0"},
    {"sigil_s_surrogate", ~S|~s(\u{D800})|, nil, "1.11.0"},
    {"sigil_s_interp_bad_unicode", ~S|~s(\uZZ#{x})|, nil, "1.11.0"},
    {"sigil_C_text", ~S|~C(a\nb#{c})|, nil, "1.11.0"},
    {"sigil_C_empty", ~S|~C()|, nil, "1.11.0"},
    {"sigil_C_unicode", ~S|~C(héllo)|, nil, "1.11.0"},
    {"sigil_C_modifier", ~S|~C(a)x|, nil, "1.11.0"},
    {"sigil_C_call", ~S|sigil_C(x, [])|, nil, "1.11.0"},
    {"sigil_c_text", ~S|~c(a\nb)|, nil, "1.11.0"},
    {"sigil_c_empty", ~S|~c()|, nil, "1.11.0"},
    {"sigil_c_unicode", ~S|~c(héllo)|, nil, "1.11.0"},
    {"sigil_c_interp", ~S|~c(a#{x})|, nil, "1.11.0"},
    {"sigil_c_interp_escape", ~S|~c(a\n#{x}\tb)|, nil, "1.11.0"},
    {"sigil_c_modifier", ~S|~c(a)x|, nil, "1.11.0"},
    {"sigil_c_interp_modifier", ~S|~c(a#{x})x|, nil, "1.11.0"},
    {"sigil_c_call", ~S|sigil_c(x, [])|, nil, "1.11.0"},
    {"sigil_c_call_line_continuation", ~S|sigil_c(<<"a\\\nb">>, [])|, nil, "1.11.0"},
    {"sigil_c_bad_hex", ~S|~c(\xZZ)|, nil, "1.11.0"},
    {"sigil_r_plain", ~S|~r/a+/|, nil, "1.11.0"},
    {"sigil_r_empty", ~S|~r//|, nil, "1.11.0"},
    {"sigil_r_opts", ~S|~r/a/i|, nil, "1.11.0"},
    {"sigil_r_all_opts", ~S|~r/a/iumxfU|, nil, "1.11.0"},
    {"sigil_r_modifier_r", ~S|~r/a/r|, nil, "1.11.0"},
    {"sigil_r_dotall", ~S|~r/a/s|, nil, "1.11.0"},
    {"sigil_r_bad_modifier", ~S|~r/a/k|, nil, "1.11.0"},
    {"sigil_r_interp", ~S|~r/a#{x}/|, nil, "1.11.0"},
    {"sigil_r_interp_opts", ~S|~r/a#{x}/i|, nil, "1.11.0"},
    {"sigil_r_interp_bad_modifier", ~S|~r/a#{x}/k|, nil, "1.11.0"},
    {"sigil_r_newline_escape", ~S|~r/a\nb/|, nil, "1.11.0"},
    {"sigil_r_escape_tab", ~S|~r/a\tb/|, nil, "1.11.0"},
    {"sigil_r_escape_tab_interp", ~S|~r/a#{x}\tb/|, nil, "1.11.0"},
    {"sigil_r_escape_controls", ~S|~r/\a\f\r\v\d/|, nil, "1.11.0"},
    {"sigil_r_hex", ~S|~r/\x41/|, nil, "1.11.0"},
    {"sigil_r_line_continuation", "~r/a\\\nb/", nil, "1.11.0"},
    {"sigil_r_escaped_delimiter", ~S|~r/a\/b/|, nil, "1.11.0"},
    {"sigil_r_match", ~S|~r/a/|, :match, "1.11.0"},
    {"sigil_r_guard", ~S|~r/a/|, :guard, "1.11.0"},
    {"sigil_r_interp_match", ~S|~r/a#{x}/|, :match, "1.11.0"},
    {"sigil_r_call", ~S|sigil_r(x, [])|, nil, "1.11.0"},
    {"sigil_r_call_line_continuation", ~S|sigil_r(<<"a\\\nb">>, [])|, nil, "1.11.0"},
    {"sigil_r_call_crlf_continuation", ~S|sigil_r(<<"a\\\r\nb">>, [])|, nil, "1.11.0"},
    {"sigil_R_plain", ~S|~R/a+/|, nil, "1.11.0"},
    {"sigil_R_opts", ~S|~R/a/i|, nil, "1.11.0"},
    {"sigil_R_escape_tab", ~S|~R/a\tb/|, nil, "1.11.0"},
    {"sigil_R_bad_modifier", ~S|~R/a/k|, nil, "1.11.0"},
    {"sigil_R_match", ~S|~R/a/|, :match, "1.11.0"},
    {"sigil_R_call", ~S|sigil_R(x, [])|, nil, "1.11.0"},
    {"sigil_D_iso", ~S|~D[2015-01-13]|, nil, "1.11.0"},
    {"sigil_D_explicit_iso", ~S|~D[2015-01-13 Calendar.ISO]|, nil, "1.11.0"},
    {"sigil_D_lowercase_tail", ~S|~D[2015-01-13 iso]|, nil, "1.11.0"},
    {"sigil_D_invalid", ~S|~D[2015-13-45]|, nil, "1.11.0"},
    {"sigil_D_modifier", ~S|~D[2015-01-13]x|, nil, "1.11.0"},
    {"sigil_D_call", ~S|sigil_D(x, [])|, nil, "1.11.0"},
    {"sigil_T_hms", ~S|~T[13:00:07]|, nil, "1.11.0"},
    {"sigil_T_micro", ~S|~T[13:00:07.123]|, nil, "1.11.0"},
    {"sigil_T_explicit_iso", ~S|~T[13:00:07 Calendar.ISO]|, nil, "1.11.0"},
    {"sigil_T_invalid", ~S|~T[25:00:07]|, nil, "1.11.0"},
    {"sigil_T_modifier", ~S|~T[13:00:07]x|, nil, "1.11.0"},
    {"sigil_N_space", ~S|~N[2015-01-13 13:00:07]|, nil, "1.11.0"},
    {"sigil_N_t", ~S|~N[2015-01-13T13:00:07.123]|, nil, "1.11.0"},
    {"sigil_N_explicit_iso", ~S|~N[2015-01-13 13:00:07 Calendar.ISO]|, nil, "1.11.0"},
    {"sigil_N_invalid", ~S|~N[2015-01-13]|, nil, "1.11.0"},
    {"sigil_N_modifier", ~S|~N[2015-01-13 13:00:07]x|, nil, "1.11.0"},
    {"sigil_U_z", ~S|~U[2015-01-13 13:00:07Z]|, nil, "1.11.0"},
    {"sigil_U_zero_offset", ~S|~U[2015-01-13T13:00:07.123+00:00]|, nil, "1.11.0"},
    {"sigil_U_offset", ~S|~U[2015-01-13 13:00:07+01:00]|, nil, "1.11.0"},
    {"sigil_U_offset_before_first_day", ~S|~U[-9999-01-01 00:00:00+01:00]|, nil, "1.11.0"},
    {"sigil_U_offset_after_last_day", ~S|~U[9999-12-31 23:00:00-01:00]|, nil, "1.11.0"},
    {"sigil_U_offset_within_last_day", ~S|~U[9999-12-31 22:00:00-01:00]|, nil, "1.11.0"},
    {"sigil_U_offset_onto_last_day", ~S|~U[9999-12-30 23:00:00-01:00]|, nil, "1.11.0"},
    {"sigil_U_offset_onto_first_day", ~S|~U[-9999-01-02 00:00:00+01:00]|, nil, "1.11.0"},
    {"sigil_U_invalid", ~S|~U[2015-01-13]|, nil, "1.11.0"},
    {"sigil_U_explicit_iso", ~S|~U[2015-01-13 13:00:07Z Calendar.ISO]|, nil, "1.11.0"},
    {"sigil_U_modifier", ~S|~U[2015-01-13 13:00:07Z]x|, nil, "1.11.0"},
    {"sigil_D_basic", ~S|~D[20150113]|, nil, "1.11.0"},
    {"sigil_D_negative_year", ~S|~D[-0001-01-01]|, nil, "1.11.0"},
    {"sigil_D_positive_year", ~S|~D[+2015-01-13]|, nil, "1.11.0"},
    {"sigil_D_leap_day", ~S|~D[2016-02-29]|, nil, "1.11.0"},
    {"sigil_D_not_leap_day", ~S|~D[2015-02-29]|, nil, "1.11.0"},
    {"sigil_D_empty", ~S|~D[]|, nil, "1.11.0"},
    {"sigil_D_two_spaces", ~S|~D[2015-01-13  Calendar.ISO]|, nil, "1.11.0"},
    {"sigil_D_time_tail", ~S|~D[2015-01-13 13:00:07]|, nil, "1.11.0"},
    {"sigil_T_basic", ~S|~T[130007]|, nil, "1.11.0"},
    {"sigil_T_t_prefix", ~S|~T[T13:00:07]|, nil, "1.11.0"},
    {"sigil_T_z", ~S|~T[13:00:07Z]|, nil, "1.11.0"},
    {"sigil_T_offset", ~S|~T[13:00:07+01:00]|, nil, "1.11.0"},
    {"sigil_T_long_fraction", ~S|~T[13:00:07.1234567]|, nil, "1.11.0"},
    {"sigil_T_comma_fraction", ~S|~T[13:00:07,5]|, nil, "1.11.0"},
    {"sigil_T_empty_fraction", ~S|~T[13:00:07.]|, nil, "1.11.0"},
    {"sigil_T_hour_24", ~S|~T[24:00:00]|, nil, "1.11.0"},
    {"sigil_T_second_60", ~S|~T[13:00:60]|, nil, "1.11.0"},
    {"sigil_N_offset", ~S|~N[2015-01-13 13:00:07-02:30]|, nil, "1.11.0"},
    {"sigil_N_z", ~S|~N[2015-01-13T13:00:07Z]|, nil, "1.11.0"},
    {"sigil_N_basic", ~S|~N[20150113 130007]|, nil, "1.11.0"},
    {"sigil_N_invalid_date", ~S|~N[2015-02-30 13:00:07]|, nil, "1.11.0"},
    {"sigil_N_invalid_time", ~S|~N[2015-01-13 25:00:07]|, nil, "1.11.0"},
    {"sigil_U_missing_offset", ~S|~U[2015-01-13 13:00:07]|, nil, "1.11.0"},
    {"sigil_U_minus_zero", ~S|~U[2015-01-13 13:00:07-00:00]|, nil, "1.11.0"},
    {"sigil_U_short_zero_offset", ~S|~U[2015-01-13 13:00:07+00]|, nil, "1.11.0"},
    {"sigil_U_compact_zero_offset", ~S|~U[2015-01-13 13:00:07+0000]|, nil, "1.11.0"},
    {"sigil_U_invalid_date", ~S|~U[2015-02-30 13:00:07Z]|, nil, "1.11.0"},
    {"sigil_U_invalid_time", ~S|~U[2015-01-13 25:00:07Z]|, nil, "1.11.0"},
    {"sigil_U_invalid_offset", ~S|~U[2015-01-13 13:00:07+25:00]|, nil, "1.11.0"},
    {"sigil_w_plain", ~S|~w(a b)|, nil, "1.11.0"},
    {"sigil_w_empty", ~S|~w()|, nil, "1.11.0"},
    {"sigil_w_blank", ~S|~w(  )|, nil, "1.11.0"},
    {"sigil_w_s", ~S|~w(a b)s|, nil, "1.11.0"},
    {"sigil_w_a", ~S|~w(a b)a|, nil, "1.11.0"},
    {"sigil_w_c", ~S|~w(a b)c|, nil, "1.11.0"},
    {"sigil_w_escape", ~S|~w(a\nb c\sd)|, nil, "1.11.0"},
    {"sigil_w_line_continuation", "~w(a\\\nb)", nil, "1.11.0"},
    {"sigil_w_newline", "~w(a\nb)", nil, "1.11.0"},
    {"sigil_w_trailing_comma", ~S|~w(a, b)|, nil, "1.11.0"},
    {"sigil_w_interp", ~S|~w(a #{x})|, nil, "1.11.0"},
    {"sigil_w_interp_a", ~S|~w(a #{x})a|, nil, "1.11.0"},
    {"sigil_w_interp_c", ~S|~w(a #{x})c|, nil, "1.11.0"},
    {"sigil_w_interp_s", ~S|~w(a #{x})s|, nil, "1.11.0"},
    {"sigil_w_bad_modifier", ~S|~w(a b)x|, nil, "1.11.0"},
    {"sigil_w_two_modifiers", ~S|~w(a b)sa|, nil, "1.11.0"},
    {"sigil_w_interp_bad_modifier", ~S|~w(a #{x})x|, nil, "1.11.0"},
    {"sigil_w_call", ~S|sigil_w(x, [])|, nil, "1.11.0"},
    {"sigil_w_call_line_continuation", ~S|sigil_w(<<"a\\\nb">>, [])|, nil, "1.11.0"},
    {"sigil_w_call_modifier", ~S|sigil_w(<<"a">>, x)|, nil, "1.11.0"},
    {"sigil_w_call_large_modifier", ~S|sigil_w(<<"a b">>, [65633])|, nil, "1.11.0"},
    {"sigil_W_plain", ~S|~W(a b)|, nil, "1.11.0"},
    {"sigil_W_empty", ~S|~W()|, nil, "1.11.0"},
    {"sigil_W_s", ~S|~W(a b)s|, nil, "1.11.0"},
    {"sigil_W_a", ~S|~W(a b)a|, nil, "1.11.0"},
    {"sigil_W_c", ~S|~W(a b)c|, nil, "1.11.0"},
    {"sigil_W_text", ~S|~W(a\nb #{c})|, nil, "1.11.0"},
    {"sigil_W_trailing_comma", ~S|~W(a, b)|, nil, "1.11.0"},
    {"sigil_W_bad_modifier", ~S|~W(a b)x|, nil, "1.11.0"},
    {"sigil_W_call", ~S|sigil_W(x, [])|, nil, "1.11.0"},
    {"sigil_W_call_large_modifier", ~S|sigil_W(<<"a b">>, [65633])|, nil, "1.11.0"}
  ]

  # Pairs beyond the ones ci-versions.json declares: the OTP windows `~r` changes at, each on one OTP release so a
  # newer install on the machine cannot change what is written.
  @extra_pairs [{"1.18.4", "28.4"}, {"1.19.5", "27.3.4.12"}, {"1.20.4", "27.3.4.12"}, {"1.20.4", "28.4"}]

  def run(env) do
    otp = File.read!(Path.join([:code.root_dir(), "releases", System.otp_release(), "OTP_VERSION"]))

    IO.puts("# Macro.expand_once/2, Elixir #{System.version()}, OTP #{String.trim(otp)}")

    for {label, source, context, since} <- @cases do
      IO.puts("## #{label}#{if context, do: " (#{context})"}: #{String.replace(source, "\n", "⏎")}")
      IO.puts(show(source, %{env | context: context}, since))
    end
  end

  def extra_pairs, do: @extra_pairs

  defp show(source, env, since) do
    if Version.compare(System.version(), since) == :lt do
      "SKIP"
    else
      try do
        output = Macro.expand_once(Code.string_to_quoted!(source), env)

        render(output) <> "\nmeta: " <> render(meta(output))
      rescue
        exception -> "RAISE #{render(exception.__struct__)} #{render(Exception.message(exception))}"
      catch
        :throw, {:error, message} when is_list(message) -> "THROW :error #{render(List.to_string(message))}"
        kind, value -> "#{kind |> Atom.to_string() |> String.upcase()} #{render(value)}"
      end
    end
  end

  @doc "Every node's metadata other than `line` and `column`, each value shortened, sorted and deduplicated."
  def meta(ast) do
    {_, acc} =
      Macro.prewalk(ast, MapSet.new(), fn
        {_, meta, _} = node, acc when is_list(meta) ->
          {node, Enum.reduce(meta, acc, fn {key, value}, acc -> if key in [:line, :column], do: acc, else: MapSet.put(acc, {key, short(key, value)}) end)}

        node, acc ->
          {node, acc}
      end)

    acc |> MapSet.to_list() |> Enum.sort()
  end

  defp short(:counter, _), do: :N
  defp short(_, value) when is_atom(value) or is_integer(value), do: value
  defp short(_, {a, b}) when is_atom(a) and is_atom(b), do: {a, b}
  defp short(_, value) when is_list(value), do: :list
  defp short(_, _), do: :other

  @doc """
  One fixed text for a term on every release: an atom is `:name`, quoted unless it is an identifier; a binary is
  quoted byte by byte; lists and tuples are bracketed, with no keyword sugar. A node's metadata loses `line` and
  `column`, and its `counter` is `:N`. A `Regex` struct is `REGEX{...}`, as the header says.
  """
  def render(atom) when is_atom(atom) do
    name = Atom.to_string(atom)

    if name =~ ~r/\A[A-Za-z_][A-Za-z0-9_@]*[?!]?\z/, do: ":" <> name, else: ":" <> render(name)
  end

  def render(integer) when is_integer(integer), do: Integer.to_string(integer)
  def render(float) when is_float(float), do: inspect(float)

  def render(binary) when is_binary(binary) do
    escaped =
      for <<byte <- binary>>, into: "" do
        case byte do
          ?" -> "\\\""
          ?\\ -> "\\\\"
          byte when byte in 0x20..0x7E -> <<byte>>
          byte -> "\\x" <> String.pad_leading(Integer.to_string(byte, 16), 2, "0")
        end
      end

    "\"" <> escaped <> "\""
  end

  def render({:%{}, meta, entries}) when is_list(meta) and is_list(entries) do
    if List.keyfind(entries, :__struct__, 0) == {:__struct__, Regex} do
      {_, source} = List.keyfind(entries, :source, 0)
      {_, opts} = List.keyfind(entries, :opts, 0)
      {_, re_pattern} = List.keyfind(entries, :re_pattern, 0)

      # `Regex.__escape__/1` writes the fields in one fixed order. Before it, `Macro.escape/1` lists a map's entries in
      # the order the Erlang VM keeps them until 1.15.0-rc.1 sorts them, which on OTP 26 and later is by atom index.
      keys = Keyword.keys(entries)

      "{" <> render(:%{}) <> ", " <> render(kept(meta)) <> ", REGEX{source: " <> render(source) <> ", opts: " <> render(opts) <>
        ", pattern: " <> pattern(re_pattern) <> ", keys: " <> render(keys) <> "}}"
    else
      "{" <> render(:%{}) <> ", " <> render(kept(meta)) <> ", " <> render(entries) <> "}"
    end
  end

  def render({form, meta, args}) when is_list(meta) do
    if Keyword.keyword?(meta) do
      "{" <> render(form) <> ", " <> render(kept(meta)) <> ", " <> render(args) <> "}"
    else
      render_tuple([form, meta, args])
    end
  end

  def render(tuple) when is_tuple(tuple), do: tuple |> Tuple.to_list() |> render_tuple()
  def render(list) when is_list(list), do: "[" <> render_list(list) <> "]"

  defp kept(meta), do: for({key, value} <- meta, key not in [:line, :column], do: {key, if(key == :counter, do: :N, else: value)})

  defp pattern({:{}, _, [:re_pattern, _captures, _unicode, _anchored, _binary]}), do: ":escaped"
  defp pattern({{:., _, [_, :__import_pattern__]}, meta, [{:{}, _, [:re_exported_pattern, _header, source, opts, _compiled]}]}),
    do: "{:import, " <> render(kept(meta)) <> ", " <> render(source) <> ", " <> render(opts) <> "}"

  defp pattern(other), do: render(other)

  defp render_tuple(elements), do: "{" <> Enum.map_join(elements, ", ", &render/1) <> "}"

  defp render_list([]), do: ""
  defp render_list([last]), do: render(last)
  defp render_list([head | tail]) when is_list(tail), do: render(head) <> ", " <> render_list(tail)
  defp render_list([head | tail]), do: render(head) <> " | " <> render(tail)
end

case System.argv() do
  ["--leg"] ->
    SigilOutput.run(__ENV__)

  [] ->
    unless Version.match?(System.version(), ">= 1.18.0") do
      raise "generate.exs needs Elixir 1.18 or later for JSON; this is #{System.version()}"
    end

    here = __DIR__
    beam = Path.expand("../../../../../.github/ci-versions.json", here) |> File.read!() |> JSON.decode!() |> Map.fetch!("beam")

    declared =
      for %{"elixir" => elixir, "otp" => otp} <- [beam["baseline"] | beam["additional"] || []] do
        {elixir, otp |> String.split(".") |> hd(), otp, elixir}
      end

    extra =
      for {elixir, otp} <- SigilOutput.extra_pairs() do
        major = otp |> String.split(".") |> hd()

        {elixir, major, otp, "#{elixir}-otp-#{major}"}
      end

    for {elixir, major, otp, dir} <- declared ++ extra do
      mise = ["exec", "elixir@#{elixir}-otp-#{major}", "erlang@#{otp}", "--", "elixir", __ENV__.file, "--leg"]

      case System.cmd("mise", mise, stderr_to_stdout: false) do
        {output, 0} ->
          File.mkdir_p!(Path.join(here, dir))
          File.write!(Path.join([here, dir, "A.txt"]), output)

        {output, status} ->
          raise "generate.exs under Elixir #{elixir} / OTP #{otp} exited #{status}:\n#{output}"
      end
    end
end
