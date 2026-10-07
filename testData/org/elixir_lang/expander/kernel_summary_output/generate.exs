# Regenerates <elixir>/A.txt beside this script: `Macro.expand_once/2` of each case below, for every Elixir/OTP pair
# .github/ci-versions.json declares. Run it from the repository root with the mise.toml pins (1.18 or later, for
# JSON):
#
#     mise exec -- elixir testData/org/elixir_lang/expander/kernel_summary_output/generate.exs
#
# It runs itself once per pair under that pair's Elixir and OTP (`mise exec elixir@<elixir>-otp-<major>
# erlang@<otp>`), so every pair must be installed in mise. `KernelSummaryOutputTest` renders the expander's output
# with the same rules as `render/1`.
#
# Each case is a header, `## <label>: <source>`, or `## <label> (<context>): <source>` when it is expanded in a match
# or a guard, then one of
#
# - the output's quoted term, with `line` and `column` dropped from every node's metadata and `counter` written `:N`,
#   then `meta: ` and the sorted, deduplicated metadata of every node, each value shortened as `short/1` does;
# - `RAISE <exception module> <message>`, or `THROW <value>`;
# - `SKIP`, on a release whose parser lacks the case's syntax.
defmodule KernelSummaryOutput do
  @cases [
    # label, source, context, the first release that parses it
    {"if_true", "if c, do: a", nil, "1.11.0"},
    {"if_else", "if c, do: a, else: b", nil, "1.11.0"},
    {"if_is_integer", "if is_integer(c), do: a, else: b", nil, "1.11.0"},
    {"if_bad_keys", "if c, foo: a", nil, "1.11.0"},
    {"unless_true", "unless c, do: a", nil, "1.11.0"},
    {"unless_else", "unless c, do: a, else: b", nil, "1.11.0"},
    {"unless_bad_keys", "unless c, foo: a", nil, "1.11.0"},
    {"and_and_body", "a && b", nil, "1.11.0"},
    {"and_and_match", "a && b", :match, "1.11.0"},
    {"and_and_guard", "a && b", :guard, "1.11.0"},
    {"or_or_body", "a || b", nil, "1.11.0"},
    {"or_or_match", "a || b", :match, "1.11.0"},
    {"or_or_guard", "a || b", :guard, "1.11.0"},
    {"not_x", "!a", nil, "1.11.0"},
    {"not_not", "!!a", nil, "1.11.0"},
    {"not_match", "!a", :match, "1.11.0"},
    {"not_guard", "!a", :guard, "1.11.0"},
    {"or_body", "a or b", nil, "1.11.0"},
    {"or_match", "a or b", :match, "1.11.0"},
    {"or_guard", "a or b", :guard, "1.11.0"},
    {"and_body", "a and b", nil, "1.11.0"},
    {"and_match", "a and b", :match, "1.11.0"},
    {"and_guard", "a and b", :guard, "1.11.0"},
    {"pipe_local", "x |> f(1)", nil, "1.11.0"},
    {"pipe_remote", "x |> M.f(1)", nil, "1.11.0"},
    {"pipe3", "x |> f(1) |> g()", nil, "1.11.0"},
    {"pipe_right", "x |> (f(1) |> g())", nil, "1.11.0"},
    {"pipe_into_name", "x |> f", nil, "1.11.0"},
    {"pipe_into_capture", "x |> &f/1", nil, "1.11.0"},
    {"pipe_into_tuple", "x |> {1, 2, 3}", nil, "1.11.0"},
    {"pipe_into_map", "x |> %{}", nil, "1.11.0"},
    {"pipe_into_alias", "x |> Foo", nil, "1.11.0"},
    {"pipe_into_bitstring", "x |> <<y>>", nil, "1.11.0"},
    {"pipe_into_unquote", "x |> unquote()", nil, "1.11.0"},
    {"pipe_into_fn", "x |> fn y -> y end", nil, "1.11.0"},
    {"pipe_into_unary", "x |> +y", nil, "1.11.0"},
    {"pipe_into_brackets", "x |> y[1]", nil, "1.11.0"},
    {"pipe_into_access_get", "x |> Access.get(1)", nil, "1.11.0"},
    {"pipe_into_access_get_atom", "x |> :\"Elixir.Access\".get(y, 1)", nil, "1.11.0"},
    {"pipe_into_ellipsis", "x |> ...", nil, "1.11.0"},
    {"pipe_into_not", "x |> !y", nil, "1.11.0"},
    {"pipe_into_plus2", "x |> y + 1", nil, "1.11.0"},
    {"pipe_into_times", "x |> y * 1", nil, "1.11.0"},
    {"pipe_into_literal", "x |> 1", nil, "1.11.0"},
    {"in_empty_body", "x in []", nil, "1.11.0"},
    {"in_empty_guard", "x in []", :guard, "1.11.0"},
    {"in_list_body", "x in [1, 2]", nil, "1.11.0"},
    {"in_list_guard", "x in [1, 2]", :guard, "1.11.0"},
    {"in_long_list_body", "x in [1, 2, 3, 4, 5, 6]", nil, "1.11.0"},
    {"in_cons_guard", "x in [1 | y]", :guard, "1.11.0"},
    {"in_improper_guard", "x in [1 | 2]", :guard, "1.11.0"},
    {"in_map", "x in %{}", nil, "1.11.0"},
    {"in_range_body", "x in 1..3", nil, "1.11.0"},
    {"in_range_guard", "x in 1..3", :guard, "1.11.0"},
    {"in_one_range", "x in 1..1", nil, "1.11.0"},
    {"in_desc_range_stepless", "x in 3..1", nil, "1.11.0"},
    {"in_desc_range", "x in 3..1//-1", nil, "1.12.0"},
    {"in_stepped_range", "x in 1..9//2", nil, "1.12.0"},
    {"in_stepped_var", "x in a..b//c", nil, "1.12.0"},
    {"in_range_literal_left", "1 in 1..3", nil, "1.11.0"},
    {"in_range_call_left", "f() in 1..3", nil, "1.11.0"},
    {"in_var_range", "x in a..b", nil, "1.11.0"},
    {"in_var_body", "x in y", nil, "1.11.0"},
    {"in_var_guard", "x in y", :guard, "1.11.0"},
    {"in_alias", "x in Foo", nil, "1.11.0"},
    {"in_alias_var_head", "x in y.Foo", nil, "1.11.0"},
    {"in_nonliteral_list_body", "x in [a, 1]", nil, "1.11.0"},
    {"in_cons_body", "x in [1 | y]", nil, "1.11.0"},
    {"in_cons_list_tail_guard", "x in [1 | [2, 3]]", :guard, "1.11.0"},
    {"in_33_list_body", "x in [#{Enum.join(1..33, ", ")}]", nil, "1.11.0"},
    {"in_call_left_list_body", "f() in [1, 2]", nil, "1.11.0"},
    {"in_literal_left_list_body", "1 in [1, 2]", nil, "1.11.0"},
    {"in_var_range_guard", "x in a..b", :guard, "1.11.0"},
    {"in_step_var_guard", "x in a..b//c", :guard, "1.12.0"},
    {"in_mixed_range_guard", "x in 1..b//1", :guard, "1.12.0"},
    {"in_reordered_range_body", "x in %{first: 1, __struct__: :\"Elixir.Range\", last: 3, step: 1}", nil, "1.11.0"},
    {"in_reordered_range_guard", "x in %{first: 1, __struct__: :\"Elixir.Range\", last: 3, step: 1}", :guard, "1.11.0"},
    {"in_struct_first_range_body", "x in %{__struct__: :\"Elixir.Range\", last: 3, first: 1, step: 1}", nil, "1.11.0"},
    {"concat2", "\"a\" <> b", nil, "1.11.0"},
    {"concat3", "\"a\" <> b <> \"c\"", nil, "1.11.0"},
    {"concat_literal", "\"a\" <> \"b\"", nil, "1.11.0"},
    {"concat_atom", ":a <> b", nil, "1.11.0"},
    {"concat_integer", "1 <> b", nil, "1.11.0"},
    {"concat_var", "a <> b", nil, "1.11.0"},
    {"concat_match_prefix", "\"a\" <> b", :match, "1.11.0"},
    {"concat_match_var", "a <> b", :match, "1.11.0"},
    {"concat_pin_left", "^a <> b", :match, "1.11.0"},
    {"to_string", "to_string(x)", nil, "1.11.0"},
    {"raise_binary", "raise \"boom\"", nil, "1.11.0"},
    {"raise_interp", "raise \"a\#{b}\"", nil, "1.11.0"},
    {"raise_alias", "raise ArgumentError", nil, "1.11.0"},
    {"raise_var", "raise msg", nil, "1.11.0"},
    {"raise_alias_message", "raise ArgumentError, \"m\"", nil, "1.11.0"},
    {"raise_alias_attrs", "raise ArgumentError, message: \"m\"", nil, "1.11.0"},
    {"binding_body", "binding()", nil, "1.11.0"},
    {"binding_context", "binding(:other)", nil, "1.11.0"},
    {"binding_match", "binding()", :match, "1.11.0"},
    {"destructure_list", "destructure([a, b], l)", nil, "1.11.0"},
    {"destructure_non_list", "destructure(x, l)", nil, "1.11.0"},
    {"range_lit", "1..3", nil, "1.11.0"},
    {"range_desc_lit", "3..1", nil, "1.11.0"},
    {"range_var", "a..b", nil, "1.11.0"},
    {"range_guard", "a..b", :guard, "1.11.0"},
    {"range_match", "a..b", :match, "1.11.0"},
    {"range_float", "1.0..2", nil, "1.11.0"},
    {"range_atom", "a..:b", nil, "1.11.0"},
    {"step_range_lit", "1..9//2", nil, "1.12.0"},
    {"step_range_var", "a..b//c", nil, "1.12.0"},
    {"step_range_guard", "a..b//c", :guard, "1.12.0"},
    {"step_range_match", "a..b//c", :match, "1.12.0"},
    {"step_range_lit_var", "1..3//c", nil, "1.12.0"},
    {"step_zero", "1..3//0", nil, "1.12.0"},
    {"step_float", "1..3//1.5", nil, "1.12.0"},
    {"full_range", "..", nil, "1.14.0"}
  ]

  def run(env) do
    IO.puts("# Macro.expand_once/2, Elixir #{System.version()}, OTP #{System.otp_release()}")

    for {label, source, context, since} <- @cases do
      IO.puts("## #{label}#{if context, do: " (#{context})"}: #{source}")
      IO.puts(show(source, %{env | context: context}, since))
    end
  end

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
  `column`, and its `counter` is `:N`.
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

  def render({form, meta, args}) when is_list(meta) do
    if Keyword.keyword?(meta) do
      kept = for {key, value} <- meta, key not in [:line, :column], do: {key, if(key == :counter, do: :N, else: value)}

      "{" <> render(form) <> ", " <> render(kept) <> ", " <> render(args) <> "}"
    else
      render_tuple([form, meta, args])
    end
  end

  def render(tuple) when is_tuple(tuple), do: tuple |> Tuple.to_list() |> render_tuple()
  def render(list) when is_list(list), do: "[" <> render_list(list) <> "]"

  defp render_tuple(elements), do: "{" <> Enum.map_join(elements, ", ", &render/1) <> "}"

  defp render_list([]), do: ""
  defp render_list([last]), do: render(last)
  defp render_list([head | tail]) when is_list(tail), do: render(head) <> ", " <> render_list(tail)
  defp render_list([head | tail]), do: render(head) <> " | " <> render(tail)
end

case System.argv() do
  ["--leg"] ->
    # Variables for `binding/0,1`.
    x = 1
    y = 2
    _ = {x, y}

    KernelSummaryOutput.run(__ENV__)

  [] ->
    unless Version.match?(System.version(), ">= 1.18.0") do
      raise "generate.exs needs Elixir 1.18 or later for JSON; this is #{System.version()}"
    end

    here = __DIR__
    beam = Path.expand("../../../../../.github/ci-versions.json", here) |> File.read!() |> JSON.decode!() |> Map.fetch!("beam")

    for %{"elixir" => elixir, "otp" => otp} <- [beam["baseline"] | beam["additional"] || []] do
      major = otp |> String.split(".") |> hd()
      mise = ["exec", "elixir@#{elixir}-otp-#{major}", "erlang@#{otp}", "--", "elixir", __ENV__.file, "--leg"]

      case System.cmd("mise", mise, stderr_to_stdout: false) do
        {output, 0} ->
          File.mkdir_p!(Path.join(here, elixir))
          File.write!(Path.join([here, elixir, "A.txt"]), output)

        {output, status} ->
          raise "generate.exs under Elixir #{elixir} / OTP #{otp} exited #{status}:\n#{output}"
      end
    end
end
